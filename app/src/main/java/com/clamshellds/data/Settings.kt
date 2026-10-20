package com.clamshellds.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.clamshellds.emu.NativeBridge
import java.io.File

/** Folder layout on shared storage: /storage/emulated/0/ClamshellDS/... */
object Paths {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "ClamshellDS")
    val roms: File get() = File(root, "roms")
    val saves: File get() = File(root, "saves")
    val states: File get() = File(root, "states")
    val bios: File get() = File(root, "bios")

    fun ensure() {
        listOf(root, roms, saves, states, bios).forEach { it.mkdirs() }
        val readme = File(bios, "README.txt")
        if (!readme.exists()) {
            runCatching {
                readme.writeText(
                    """
                    Clamshell DS - BIOS / firmware folder
                    =====================================

                    DS mode works without any files here (a free BIOS replacement is built in).

                    For DSi mode (the full DSi replica, including the real DSi Menu), copy dumps
                    from YOUR OWN consoles into this folder with exactly these names:

                      bios9.bin          DS ARM9 BIOS (4 KB)
                      bios7.bin          DS ARM7 BIOS (16 KB)
                      dsi_bios9.bin      DSi ARM9i BIOS (64 KB)
                      dsi_bios7.bin      DSi ARM7i BIOS (64 KB)
                      dsi_firmware.bin   DSi firmware (128 KB)
                      dsi_nand.bin       DSi NAND dump (made with GodMode9i)

                    Optional, to boot the original DS menu in DS mode:
                      firmware.bin       DS / DS Lite firmware (256 KB)

                    Dumping guide: https://dsi.cfw.guide (dumping NAND / BIOS with GodMode9i)
                    """.trimIndent() + "\n"
                )
            }
        }
    }

    fun saveFor(rom: File): File = File(saves, rom.nameWithoutExtension + ".sav")
    fun stateFor(rom: File?, slot: Int): File =
        File(states, (rom?.nameWithoutExtension ?: "system-menu") + ".ml$slot")

    fun dsiFilesPresent(): Boolean =
        listOf("bios9.bin", "bios7.bin", "dsi_bios9.bin", "dsi_bios7.bin", "dsi_firmware.bin", "dsi_nand.bin")
            .all { File(bios, it).isFile }
}

/** Known handheld quirks. */
object Device {
    private val id = (android.os.Build.MODEL + " " + android.os.Build.DEVICE + " " + android.os.Build.PRODUCT)
        .lowercase().replace(" ", "").replace("-", "").replace("_", "")

    val isRgDs: Boolean get() = "rgds" in id

    /** True where Android's default display is the lower panel of the clamshell. */
    val mainDisplayIsBottom: Boolean get() = isRgDs
}

