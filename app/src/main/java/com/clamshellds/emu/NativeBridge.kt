package com.clamshellds.emu

import android.view.Surface

/** Thin JNI wrapper around the melonDS-based core in libclamshellds.so. */
object NativeBridge {
    init {
        System.loadLibrary("clamshellds")
    }

    // melonDS key bits (see NDS::SetKeyMask)
    const val KEY_A = 1 shl 0
    const val KEY_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
    const val KEY_R = 1 shl 8
    const val KEY_L = 1 shl 9
    const val KEY_X = 1 shl 10
    const val KEY_Y = 1 shl 11

    // melonDS Platform::StopReason
    const val STOP_POWER_OFF = 4

    /** Serialises boot / shutdown between overlapping activity instances. */
    val lock = Any()

    external fun setBasePath(path: String)

    external fun configure(
        consoleType: Int,
        jit: Boolean,
        fastMem: Boolean,
        threaded3D: Boolean,
        directBoot: Boolean,
        audioSync: Boolean,
        audioEnabled: Boolean,
        volume: Int,
        scale: Int,
        username: String,
        language: Int,
        favoriteColor: Int,
        birthMonth: Int,
        birthDay: Int,
        useExternalDSBios: Boolean,
        biosDir: String,
    )

    /** @return null on success, or an error message. */
    external fun loadGame(romPath: String, savePath: String): String?

    /** Boots the console's own system menu (needs BIOS/firmware dumps). */
    external fun bootFirmware(): String?

    external fun start()
    external fun pause()
    external fun resume()
    external fun shutdown()
    external fun reset()

    external fun isRunning(): Boolean
    external fun getStopReason(): Int
    external fun getFps(): Int
    external fun isJitActive(): Boolean

    /** slot: 0/1 output surface; screen: 0 = top, 1 = bottom. */
    external fun setSurface(slot: Int, surface: Surface?, screen: Int)

    external fun setKeys(pressedMask: Int)
    external fun setTouch(down: Boolean, x: Int, y: Int)
    external fun setFastForward(enabled: Boolean)
    external fun setLidClosed(closed: Boolean)
    external fun setBattery(percent: Int, charging: Boolean)
    external fun setScale(scale: Int)

    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean

    /** Copies the last frame of a screen (0xAARRGGBB, 256x192) into [out]. */
    external fun copyScreen(screen: Int, out: IntArray): Boolean
}
