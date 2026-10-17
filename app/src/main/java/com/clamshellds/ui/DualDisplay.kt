package com.clamshellds.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.clamshellds.data.Settings

/**
 * Finds the second physical screen on clamshell handhelds (Anbernic RG DS, AYN Thor, ...).
 * Android exposes it as a secondary display; we put the DS bottom screen there with a Presentation.
 */
object DualDisplay {
    fun secondaryDisplay(context: Context, settings: Settings): Display? {
        if (settings.displayMode == 2) return null // forced single screen
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        if (presentation != null) return presentation
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    /** Battery updates (a protected system broadcast; flagged for Android 13+ rules). */
    fun registerBattery(context: Context, receiver: android.content.BroadcastReceiver) {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
    }

    /** Hide status/navigation bars for a true "console" look. */
    @Suppress("DEPRECATION")
    fun immersive(window: android.view.Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}

/**
 * Shows a view on the second screen. The window is not focusable so the
 * gamepad keeps talking to the activity on the main screen, but it still gets touches.
 */
class SecondScreen(
    private val owner: Activity,
    display: Display,
    private val content: View,
) : Presentation(owner, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(content)
        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            DualDisplay.immersive(it)
        }
        setCancelable(false)
    }

    // If the system does give this window focus anyway, hand keys back to the activity.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = owner.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = owner.dispatchGenericMotionEvent(ev)
}
