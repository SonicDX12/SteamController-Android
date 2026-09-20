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

    // IMU block (bytes 30-45). Only present when raw IMU output has been enabled via
    // SteamHidProtocol.setImuEnabled() — the report grows to >= 46 bytes when it is.
    // imuValid says whether the fields below carry real data or are just zero padding.
    val imuValid: Boolean = false,
    val imuTimestamp: Int = 0,   // bytes 30-33, UInt32 LE, ~16us units (stored raw)
    val accelX: Short = 0,       // bytes 34-35
    val accelY: Short = 0,       // bytes 36-37
    val accelZ: Short = 0,       // bytes 38-39
    val gyroX: Short = 0,        // bytes 40-41
    val gyroY: Short = 0,        // bytes 42-43
    val gyroZ: Short = 0,        // bytes 44-45
) {
    fun isButtonPressed(mask: Int) = (buttons and mask) != 0
}

// Source: github.com/ddeverill/SteamlessController/blob/main/src/steam/SteamController.h
// buttons = byte2 | (byte3 shl 8) | (byte4 shl 16) | (byte5 shl 24)
object Buttons {
    // byte2
    const val A            = 0x00000001
    const val B            = 0x00000002
    const val X            = 0x00000004
    const val Y            = 0x00000008
    const val QUICK_ACCESS = 0x00000010  // Quick Access Menu button (validated empirically; upstream lists this bit as TBD)
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
    const val TP_RT        = 0x00200000  // right trackpad active (touch or click)
    const val TP_RT_CLICK  = 0x00400000  // right trackpad hard press (physical click)
    const val RT_FULL      = 0x00800000  // RT full digital press

    // byte5 (flags)
    const val LS_TOUCH   = 0x01000000
    const val TP_LT      = 0x02000000  // left trackpad active (touch or click)
    const val TP_LT_CLICK= 0x04000000
    const val LT_FULL    = 0x08000000  // LT full digital press
    const val GRIP_RT    = 0x10000000
    const val GRIP_LT    = 0x20000000
}
