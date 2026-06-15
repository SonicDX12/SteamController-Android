package com.steamcontroller.android.uinput

// Button bit layout sent over IPC to UInputService.
// Order MUST match bit_to_key[] in uinput_jni.cpp.
object XboxButtons {
    const val A      = 1 shl 0
    const val B      = 1 shl 1
    const val X      = 1 shl 2
    const val Y      = 1 shl 3
    const val LB     = 1 shl 4
    const val RB     = 1 shl 5
    const val SELECT = 1 shl 6   // BTN_SELECT (View on Xbox One)
    const val START  = 1 shl 7   // BTN_START  (Menu on Xbox One)
    const val MODE   = 1 shl 8   // Guide/Xbox button
    const val THUMBL = 1 shl 9   // L3 click
    const val THUMBR = 1 shl 10  // R3 click
}

// Xbox 360 native axis ranges. Caller normalizes SC2026 values into these.
object XboxAxes {
    const val STICK_MIN  = -32768
    const val STICK_MAX  =  32767
    const val TRIGGER_MIN = 0
    const val TRIGGER_MAX = 255
    const val DPAD_MIN = -1
    const val DPAD_MAX =  1
}
