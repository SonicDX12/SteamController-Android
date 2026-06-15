package com.steamcontroller.android.parser

data class SteamControllerState(
    val buttons: Int = 0,

    val leftTrigger: Int = 0,    // bytes 6-7, 16-bit, range 0-32767
    val rightTrigger: Int = 0,   // bytes 8-9, 16-bit

    val leftJoyX: Short = 0,     // bytes 10-11
    val leftJoyY: Short = 0,     // bytes 12-13
    val rightJoyX: Short = 0,    // bytes 14-15
    val rightJoyY: Short = 0,    // bytes 16-17

    val leftPadX: Short = 0,     // bytes 18-19
    val leftPadY: Short = 0,     // bytes 20-21
    val leftPadContact: Int = 0, // bytes 22-23

    val rightPadX: Short = 0,    // bytes 24-25
    val rightPadY: Short = 0,    // bytes 26-27
    val rightPadContact: Int = 0,// bytes 28-29

    val quatW: Short = 0,        // bytes 32-33  IMU quaternion
    val quatX: Short = 0,        // bytes 34-35
    val quatY: Short = 0,        // bytes 36-37
    val quatZ: Short = 0,        // bytes 38-39

    val battery: Int = -1        // bytes 44-45 UInt16 LE (0xFFFF = full). -1 = unknown
) {
    fun isButtonPressed(mask: Int) = (buttons and mask) != 0

    /** Battery as 0..100, or null if unknown. 0xFFFF raw = 100%. */
    val batteryPercent: Int?
        get() = if (battery < 0) null else (battery * 100 / 0xFFFF).coerceIn(0, 100)
}

// Source: github.com/ddeverill/SteamlessController/blob/main/src/steam/SteamController.h
// buttons = byte2 | (byte3 shl 8) | (byte4 shl 16) | (byte5 shl 24)
object Buttons {
    // byte2
    const val A            = 0x00000001
    const val B            = 0x00000002
    const val X            = 0x00000004
    const val Y            = 0x00000008
    const val QUICK_ACCESS = 0x00000010  // Quick Access Menu button (validated empirically)
    const val RS           = 0x00000020  // right stick click
    const val MENU         = 0x00000040  // Start/Menu button
    const val R4           = 0x00000080  // extra back button

    // byte3
    const val R5         = 0x00000100
    const val RB         = 0x00000200
    const val DPAD_DOWN  = 0x00000400
    const val DPAD_RIGHT = 0x00000800
    const val DPAD_LEFT  = 0x00001000
    const val DPAD_UP    = 0x00002000
    const val VIEW       = 0x00004000  // Select/View button
    const val LS         = 0x00008000  // left stick click

    // byte4
    const val STEAM        = 0x00010000  // Steam guide button
    const val L4           = 0x00020000
    const val L5           = 0x00040000
    const val LB           = 0x00080000
    const val RS_TOUCH     = 0x00100000
    const val TP_RT        = 0x00200000  // right trackpad touch
    // bit 0x00400000 = unused / unknown
    const val RT_FULL      = 0x00800000  // RT full digital press

    // byte5 (flags)
    const val LS_TOUCH   = 0x01000000
    const val TP_LT      = 0x02000000  // left trackpad touch
    const val TP_LT_CLICK= 0x04000000
    const val GRIP_RT    = 0x10000000
    const val GRIP_LT    = 0x20000000
}
