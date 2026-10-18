package com.clamshellds.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.storage.StorageManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** One DS game found on storage, with the title and icon from its banner. */
class RomInfo(
    val file: File,
    val size: Long,
    val modified: Long,
    val title: String,      // banner title, may contain '\n'
    val gameCode: String,
    val iconPixels: IntArray?, // 32x32 ARGB, or null
) {
    val icon: Bitmap? by lazy {
        iconPixels?.let { Bitmap.createBitmap(it, 32, 32, Bitmap.Config.ARGB_8888) }
    }
    val titleLines: List<String> get() = title.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val shortTitle: String get() = titleLines.firstOrNull() ?: file.nameWithoutExtension
}

object RomParser {
    private val EXTENSIONS = setOf("nds", "dsi", "srl", "ids")

    fun isRom(f: File) = f.isFile && f.extension.lowercase() in EXTENSIONS && f.length() >= 0x200

    fun parse(f: File): RomInfo? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(0x200)
            raf.readFully(header)
            val headerTitle = String(header, 0, 12, Charsets.US_ASCII).trim { it <= ' ' }
            val gameCode = String(header, 0x0C, 4, Charsets.US_ASCII).trim { it <= ' ' }
            val bannerOffset = le32(header, 0x68).toLong() and 0xFFFFFFFFL