/** All user settings, backed by SharedPreferences. */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("clamshell", Context.MODE_PRIVATE)

    init { migrate() }

    /**
     * One-off fixes for settings saved by older builds. v2: the launch crash in early builds
     * made people turn on safe mode, which leaves games very slow. That crash wasn't caused
     * by the JIT, so turn the speed-ups back on once.
     */
    private fun migrate() {
        val v = prefs.getInt("settingsVersion", 0)
        if (v < 2) {
            prefs.edit()
                .putBoolean("jit", true).putBoolean("fastMem", true).putBoolean("threaded3D", true)
                .putInt("settingsVersion", 2).apply()
        }
    }

    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun str(key: String, def: String) = prefs.getString(key, def) ?: def
    fun put(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
            }
        }.apply()
    }

    // System
    var consoleType: Int get() = int("consoleType", 0); set(v) = put("consoleType", v)       // 0 DS, 1 DSi
    var directBoot: Boolean get() = bool("directBoot", true); set(v) = put("directBoot", v)
    var useExternalDSBios: Boolean get() = bool("extBios", false); set(v) = put("extBios", v)

    // Performance
    var jit: Boolean get() = bool("jit", true); set(v) = put("jit", v)
    var fastMem: Boolean get() = bool("fastMem", true); set(v) = put("fastMem", v)
    var threaded3D: Boolean get() = bool("threaded3D", true); set(v) = put("threaded3D", v)
    var audioSync: Boolean get() = bool("audioSync", true); set(v) = put("audioSync", v)
    var audioEnabled: Boolean get() = bool("audio", true); set(v) = put("audio", v)
    var volume: Int get() = int("volume", 100); set(v) = put("volume", v)                   // percent

    // Display
    var displayMode: Int get() = int("displayMode", 0); set(v) = put("displayMode", v)     // 0 auto, 1 dual, 2 single
    // On the RG DS Android's main display is the BOTTOM panel, so swap by default there.
    var swapDisplays: Boolean get() = bool("swapDisplays", Device.mainDisplayIsBottom); set(v) = put("swapDisplays", v)
    /** Whether the user has confirmed which physical screen is on top. */
    var topScreenConfirmed: Boolean get() = bool("topScreenConfirmed", false); set(v) = put("topScreenConfirmed", v)
    var singleLayout: Int get() = int("singleLayout", 0); set(v) = put("singleLayout", v)  // 0 auto, 1 stacked, 2 side
    var filter: Int get() = int("filter", 2); set(v) = put("filter", v)                    // prescale 1..4
    var showFps: Boolean get() = bool("showFps", false); set(v) = put("showFps", v)
    var menuSounds: Boolean get() = bool("menuSounds", true); set(v) = put("menuSounds", v)

    // Profile (DS/DSi user settings)
    var username: String get() = str("username", "Player"); set(v) = put("username", v)
    var favoriteColor: Int get() = int("favColor", 11); set(v) = put("favColor", v)
    var birthMonth: Int get() = int("birthMonth", 1); set(v) = put("birthMonth", v)
    var birthDay: Int get() = int("birthDay", 1); set(v) = put("birthDay", v)
    var language: Int get() = int("language", 1); set(v) = put("language", v)

    // Library
    var romFolders: Set<String>
        get() = prefs.getStringSet("romFolders", emptySet()) ?: emptySet()
        set(v) = put("romFolders", v)
    var lastRom: String get() = str("lastRom", ""); set(v) = put("lastRom", v)
    var autoResume: Boolean get() = bool("autoResume", false); set(v) = put("autoResume", v)

    fun applyToCore() {
        NativeBridge.setBasePath(Paths.root.absolutePath)
        NativeBridge.configure(
            consoleType = consoleType,
            jit = jit,
            fastMem = fastMem,
            threaded3D = threaded3D,
            directBoot = directBoot,
            audioSync = audioSync,
            audioEnabled = audioEnabled,
            volume = volume.coerceIn(0, 100) * 256 / 100,
            scale = filter,
            username = username,
            language = language,
            favoriteColor = favoriteColor,
            birthMonth = birthMonth,
            birthDay = birthDay,
            useExternalDSBios = useExternalDSBios,
            biosDir = Paths.bios.absolutePath,
        )
    }

    /**
     * After a crash during emulation, turn off the riskiest remaining speed-up.
     * Returns a description of what changed, or null if nothing was left to turn off.
     */
    fun stepDownAfterCrash(): String? = when {
        fastMem && jit -> { fastMem = false; "turned off JIT fast memory" }
        jit -> { jit = false; "turned off the JIT (games run slower but more safely)" }
        threaded3D -> { threaded3D = false; "turned off threaded 3D rendering" }
        consoleType == 1 -> { consoleType = 0; "switched from DSi mode to DS mode" }
        else -> null
    }

    /** Slowest but most compatible settings, offered after a crash. */
    fun enableSafeMode() {
        jit = false
        fastMem = false
        threaded3D = false
        consoleType = 0
        displayMode = 0
    }

    companion object {
        val LANGUAGES = listOf("Japanese", "English", "French", "German", "Italian", "Spanish")
        val FILTERS = listOf("Smooth", "Sharp (2x)", "Sharper (3x)", "Crisp (4x)")
    }
}
