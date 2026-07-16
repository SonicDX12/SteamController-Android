package com.steamcontroller.android.uinput

// Thin JNI binding. Loaded by the Shizuku user service process (UID shell).
// Calling these from the app UID will fail with permission denied on /dev/uinput.
object UInputNative {

    init {
        System.loadLibrary("uinput_jni")
    }

    external fun canOpen(): Boolean
    external fun createDevice(profileId: Int): Boolean
    /** Returns [strongMagnitude, weakMagnitude] in 0..65535, or null if no FF event is pending. */
    external fun pollFFEvent(): IntArray?
    external fun sendFrame(
        buttons: Int,
        leftX: Int, leftY: Int,
        rightX: Int, rightY: Int,
        leftTrigger: Int, rightTrigger: Int,
        dpadX: Int, dpadY: Int
    )
    /** Desktop mode frame. `keys` is a MouseTarget bitmask. */
    external fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int)
    external fun destroy()
}
