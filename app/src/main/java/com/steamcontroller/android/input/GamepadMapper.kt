package com.steamcontroller.android.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState

object GamepadMapper {

    private const val JOY_MAX  = 32767f
    private const val TRIG_MAX = 32767f  // triggers are 16-bit 0-32767

    data class MappedInput(
        val keyEvents: List<Pair<Int, Boolean>>,
        val axes: Map<Int, Float>
    )

    // Axes only — called every frame for smooth analog input.
    // Optional calibration applied if provided (uinput path supplies its own; legacy passes null).
    fun axes(
        state: SteamControllerState,
        leftCal: StickCalibration = StickCalibration.DEFAULT,
        rightCal: StickCalibration = StickCalibration.DEFAULT
    ): Map<Int, Float> {
        val (lx, ly) = leftCal.apply(state.leftJoyX.toInt(),  state.leftJoyY.toInt())
        val (rx, ry) = rightCal.apply(state.rightJoyX.toInt(), state.rightJoyY.toInt())
        return mapOf(
            MotionEvent.AXIS_X        to  (lx / JOY_MAX),
            MotionEvent.AXIS_Y        to -(ly / JOY_MAX),
            MotionEvent.AXIS_Z        to  (rx / JOY_MAX),
            MotionEvent.AXIS_RZ       to -(ry / JOY_MAX),
            MotionEvent.AXIS_LTRIGGER to  (state.leftTrigger  / TRIG_MAX),
            MotionEvent.AXIS_RTRIGGER to  (state.rightTrigger / TRIG_MAX),
            MotionEvent.AXIS_HAT_X    to  dpadHatX(state),
            MotionEvent.AXIS_HAT_Y    to  dpadHatY(state)
        )
    }

    // Buttons only — compared against confirmed (debounced) state
    fun buttons(current: SteamControllerState, confirmed: SteamControllerState): List<Pair<Int, Boolean>> {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        fun button(mask: Int, keyCode: Int) {
            val now = current.isButtonPressed(mask)
            val was = confirmed.isButtonPressed(mask)
            if (now != was) keys.add(keyCode to now)
        }
        button(Buttons.A,          KeyEvent.KEYCODE_BUTTON_A)
        button(Buttons.B,          KeyEvent.KEYCODE_BUTTON_B)
        button(Buttons.X,          KeyEvent.KEYCODE_BUTTON_X)
        button(Buttons.Y,          KeyEvent.KEYCODE_BUTTON_Y)
        button(Buttons.LB,         KeyEvent.KEYCODE_BUTTON_L1)
        button(Buttons.RB,         KeyEvent.KEYCODE_BUTTON_R1)
        button(Buttons.MENU,       KeyEvent.KEYCODE_BUTTON_START)
        button(Buttons.VIEW,       KeyEvent.KEYCODE_BUTTON_SELECT)
        button(Buttons.STEAM,      KeyEvent.KEYCODE_BUTTON_MODE)
        button(Buttons.LS,         KeyEvent.KEYCODE_BUTTON_THUMBL)
        button(Buttons.RS,         KeyEvent.KEYCODE_BUTTON_THUMBR)
        button(Buttons.DPAD_UP,    KeyEvent.KEYCODE_DPAD_UP)
        button(Buttons.DPAD_DOWN,  KeyEvent.KEYCODE_DPAD_DOWN)
        button(Buttons.DPAD_LEFT,  KeyEvent.KEYCODE_DPAD_LEFT)
        button(Buttons.DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        return keys
    }

    fun map(state: SteamControllerState, previous: SteamControllerState?): MappedInput {
        val keys = mutableListOf<Pair<Int, Boolean>>()

        fun button(mask: Int, keyCode: Int) {
            val now = state.isButtonPressed(mask)
            val was = previous?.isButtonPressed(mask) ?: false
            if (now != was) keys.add(keyCode to now)
        }

        button(Buttons.A,          KeyEvent.KEYCODE_BUTTON_A)
        button(Buttons.B,          KeyEvent.KEYCODE_BUTTON_B)
        button(Buttons.X,          KeyEvent.KEYCODE_BUTTON_X)
        button(Buttons.Y,          KeyEvent.KEYCODE_BUTTON_Y)

        button(Buttons.LB,         KeyEvent.KEYCODE_BUTTON_L1)
        button(Buttons.RB,         KeyEvent.KEYCODE_BUTTON_R1)

        button(Buttons.MENU,       KeyEvent.KEYCODE_BUTTON_START)
        button(Buttons.VIEW,       KeyEvent.KEYCODE_BUTTON_SELECT)
        button(Buttons.STEAM,      KeyEvent.KEYCODE_BUTTON_MODE)

        button(Buttons.LS,         KeyEvent.KEYCODE_BUTTON_THUMBL)
        button(Buttons.RS,         KeyEvent.KEYCODE_BUTTON_THUMBR)

        button(Buttons.DPAD_UP,    KeyEvent.KEYCODE_DPAD_UP)
        button(Buttons.DPAD_DOWN,  KeyEvent.KEYCODE_DPAD_DOWN)
        button(Buttons.DPAD_LEFT,  KeyEvent.KEYCODE_DPAD_LEFT)
        button(Buttons.DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)

        val axes = mapOf(
            MotionEvent.AXIS_X        to  (state.leftJoyX  / JOY_MAX),
            MotionEvent.AXIS_Y        to -(state.leftJoyY  / JOY_MAX),
            MotionEvent.AXIS_Z        to  (state.rightJoyX / JOY_MAX),
            MotionEvent.AXIS_RZ       to -(state.rightJoyY / JOY_MAX),
            MotionEvent.AXIS_LTRIGGER to  (state.leftTrigger  / TRIG_MAX),
            MotionEvent.AXIS_RTRIGGER to  (state.rightTrigger / TRIG_MAX),
            MotionEvent.AXIS_HAT_X    to  dpadHatX(state),
            MotionEvent.AXIS_HAT_Y    to  dpadHatY(state)
        )

        return MappedInput(keys, axes)
    }

    private fun dpadHatX(s: SteamControllerState) = when {
        s.isButtonPressed(Buttons.DPAD_RIGHT) ->  1f
        s.isButtonPressed(Buttons.DPAD_LEFT)  -> -1f
        else -> 0f
    }

    private fun dpadHatY(s: SteamControllerState) = when {
        s.isButtonPressed(Buttons.DPAD_DOWN) ->  1f
        s.isButtonPressed(Buttons.DPAD_UP)   -> -1f
        else -> 0f
    }
}
