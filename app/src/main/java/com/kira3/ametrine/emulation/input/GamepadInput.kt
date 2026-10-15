package com.kira3.ametrine.emulation.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emucore.PadButton
import com.kira3.ametrine.emucore.settings.ControlsSettings
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a physical controller's key and motion events into presses on the emulated pad.
 * Call from the activity's dispatchKeyEvent / dispatchGenericMotionEvent, on the UI thread.
 *
 * A pad can report the same control twice (the D-pad as keys, as a HAT axis and as the left
 * stick; L and R as shoulder keys, trigger keys and trigger axes), so each source is tracked
 * separately and merged; the core only hears about changes.
 */
class GamepadInput(private val core: EmulatorCore, private val port: Int = 0) {
    /** Called when someone deliberately uses a controller (not just resting stick noise). */
    var onControllerUsed: (() -> Unit)? = null

    /** The device that last sent input, for rumble. -1 until a controller is used. */
    @Volatile
    var lastDeviceId: Int = -1
        private set

    private val keysHeld = BooleanArray(BUTTONS.size)
    private var hatX = 0f
    private var hatY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f
    private var stickX = 0f
    private var stickY = 0f
    private val sentPressure = FloatArray(BUTTONS.size)

    @Volatile
    private var keyMap: Map<Int, PadButton> = DEFAULT_KEY_MAP
    private var deadzone = DEFAULT_DEADZONE
    private var triggerThreshold = DEFAULT_TRIGGER_THRESHOLD

    /** Takes the button assignments, dead zone and trigger threshold from the settings. */
    fun configure(controls: ControlsSettings) {
        val map = controls.buttonMap
        val assigned = mapOf(
            PadButton.Up to map.up, PadButton.Down to map.down, PadButton.Left to map.left,
            PadButton.Right to map.right, PadButton.A to map.a, PadButton.B to map.b,
            PadButton.X to map.x, PadButton.Y to map.y, PadButton.L to map.l,
            PadButton.R to map.r, PadButton.Start to map.start, PadButton.Select to map.select,
        )
        val newMap = HashMap<Int, PadButton>()
        for ((button, key) in assigned) if (key != KeyEvent.KEYCODE_UNKNOWN) newMap[key] = button
        deadzone = (controls.stickDeadzone / 100f).coerceIn(0f, 0.9f)
        triggerThreshold = (controls.triggerThreshold / 100f).coerceIn(0.01f, 0.95f)
        if (newMap != keyMap) {
            // A key held under the old assignments must not stay pressed.
            keyMap = newMap
            releaseAll()
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromGamepad()) return false
        val button = keyMap[event.keyCode] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                keysHeld[button.ordinal] = true
                controllerUsed(event.deviceId)
            }
            KeyEvent.ACTION_UP -> keysHeld[button.ordinal] = false
            else -> return true
        }
        flushButtons()
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return false
        }

        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        leftTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        rightTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )
        val (x, y) = deadzoned(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        stickX = x
        stickY = y
        flushButtons()
        val leftMag = hypot(x, y)
        val rightMag = 0f

        val deliberate = hatX != 0f || hatY != 0f ||
            max(leftTrigger, rightTrigger) > DELIBERATE_THRESHOLD ||
            max(leftMag, rightMag) > DELIBERATE_THRESHOLD
        if (deliberate) controllerUsed(event.deviceId)
        return true
    }

    /** Lets go of everything, e.g. when the activity loses focus mid-press. */
    fun releaseAll() {
        keysHeld.fill(false)
        hatX = 0f
        hatY = 0f
        leftTrigger = 0f
        rightTrigger = 0f
        stickX = 0f
        stickY = 0f
        flushButtons()
    }

    private fun controllerUsed(deviceId: Int) {
        lastDeviceId = deviceId
        onControllerUsed?.invoke()
    }

    private fun flushButtons() {
        for (button in BUTTONS) {
            val pressure = pressureOf(button)
            if (pressure != sentPressure[button.ordinal]) {
                sentPressure[button.ordinal] = pressure
                core.setPadButton(port, button, pressure > 0f, pressure)
            }
        }
    }

    private fun pressureOf(button: PadButton): Float {
        val fromKey = if (keysHeld[button.ordinal]) 1f else 0f
        val fromAxis = when (button) {
            // The D-pad, as a HAT axis or as the left stick.
            PadButton.Up -> if (hatY < -HAT_THRESHOLD || stickY < -STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Down -> if (hatY > HAT_THRESHOLD || stickY > STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Left -> if (hatX < -HAT_THRESHOLD || stickX < -STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Right -> if (hatX > HAT_THRESHOLD || stickX > STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.L -> triggerPressure(leftTrigger)
            PadButton.R -> triggerPressure(rightTrigger)
            else -> 0f
        }
        return max(fromKey, fromAxis)
    }

    private fun triggerPressure(value: Float): Float =
        if (value > triggerThreshold) value.coerceAtMost(1f) else 0f

    /** The stick at ([rawX], [rawY]) with a radial dead zone applied. */
    private fun deadzoned(rawX: Float, rawY: Float): Pair<Float, Float> {
        val mag = hypot(rawX, rawY)
        if (mag <= deadzone) return 0f to 0f
        val scale = ((mag - deadzone) / (1f - deadzone)).coerceAtMost(1f) / mag
        return rawX * scale to rawY * scale
    }

    private fun KeyEvent.isFromGamepad(): Boolean =
        isFromSource(InputDevice.SOURCE_GAMEPAD) || isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            isFromSource(InputDevice.SOURCE_DPAD)

    private companion object {
        val BUTTONS = PadButton.entries

        val DEFAULT_KEY_MAP = mapOf(
            // By position: Android's A is the bottom button, where the SNES pad has B.
            KeyEvent.KEYCODE_BUTTON_A to PadButton.B,
            KeyEvent.KEYCODE_BUTTON_B to PadButton.A,
            KeyEvent.KEYCODE_BUTTON_X to PadButton.Y,
            KeyEvent.KEYCODE_BUTTON_Y to PadButton.X,
            KeyEvent.KEYCODE_BUTTON_L1 to PadButton.L,
            KeyEvent.KEYCODE_BUTTON_R1 to PadButton.R,
            KeyEvent.KEYCODE_BUTTON_L2 to PadButton.L,
            KeyEvent.KEYCODE_BUTTON_R2 to PadButton.R,
            KeyEvent.KEYCODE_BUTTON_START to PadButton.Start,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadButton.Select,
            KeyEvent.KEYCODE_DPAD_UP to PadButton.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to PadButton.Down,
            KeyEvent.KEYCODE_DPAD_LEFT to PadButton.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadButton.Right,
        )

        const val DEFAULT_DEADZONE = 0.15f
        const val DEFAULT_TRIGGER_THRESHOLD = 0.08f
        const val HAT_THRESHOLD = 0.5f
        const val STICK_DPAD_THRESHOLD = 0.5f
        const val DELIBERATE_THRESHOLD = 0.5f
    }
}
