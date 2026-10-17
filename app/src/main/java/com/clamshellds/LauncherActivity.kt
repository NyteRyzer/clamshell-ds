package com.clamshellds

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import android.view.View
import com.clamshellds.data.Paths
import com.clamshellds.data.RomInfo
import com.clamshellds.data.RomLibrary
import com.clamshellds.data.Settings
import com.clamshellds.input.Action
import com.clamshellds.input.InputMapper
import com.clamshellds.ui.DsiMenuView
import com.clamshellds.ui.DsiTheme
import com.clamshellds.ui.DsiTopView
import com.clamshellds.ui.DualDisplay
import com.clamshellds.ui.MenuItem
import com.clamshellds.ui.MenuState
import com.clamshellds.ui.ScreenPairLayout
import com.clamshellds.ui.SecondScreen
import com.clamshellds.ui.Sfx
import kotlin.concurrent.thread

/**
 * The DSi-style home menu. On dual-screen handhelds the top screen shows the clock and
 * the selected title, the bottom screen shows the icon carousel, just like a DSi.
 */
class LauncherActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var input: InputMapper
    private lateinit var sfx: Sfx
    private val state = MenuState()
    private lateinit var topView: DsiTopView
    private lateinit var menuView: DsiMenuView
    private var second: SecondScreen? = null
    private var scanning = false
    private var askedPermission = false
    private var crashChecked = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            state.batteryPercent = level * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            state.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            state.invalidateAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        input = InputMapper(settings)
        sfx = Sfx(this, settings.menuSounds)
        topView = DsiTopView(this, state)
        menuView = DsiMenuView(this, state, onMove = { sfx.move() }, onActivate = { activate(it) })
        DualDisplay.immersive(window)
        applyProfile()
        if (hasStorageAccess()) {
            Paths.ensure()
            setItems(RomLibrary(this, settings).cached())
        }
    }

    override fun onResume() {
        super.onResume()
        DualDisplay.immersive(window)
        input.reload()
        sfx.enabled = settings.menuSounds
        applyProfile()
        showCrashReportIfAny()
        try {
            attachScreens()
        } catch (t: Throwable) {
            // Never crash-loop on display setup: show both screens on this display instead.
            second = null
            (topView.parent as? android.view.ViewGroup)?.removeView(topView)
            (menuView.parent as? android.view.ViewGroup)?.removeView(menuView)
            setContentView(ScreenPairLayout(this).apply { addView(topView); addView(menuView) })
            state.statusMessage = "Second screen unavailable: ${t.javaClass.simpleName}"
        }
        DualDisplay.registerBattery(this, batteryReceiver)
        if (hasStorageAccess()) {
            Paths.ensure()
            rescan()
        } else if (!askedPermission) {
            askedPermission = true
            requestStorageAccess()
        } else {
            state.statusMessage = "Storage access is needed to find your games"
            state.invalidateAll()
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(batteryReceiver) }
        second?.dismiss()
        second = null
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    /** If the previous run crashed, explain it, save a report and offer safe mode. */
    private fun showCrashReportIfAny() {
        if (crashChecked) return
        crashChecked = true
        val report = CrashReporter.pendingReport(this) ?: return
        val where = if (hasStorageAccess()) CrashReporter.reportFile().absolutePath else "the app's private folder"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Clamshell DS closed unexpectedly")
            .setMessage(
                "Turning on Safe mode switches off the JIT and other speed-ups so games can start reliably. " +
                    "You can turn them back on in Settings > Performance.\n\n" +
                    "A report was saved to $where.\n\n" + report
            )
            .setPositiveButton("Turn on safe mode") { _, _ -> settings.enableSafeMode() }
            .setNeutralButton("Share report") { _, _ ->
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Clamshell DS crash report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, "Share crash report"))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyProfile() {
        state.username = settings.username
        state.accent = DsiTheme.accent(settings.favoriteColor)
        state.invalidateAll()
    }

    /** Puts the top/bottom menu on the two physical screens, or both on one. */
    private fun attachScreens() {
        (topView.parent as? android.view.ViewGroup)?.removeView(topView)
        (menuView.parent as? android.view.ViewGroup)?.removeView(menuView)
        second?.dismiss()
        second = null

        val display = DualDisplay.secondaryDisplay(this, settings)
        if (display != null) {
            val mainView: View = if (settings.swapDisplays) menuView else topView
            val otherView: View = if (settings.swapDisplays) topView else menuView
            setContentView(mainView)
            val shown = runCatching {
                SecondScreen(this, display, otherView).also { it.show() }
            }
            second = shown.getOrNull()
            if (second != null) return
            // Could not use the second display: fall back to both screens on this one.
            (otherView.parent as? android.view.ViewGroup)?.removeView(otherView)
            (mainView.parent as? android.view.ViewGroup)?.removeView(mainView)
        }
        setContentView(ScreenPairLayout(this, settings.singleLayout).apply {
            addView(topView); addView(menuView)
        })
    }

    // --- Library ------------------------------------------------------------

    private fun setItems(roms: List<RomInfo>) {
        val prevItem = state.current
        val items = ArrayList<MenuItem>()
        items += MenuItem.Settings
        items += MenuItem.SystemMenu
        roms.forEach { items += MenuItem.Game(it) }
        // Empty slots after the last title, like the DSi's unused icon spaces.
        val pad = maxOf(6, 16 - items.size)
        repeat(pad) { items += MenuItem.Empty }
        state.items = items

        val keepPath = (prevItem as? MenuItem.Game)?.rom?.file?.absolutePath ?: settings.lastRom
        var idx = items.indexOfFirst { it is MenuItem.Game && it.rom.file.absolutePath == keepPath }
        if (idx < 0) idx = when {
            prevItem == MenuItem.Settings -> 0
            prevItem == MenuItem.SystemMenu -> 1
            roms.isNotEmpty() -> 2
            else -> 0
        }
        menuView.select(idx, animate = false)
        state.statusMessage = if (roms.isEmpty() && !state.scanning) "No games found yet – see Settings > Game folders" else null
        state.invalidateAll()
    }

    private fun rescan() {
        if (scanning) return
        scanning = true
        state.scanning = true
        state.invalidateAll()
        val lib = RomLibrary(this, settings)
        thread(name = "rom-scan") {
            val roms = runCatching { lib.scan() }.getOrDefault(emptyList())
            runOnUiThread {
                scanning = false
                state.scanning = false
                setItems(roms)
            }
        }
    }

    // --- Actions ------------------------------------------------------------

    private fun activate(index: Int) {
        when (val item = state.items.getOrNull(index)) {
            is MenuItem.Game -> {
                sfx.launch()
                settings.lastRom = item.rom.file.absolutePath
                startActivity(Intent(this, EmulatorActivity::class.java)
                    .putExtra(EmulatorActivity.EXTRA_ROM, item.rom.file.absolutePath)
                    .putExtra(EmulatorActivity.EXTRA_TITLE, item.rom.shortTitle))
            }
            MenuItem.Settings -> {
                sfx.select()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            MenuItem.SystemMenu -> {
                sfx.launch()
                startActivity(Intent(this, EmulatorActivity::class.java)
                    .putExtra(EmulatorActivity.EXTRA_FIRMWARE, true))
            }
            else -> sfx.back()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (input.actionFor(event.keyCode)) {
            Action.LEFT -> menuView.select(state.selected - 1)
            Action.RIGHT -> menuView.select(state.selected + 1)
            Action.L -> menuView.select(state.selected - 5)
            Action.R -> menuView.select(state.selected + 5)
            Action.UP -> menuView.select(0)
            Action.DOWN -> menuView.select(state.items.indexOfLast { it != MenuItem.Empty }.coerceAtLeast(0))
            Action.A, Action.START -> if (event.repeatCount == 0) activate(state.selected)
            Action.Y, Action.SELECT -> if (event.repeatCount == 0) activate(0)
            Action.X -> if (event.repeatCount == 0) { sfx.select(); rescan() }
            Action.MENU, Action.B -> {
                // When used as the home screen there is nowhere to go back to.
                if (event.keyCode == KeyEvent.KEYCODE_BACK && !isHomeLaunch()) return super.dispatchKeyEvent(event)
                sfx.back()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun isHomeLaunch() = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    // --- Storage permission ---------------------------------------------------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Welcome to Clamshell DS")
            .setMessage(
                "To load your games automatically, Clamshell DS needs access to your files.\n\n" +
                    "It looks in ClamshellDS/roms, Roms/NDS and similar folders on internal storage and SD cards."
            )
            .setPositiveButton("Allow") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    runCatching { startActivity(i) }.onFailure {
                        runCatching { startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            .onFailure { _ ->
                                runCatching {
                                    startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                }
                            }
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorageAccess()) { Paths.ensure(); rescan() }
    }
}
