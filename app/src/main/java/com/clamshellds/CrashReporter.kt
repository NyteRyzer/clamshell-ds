package com.clamshellds

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.clamshellds.data.Paths
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash handler as early as possible. */
class ClamshellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/**
 * Records why the app died so the next launch can explain it, save a report,
 * and offer safe mode. Java crashes are caught directly; native (emulator core)
 * crashes are read back from Android's ApplicationExitInfo tombstones (Android 11+).
 */
object CrashReporter {
    private const val PREFS = "crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, "java-crash.txt").writeText(
                    "Thread: ${thread.name}\n" + describeSession(app) + "\n" + sw.toString()
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    // --- "currently emulating" marker -------------------------------------------

    /** Called while a game is on screen, so a sudden death can be blamed on it. */
    fun markEmulating(context: Context, what: String, jit: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("running", what).putBoolean("runningJit", jit).commit()
    }

    fun clearEmulating(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("running").commit()
    }

    private fun describeSession(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val running = p.getString("running", null) ?: return ""
        return "Was running: $running (JIT ${if (p.getBoolean("runningJit", false)) "on" else "off"})\n"
    }

    // --- Report from the previous run -------------------------------------------------

    /** A crash from the previous run that hasn't been shown yet, or null. */
    fun pendingReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sb = StringBuilder()

        val javaCrash = File(context.filesDir, "java-crash.txt")
        if (javaCrash.isFile) {
            sb.append(javaCrash.readText().take(6000))
            javaCrash.delete()
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val lastSeen = prefs.getLong("lastExitSeen", 0L)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = runCatching { am?.getHistoricalProcessExitReasons(context.packageName, 0, 5).orEmpty() }.getOrDefault(emptyList())
            val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
            exits.filter { it.timestamp > lastSeen }
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || (it.reason == ApplicationExitInfo.REASON_CRASH && sb.isEmpty()) }
                ?.let { info ->
                    if (sb.isEmpty()) sb.append(describeSession(context))
                    sb.append(describeExit(info))
                }
            if (newest > lastSeen) prefs.edit().putLong("lastExitSeen", newest).apply()
        }

        // The process died mid-game without any record we can read.
        val running = prefs.getString("running", null)
        if (sb.isEmpty() && running != null) {
            sb.append(describeSession(context)).append("The app closed while the game was running (no further details from Android).\n")
        }
        // Crashed while emulating: step down one speed-up so the next attempt can work.
        if (running != null) {
            val step = com.clamshellds.data.Settings(context).stepDownAfterCrash()
            if (step != null) sb.insert(0, "Automatic fix applied: $step\n\n")
        }
        clearEmulating(context)

        if (sb.isEmpty()) return null
        val header = "Clamshell DS ${versionName(context)} on ${Build.MANUFACTURER} ${Build.MODEL} " +
            "(Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.joinToString()})\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + "\n\n"
        val report = header + sb.toString()
        save(context, report)
        return report
    }

    fun reportFile(): File = File(Paths.root, "crash-report.txt")

    private fun save(context: Context, report: String) {
        runCatching { Paths.root.mkdirs(); reportFile().writeText(report) }
            .onFailure { runCatching { File(context.filesDir, "crash-report.txt").writeText(report) } }
    }

    private fun versionName(context: Context) =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun describeExit(info: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val sb = StringBuilder()
        val kind = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "Native crash (emulator core)" else "App crash"
        sb.append(kind).append(": ").append(info.description ?: "").append('\n')
        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
            runCatching {
                info.traceInputStream?.use { sb.append(Tombstone.summarize(it.readBytes())) }
            }
        }
        return sb.toString()
    }
}

/**
 * Minimal reader for Android's tombstone protobuf (system/core/debuggerd/proto/tombstone.proto):
 * pulls out the signal, abort message and the crashing thread's backtrace.
 */
private object Tombstone {
    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (pos < end) {
                val v = b[pos++].toInt() and 0xFF
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        /** Returns (field, wiretype, value-or-subreader). */
        fun next(): Triple<Int, Int, Any>? {
            if (!more()) return null
            val key = varint()
            val field = (key ushr 3).toInt()
            return when (val wt = (key and 7).toInt()) {
                0 -> Triple(field, wt, varint())
                1 -> { pos += 8; Triple(field, wt, 0L) }
                2 -> {
                    val len = varint().toInt()
                    val sub = Reader(b, pos, minOf(end, pos + len))
                    pos += len
                    Triple(field, wt, sub)
                }
                5 -> { pos += 4; Triple(field, wt, 0L) }
                else -> { pos = end; null }
            }
        }
        fun string() = String(b, pos, end - pos, Charsets.UTF_8)
    }

    private class Frame(var relPc: Long = 0, var fn: String = "", var fnOff: Long = 0, var file: String = "")

    fun summarize(bytes: ByteArray): String {
        val r = Reader(bytes)
        var tid = 0L
        var signal = ""
        var faultAddr = 0L
        var abortMsg = ""
        val threads = HashMap<Long, Pair<String, List<Frame>>>()

        while (true) {
            val (field, _, v) = r.next() ?: break
            when (field) {
                6 -> tid = v as Long
                10 -> (v as Reader).let { s ->
                    var name = ""; var code = ""
                    while (true) {
                        val (f, _, x) = s.next() ?: break
                        when (f) {
                            2 -> name = (x as Reader).string()
                            4 -> code = (x as Reader).string()
                            9 -> faultAddr = x as Long
                        }
                    }
                    signal = "$name $code".trim()
                }
                14 -> abortMsg = (v as Reader).string()
                16 -> (v as Reader).let { entry ->
                    var key = 0L
                    var thread: Pair<String, List<Frame>>? = null
                    while (true) {
                        val (f, _, x) = entry.next() ?: break
                        if (f == 1) key = x as Long
                        if (f == 2) thread = parseThread(x as Reader)
                    }
                    thread?.let { threads[key] = it }
                }
            }
        }

        val sb = StringBuilder()
        if (signal.isNotEmpty()) sb.append("Signal: ").append(signal).append(" at 0x").append(java.lang.Long.toHexString(faultAddr)).append('\n')
        if (abortMsg.isNotEmpty()) sb.append("Abort: ").append(abortMsg).append('\n')
        val crashing = threads[tid]
        if (crashing != null) {
            sb.append("Thread: ").append(crashing.first).append('\n')
            crashing.second.take(24).forEachIndexed { i, f ->
                sb.append(String.format(Locale.US, "  #%02d pc %08x  %s", i, f.relPc, f.file.substringAfterLast('/')))
                if (f.fn.isNotEmpty()) sb.append(" (").append(f.fn).append('+').append(f.fnOff).append(')')
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun parseThread(t: Reader): Pair<String, List<Frame>> {
        var name = ""
        val frames = ArrayList<Frame>()
        while (true) {
            val (f, _, x) = t.next() ?: break
            when (f) {
                2 -> name = (x as Reader).string()
                4 -> {
                    val fr = Frame()
                    val fx = x as Reader
                    while (true) {
                        val (ff, _, y) = fx.next() ?: break
                        when (ff) {
                            1 -> fr.relPc = y as Long
                            4 -> fr.fn = (y as Reader).string()
                            5 -> fr.fnOff = y as Long
                            6 -> fr.file = (y as Reader).string()
                        }
                    }
                    frames += fr
                }
            }
        }
        return name to frames
    }
}
