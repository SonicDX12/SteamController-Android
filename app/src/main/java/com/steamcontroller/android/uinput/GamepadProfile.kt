package com.steamcontroller.android.uinput

enum class GamepadProfile(
    val id: Int,
    val displayName: String,
    val vid: Int,
    val pid: Int
) {
    XBOX_360   (0, "Xbox 360 Controller",     0x045E, 0x028E),
    XBOX_ONE   (1, "Xbox One Controller",     0x045E, 0x02EA),
    DUALSHOCK_4(2, "Sony DualShock 4",        0x054C, 0x05C4),
    DUALSENSE  (3, "Sony DualSense (PS5)",    0x054C, 0x0CE6);

    companion object {
        fun fromId(id: Int): GamepadProfile = values().firstOrNull { it.id == id } ?: XBOX_360
    }
}