            var title = ""
            var icon: IntArray? = null
            if (bannerOffset in 0x200 until f.length() - 0x840) {
                val banner = ByteArray(0x840)
                raf.seek(bannerOffset)
                raf.readFully(banner)
                icon = decodeIcon(banner)
                // English, then Japanese, then the other languages
                for (lang in intArrayOf(1, 0, 2, 3, 4, 5)) {
                    val t = decodeTitle(banner, 0x240 + lang * 0x100)
                    if (t.isNotBlank()) { title = t; break }
                }
            }
            if (title.isBlank()) title = headerTitle.ifBlank { f.nameWithoutExtension }
            RomInfo(f, f.length(), f.lastModified(), title, gameCode, icon)
        }
    }.getOrNull()

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun decodeTitle(b: ByteArray, o: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < 128) {
            val c = le16(b, o + i * 2)
            if (c == 0) break
            sb.append(c.toChar())
            i++
        }
        return sb.toString().trim()
    }

    /** 32x32 4bpp tiled icon at 0x20, BGR555 palette at 0x220. */
    private fun decodeIcon(b: ByteArray): IntArray {
        val palette = IntArray(16) { i ->
            if (i == 0) 0 else {
                val c = le16(b, 0x220 + i * 2)
                val r = (c and 0x1F) * 255 / 31
                val g = ((c shr 5) and 0x1F) * 255 / 31
                val bl = ((c shr 10) and 0x1F) * 255 / 31
                (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        val out = IntArray(32 * 32)
        for (tile in 0 until 16) {
            val tx = (tile % 4) * 8
            val ty = (tile / 4) * 8
            for (p in 0 until 32) {
                val byte = b[0x20 + tile * 32 + p].toInt() and 0xFF
                val px = (p * 2) % 8
                val py = (p * 2) / 8
                out[(ty + py) * 32 + tx + px] = palette[byte and 0xF]
                out[(ty + py) * 32 + tx + px + 1] = palette[byte shr 4]
            }
        }
        return out
    }
}

/**
 * Finds DS games automatically. Folders picked in Settings and ClamshellDS/roms are always
 * searched; then every storage volume (internal + SD cards) is walked, skipping photo/music/
 * video and Android system folders, so games are found wherever they were copied.
 */
class RomLibrary(private val context: Context, private val settings: Settings) {
    private val cacheFile = File(context.filesDir, "library.cache")

    /** Folders the user added plus the app's own ROM folder. */
    fun explicitFolders(): List<File> {
        val dirs = LinkedHashSet<File>()
        dirs += Paths.roms
        settings.romFolders.forEach { dirs += File(it) }
        return dirs.filter { it.isDirectory }
    }

    fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots += android.os.Environment.getExternalStorageDirectory()
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { sm?.storageVolumes?.forEach { v -> v.directory?.let { roots += it } } }
        }
        // Belt and braces: some handheld ROMs don't report SD cards as storage volumes.
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && f.canRead()) roots += f
        }
        return roots.filter { it.isDirectory }
    }

    private class Walk(val cache: Map<String, RomInfo>) {
        val found = LinkedHashMap<String, RomInfo>()
        val visited = HashSet<String>()
        val perFolder = LinkedHashMap<String, Int>()
        var folders = 0
        var errors = 0
    }

    /** Scans storage; banner data is cached so repeat scans are fast. */
    fun scan(): List<RomInfo> {
        val w = Walk(loadCache())
        for (dir in explicitFolders()) walk(dir, 0, w)
        for (root in storageRoots()) walk(root, 0, w)
        val list = w.found.values.sortedBy { it.shortTitle.lowercase() }
        saveCache(list)
        saveReport(w, list.size)
        android.util.Log.i("ClamshellDS", "Library scan: ${list.size} games in ${w.folders} folders")
        return list
    }

    private fun walk(dir: File, depth: Int, w: Walk) {
        if (depth > MAX_DEPTH || w.folders >= MAX_FOLDERS) return
        val canonical = runCatching { dir.canonicalPath }.getOrElse { w.errors++; return }
        if (!w.visited.add(canonical)) return // already seen (symlinks, duplicate roots)
        w.folders++
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in files) {
            try {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (name.lowercase() !in SKIP_DIRS) walk(f, depth + 1, w)
                    continue
                }
                if (!RomParser.isRom(f)) continue
                val key = f.canonicalPath
                if (w.found.containsKey(key)) continue
                val cached = w.cache[key]
                val info = if (cached != null && cached.size == f.length() && cached.modified == f.lastModified()) cached
                else RomParser.parse(f)
                if (info != null) {
                    w.found[key] = info
                    val folder = f.parentFile?.absolutePath ?: "?"
                    w.perFolder[folder] = (w.perFolder[folder] ?: 0) + 1
                }
            } catch (t: Throwable) {
                w.errors++ // one unreadable file must never hide the rest of the library
            }
        }
    }

    private fun saveReport(w: Walk, total: Int) {
        val sb = StringBuilder()
        sb.append("Found $total game").append(if (total == 1) "" else "s")
            .append(" after searching ${w.folders} folders.\n\n")
        if (w.perFolder.isNotEmpty()) {
            sb.append("Games found in:\n")
            w.perFolder.forEach { (folder, n) -> sb.append("• ").append(folder).append("  ($n)\n") }
            sb.append('\n')
        }
        sb.append("Storage searched:\n")
        storageRoots().forEach { sb.append("• ").append(it.absolutePath).append('\n') }
        if (w.errors > 0) sb.append("\n${w.errors} files or folders couldn't be read.\n")
        settings.put("lastScanReport", sb.toString())
    }

    fun lastReport(): String = settings.prefs.getString("lastScanReport", null)
        ?: "No scan has run yet. Open the home menu once with file access allowed."

    fun loadCache(): Map<String, RomInfo> = runCatching {
        DataInputStream(cacheFile.inputStream().buffered()).use { inp ->
            if (inp.readInt() != CACHE_VERSION) return emptyMap()
            val n = inp.readInt()
            val map = HashMap<String, RomInfo>(n)
            repeat(n) {
                val path = inp.readUTF()
                val size = inp.readLong()
                val mod = inp.readLong()
                val title = inp.readUTF()
                val code = inp.readUTF()
                val hasIcon = inp.readBoolean()
                val icon = if (hasIcon) IntArray(1024) { inp.readInt() } else null
                val f = File(path)
                map[f.canonicalPath] = RomInfo(f, size, mod, title, code, icon)
            }
            map
        }
    }.getOrDefault(emptyMap())

    /** Games from the last scan, for an instant launcher start. */
    fun cached(): List<RomInfo> = loadCache().values.filter { it.file.isFile }.sortedBy { it.shortTitle.lowercase() }

    private fun saveCache(list: List<RomInfo>) {
        runCatching {
            DataOutputStream(cacheFile.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION)
                out.writeInt(list.size)
                for (r in list) {
                    out.writeUTF(r.file.absolutePath)
                    out.writeLong(r.size)
                    out.writeLong(r.modified)
                    out.writeUTF(r.title)
                    out.writeUTF(r.gameCode)
                    out.writeBoolean(r.iconPixels != null)
                    r.iconPixels?.forEach { out.writeInt(it) }
                }
            }
        }
    }

    companion object {
        private const val CACHE_VERSION = 2
        private const val MAX_DEPTH = 6
        private const val MAX_FOLDERS = 50_000
        /** Never contain DS games, and some are huge. Matched case-insensitively at any depth. */
        private val SKIP_DIRS = setOf(
            "android", "dcim", "pictures", "movies", "music", "podcasts", "ringtones", "alarms",
            "notifications", "recordings", "audiobooks", "screenshots", "lost.dir", "\$recycle.bin",
            "system volume information",
        )
    }
}
