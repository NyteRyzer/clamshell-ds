package com.clamshellds

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.clamshellds.data.Paths
import com.clamshellds.data.Settings
import com.clamshellds.emu.NativeBridge
import com.clamshellds.input.Action
import com.clamshellds.input.InputMapper
import com.clamshellds.ui.DualDisplay
import com.clamshellds.ui.ScreenPairLayout
import com.clamshellds.ui.SecondScreen
import java.io.File

/** Runs a game (or the console's own menu) across one or two physical screens. */
class EmulatorActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var input: InputMapper
    private var rom: File? = null
    private var started = false
    private var menuOpen = false
    private var resumed = false
    private var second: SecondScreen? = null

    // Output slots: slot 0 is on the main display, slot 1 on the second display
    // (or below/beside slot 0 on single-screen devices).
    private val surfaces = arrayOfNulls<SurfaceView>(2)
    private val surfaceReady = BooleanArray(2)
    private var swapped = false
    private lateinit var osd: TextView
    private lateinit var fpsView: TextView

    private var keyBits = 0
    private var lastTriggerL = false
    private var lastTriggerR = false
    private var fastForwardHeld = false
    private val handler = Handler(Looper.getMainLooper())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            NativeBridge.setBattery(level * 100 / scale,
                st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (started) {
                if (NativeBridge.getStopReason() == NativeBridge.STOP_POWER_OFF) {
                    finish()
                    return
                }
                if (settings.showFps) fpsView.text = "${NativeBridge.getFps()} FPS"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        input = InputMapper(settings)
        DualDisplay.immersive(window)
        Paths.ensure()

        osd = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 16f
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
        }
        fpsView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            textSize = 12f
            setPadding(12, 8, 12, 8)
            visibility = if (settings.showFps) View.VISIBLE else View.GONE
        }
        buildScreens()
        boot()
    }

    // --- Boot -------------------------------------------------------------------

    private fun boot() {
        val firmware = intent.getBooleanExtra(EXTRA_FIRMWARE, false)
        // Frontends (ES-DE, Daijisho, Beacon...) pass the ROM as an extra or as a file:// URI.
        val romPath = intent.getStringExtra(EXTRA_ROM)
            ?: intent.data?.takeIf { it.scheme == "file" || it.scheme == null }?.path
        if (intent.getBooleanExtra(EXTRA_SAFE, false)) { settings.jit = false; settings.fastMem = false }
        if (intent.hasExtra(EXTRA_JIT)) intent.getBooleanExtra(EXTRA_JIT, true).let { settings.jit = it; settings.fastMem = it }
        val label = intent.getStringExtra(EXTRA_TITLE) ?: romPath?.let { File(it).name } ?: "System Menu"
        CrashReporter.markEmulating(this, "loading $label", settings.jit)
        // Big stack: console creation runs deep inside the emulator core.
        Thread(null, {
            val err = synchronized(NativeBridge.lock) {
                settings.applyToCore()
                if (firmware || romPath == null) {
                    NativeBridge.bootFirmware()
                } else {
                    val f = File(romPath)
                    rom = f
                    val save = Paths.saveFor(f)
                    importSaveIfNeeded(f, save)
                    NativeBridge.loadGame(f.absolutePath, save.absolutePath)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err != null) {
                    CrashReporter.clearEmulating(this)
                    showError(err)
                } else {
                    synchronized(NativeBridge.lock) { NativeBridge.start() }
                    started = true
                    CrashReporter.markEmulating(this, label, settings.jit)
                    if (settings.jit && !NativeBridge.isJitActive()) showOsd("JIT unavailable on this device – running in compatibility mode")
                    if (settings.autoResume && rom != null) {
                        val auto = Paths.stateFor(rom, 0)
                        if (auto.isFile && NativeBridge.loadState(auto.absolutePath)) showOsd("Resumed where you left off")
                    }
                    if (!resumed || menuOpen) NativeBridge.pause()
                }
            }
        }, "boot", 16L * 1024 * 1024).start()
    }

    /** Pick up an existing .sav that sits next to the ROM (e.g. from another emulator). */
    private fun importSaveIfNeeded(rom: File, save: File) {
        if (save.exists()) return
        val beside = File(rom.parentFile, rom.nameWithoutExtension + ".sav")
        if (beside.isFile) runCatching { beside.copyTo(save) }
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Couldn't start")
            .setMessage(msg)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // --- Screens ----------------------------------------------------------------

    private fun screenForSlot(slot: Int): Int {
        // slot 0 shows the top screen unless the user swapped them
        val bottomOnMain = settings.swapDisplays xor swapped
        return if ((slot == 0) == bottomOnMain) 1 else 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeSurface(slot: Int): SurfaceView {
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                surfaceReady[slot] = true
                NativeBridge.setSurface(slot, holder.surface, screenForSlot(slot))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady[slot] = false
                NativeBridge.setSurface(slot, null, 0)
            }
        })
        sv.setOnTouchListener { v, e -> onScreenTouch(slot, v, e) }
        surfaces[slot] = sv
        return sv
    }

    private fun buildScreens() {
        val s0 = makeSurface(0)
        val s1 = makeSurface(1)
        val display = DualDisplay.secondaryDisplay(this, settings)

        val mainFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val overlay = FrameLayout(this)
        overlay.addView(osd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        overlay.addView(fpsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        if (display != null) {
            mainFrame.addView(ScreenPairLayout(this).apply { addView(s0) })
            mainFrame.addView(overlay)
            setContentView(mainFrame)
            val secondFrame = ScreenPairLayout(this).apply { addView(s1) }
            second = SecondScreen(this, display, secondFrame)
            if (runCatching { second?.show() }.isFailure) {
                second = null
                secondFrame.removeView(s1)
                mainFrame.removeAllViews()
                mainFrame.addView(ScreenPairLayout(this, settings.singleLayout).apply { addView(s0); addView(s1) })
                mainFrame.addView(overlay)
            }
        } else {
            mainFrame.addView(ScreenPairLayout(this, settings.singleLayout).apply { addView(s0); addView(s1) })
            mainFrame.addView(overlay)
            setContentView(mainFrame)
        }
    }

    private fun swapScreens() {
        swapped = !swapped
        for (slot in 0..1) {
            val sv = surfaces[slot] ?: continue
            if (surfaceReady[slot]) NativeBridge.setSurface(slot, sv.holder.surface, screenForSlot(slot))
        }
        showOsd("Screens swapped")
    }

    private fun onScreenTouch(slot: Int, v: View, e: MotionEvent): Boolean {
        if (screenForSlot(slot) != 1 || !started) return true
        val x = (e.x / v.width * 256f).toInt()
        val y = (e.y / v.height * 192f).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> NativeBridge.setTouch(true, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeBridge.setTouch(false, x, y)
        }
        return true
    }

    // --- Input ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = input.actionFor(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (menuOpen) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        handleAction(action, down, event.repeatCount == 0)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!menuOpen && input.onMotion(ev)) {
            if (input.triggerLeft != lastTriggerL) {
                lastTriggerL = input.triggerLeft
                input.actionFor(KeyEvent.KEYCODE_BUTTON_L2)?.let { handleAction(it, lastTriggerL, true) }
            }
            if (input.triggerRight != lastTriggerR) {
                lastTriggerR = input.triggerRight
                input.actionFor(KeyEvent.KEYCODE_BUTTON_R2)?.let { handleAction(it, lastTriggerR, true) }
            }
            pushKeys()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun handleAction(action: Action, down: Boolean, firstPress: Boolean) {
        if (action.dsBit != 0) {
            keyBits = if (down) keyBits or action.dsBit else keyBits and action.dsBit.inv()
            pushKeys()
            return
        }
        when (action) {
            Action.FAST_FORWARD -> {
                fastForwardHeld = down
                NativeBridge.setFastForward(down)
            }
            Action.MENU -> if (down && firstPress) showMenu()
            Action.SWAP_SCREENS -> if (down && firstPress) swapScreens()
            Action.QUICK_SAVE -> if (down && firstPress) saveState(1)
            Action.QUICK_LOAD -> if (down && firstPress) loadState(1)
            else -> Unit
        }
    }

    private fun pushKeys() = NativeBridge.setKeys(keyBits or input.axisBits)

    private fun releaseAll() {
        keyBits = 0
        NativeBridge.setKeys(0)
        NativeBridge.setTouch(false, 0, 0)
        if (fastForwardHeld) { fastForwardHeld = false; NativeBridge.setFastForward(false) }
    }

    // --- Menu ---------------------------------------------------------------------

    private fun showMenu() {
        if (!started || menuOpen) return
        menuOpen = true
        releaseAll()
        NativeBridge.pause()
        val entries = listOf(
            "Resume" to { },
            "Save state…" to { pickSlot(true) },
            "Load state…" to { pickSlot(false) },
            "Swap screens" to { swapScreens() },
            "Close / open lid" to { toggleLid() },
            "Screen filter: ${Settings.FILTERS[settings.filter - 1]}" to { cycleFilter() },
            "Reset" to { NativeBridge.reset(); showOsd("Reset") },
            "Quit to menu" to { finish() },
        )
        var chosen: (() -> Unit)? = null
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: if (rom == null) "System Menu" else rom!!.nameWithoutExtension)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> chosen = entries[which].second }
            .setOnDismissListener {
                menuOpen = false
                val action = chosen
                if (action != null) action()
                if (!menuOpen && !isFinishing) NativeBridge.resume()
            }
            .show()
            .also { dlg -> dlg.window?.let { DualDisplay.immersive(it) } }
    }

    private fun pickSlot(save: Boolean) {
        menuOpen = true
        NativeBridge.pause()
        val labels = (1..5).map { slot ->
            val f = Paths.stateFor(rom, slot)
            "Slot $slot" + if (f.isFile) "  –  " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(f.lastModified()) else "  –  empty"
        }
        var picked = -1
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(if (save) "Save state" else "Load state")
            .setItems(labels.toTypedArray()) { _, which -> picked = which + 1 }
            .setOnDismissListener {
                menuOpen = false
                if (picked > 0) { if (save) saveState(picked) else loadState(picked) }
                if (!isFinishing) NativeBridge.resume()
            }
            .show()
    }

    private fun saveState(slot: Int) {
        val ok = NativeBridge.saveState(Paths.stateFor(rom, slot).absolutePath)
        showOsd(if (ok) "Saved to slot $slot" else "Couldn't save state")
    }

    private fun loadState(slot: Int) {
        val f = Paths.stateFor(rom, slot)
        if (!f.isFile) { showOsd("Slot $slot is empty"); return }
        val ok = NativeBridge.loadState(f.absolutePath)
        showOsd(if (ok) "Loaded slot $slot" else "Couldn't load state")
    }

    private var lidClosed = false
    private fun toggleLid() {
        lidClosed = !lidClosed
        NativeBridge.setLidClosed(lidClosed)
        showOsd(if (lidClosed) "Lid closed (sleep)" else "Lid opened")
    }

    private fun cycleFilter() {
        settings.filter = settings.filter % 4 + 1
        NativeBridge.setScale(settings.filter)
        showOsd("Filter: ${Settings.FILTERS[settings.filter - 1]}")
    }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 2000)
    }

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    // --- Lifecycle ----------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        resumed = true
        DualDisplay.immersive(window)
        input.reload()
        DualDisplay.registerBattery(this, batteryReceiver)
        handler.post(ticker)
        if (started) CrashReporter.markEmulating(this, intent.getStringExtra(EXTRA_TITLE) ?: rom?.name ?: "System Menu", settings.jit)
        if (started && !menuOpen) NativeBridge.resume()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        runCatching { unregisterReceiver(batteryReceiver) }
        handler.removeCallbacks(ticker)
        releaseAll()
        if (started) NativeBridge.pause()
        if (started) CrashReporter.clearEmulating(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) DualDisplay.immersive(window)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (started) {
            if (settings.autoResume && rom != null) NativeBridge.saveState(Paths.stateFor(rom, 0).absolutePath)
            NativeBridge.setSurface(0, null, 0)
            NativeBridge.setSurface(1, null, 0)
            synchronized(NativeBridge.lock) { NativeBridge.shutdown() }
            started = false
            CrashReporter.clearEmulating(this)
        }
        CrashReporter.clearEmulating(this)
        second?.dismiss()
        second = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FIRMWARE = "firmware"
        const val EXTRA_SAFE = "safe"
        const val EXTRA_JIT = "jit"
    }
}
