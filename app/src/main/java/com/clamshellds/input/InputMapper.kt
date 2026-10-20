package com.clamshellds.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.clamshellds.data.Settings
import com.clamshellds.emu.NativeBridge

/** Things a physical button can do. */
enum class Action(val label: String, val dsBit: Int = 0) {
    A("A", NativeBridge.KEY_A),
    B("B", NativeBridge.KEY_B),
    X("X", NativeBridge.KEY_X),
    Y("Y", NativeBridge.KEY_Y),
    UP("D-Pad Up", NativeBridge.KEY_UP),
    DOWN("D-Pad Down", NativeBridge.KEY_DOWN),
    LEFT("D-Pad Left", NativeBridge.KEY_LEFT),
    RIGHT("D-Pad Right", NativeBridge.KEY_RIGHT),
    L("L", NativeBridge.KEY_L),
    R("R", NativeBridge.KEY_R),
    START("Start", NativeBridge.KEY_START),
    SELECT("Select", NativeBridge.KEY_SELECT),
    MENU("Open menu"),
    FAST_FORWARD("Fast forward (hold)"),
    SWAP_SCREENS("Swap screens"),
    QUICK_SAVE("Quick save"),
    QUICK_LOAD("Quick load"),
}

/**
 * Maps Android key codes to DS buttons / hotkeys. Defaults follow the button labels,
 * and everything can be remapped from Settings > Controls.
 */
class InputMapper(private val settings: Settings) {
    private val map = HashMap<Int, Action>()

    init { reload() }

    fun reload() {
        map.clear()
        val custom = Action.values().mapNotNull { a ->
            val code = settings.prefs.getInt("map_" + a.name, -1)
            if (code >= 0) code to a else null
        }
        if (custom.isEmpty()) map.putAll(DEFAULTS) else custom.forEach { (c, a) -> map[c] = a }
        // Always keep a way into the menu.
        if (KeyEvent.KEYCODE_BACK !in map) map[KeyEvent.KEYCODE_BACK] = Action.MENU
    }

    fun actionFor(keyCode: Int): Action? = map[keyCode]

    fun keyFor(action: Action): Int? = map.entries.firstOrNull { it.value == action }?.key

    fun assign(action: Action, keyCode: Int) {
        // Materialise the current mapping first so assigning one button keeps the others.
        val current = HashMap(map)
        current.entries.removeAll { it.value == action || it.key == keyCode }
        current[keyCode] = action
        val ed = settings.prefs.edit()
        Action.values().forEach { ed.remove("map_" + it.name) }
        current.forEach { (k, a) -> ed.putInt("map_" + a.name, k) }
        ed.apply()
        map.clear(); map.putAll(current)
    }

    fun resetToDefaults() {
        val ed = settings.prefs.edit()
        Action.values().forEach { ed.remove("map_" + it.name) }
        ed.apply()
        reload()
    }

    // --- Analog sticks / hats -> D-pad --------------------------------------

    /** D-pad bits currently held by sticks or hat (from the last motion event). */
    var axisBits = 0
        private set

    fun onMotion(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            ev.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        if (ev.action != MotionEvent.ACTION_MOVE) return false
        val x = pick(ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_X))
        val y = pick(ev.getAxisValue(MotionEvent.AXIS_HAT_Y), ev.getAxisValue(MotionEvent.AXIS_Y))
        var bits = 0
        if (x < -0.5f) bits = bits or NativeBridge.KEY_LEFT
        if (x > 0.5f) bits = bits or NativeBridge.KEY_RIGHT
        if (y < -0.5f) bits = bits or NativeBridge.KEY_UP
        if (y > 0.5f) bits = bits or NativeBridge.KEY_DOWN
        axisBits = bits
        triggerLeft = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(ev.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        triggerRight = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(ev.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        return true
    }

    /** Analog L2/R2 (some handhelds report triggers as axes only). */
    var triggerLeft = false
        private set
    var triggerRight = false
        private set

    private fun pick(hat: Float, stick: Float) = if (kotlin.math.abs(hat) > 0.1f) hat else stick

    companion object {
        val DEFAULTS: Map<Int, Action> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Action.A,
            KeyEvent.KEYCODE_BUTTON_B to Action.B,
            KeyEvent.KEYCODE_BUTTON_X to Action.X,
            KeyEvent.KEYCODE_BUTTON_Y to Action.Y,
            KeyEvent.KEYCODE_DPAD_UP to Action.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Action.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Action.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Action.RIGHT,
            KeyEvent.KEYCODE_BUTTON_L1 to Action.L,
            KeyEvent.KEYCODE_BUTTON_R1 to Action.R,
            KeyEvent.KEYCODE_BUTTON_START to Action.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to Action.SELECT,
            KeyEvent.KEYCODE_BUTTON_L2 to Action.FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_THUMBL to Action.QUICK_SAVE,
            KeyEvent.KEYCODE_BUTTON_THUMBR to Action.QUICK_LOAD,
            KeyEvent.KEYCODE_BUTTON_MODE to Action.MENU,
            KeyEvent.KEYCODE_BACK to Action.MENU,
            // Keyboard fallback (handy with a USB keyboard or the Android emulator)
            KeyEvent.KEYCODE_X to Action.A,
            KeyEvent.KEYCODE_Z to Action.B,
            KeyEvent.KEYCODE_S to Action.X,
            KeyEvent.KEYCODE_A to Action.Y,
            KeyEvent.KEYCODE_Q to Action.L,
            KeyEvent.KEYCODE_W to Action.R,
            KeyEvent.KEYCODE_ENTER to Action.START,
            KeyEvent.KEYCODE_SHIFT_RIGHT to Action.SELECT,
            KeyEvent.KEYCODE_ESCAPE to Action.MENU,
        )
    }
}
