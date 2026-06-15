package com.steamcontroller.android

import android.content.Context
import com.steamcontroller.android.input.DEFAULT_MAPPING
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.uinput.GamepadProfile

enum class Transport(val id: Int, val displayName: String) {
    USB(0, "USB / Puck"),
    BLUETOOTH(1, "Bluetooth");
    companion object {
        fun fromId(id: Int) = values().firstOrNull { it.id == id } ?: USB
    }
}

object Prefs {
    private const val NAME = "steam_controller_prefs"
    private const val KEY_PROFILE_ID = "gamepad_profile_id"
    private const val KEY_TRANSPORT  = "transport"
    private const val KEY_BT_ADDRESS = "bt_device_address"

    private const val KEY_L_CENTER_X = "calib_l_cx"
    private const val KEY_L_CENTER_Y = "calib_l_cy"
    private const val KEY_L_DEADZONE = "calib_l_dz"
    private const val KEY_L_INVERT_Y = "calib_l_invy"

    private const val KEY_R_CENTER_X = "calib_r_cx"
    private const val KEY_R_CENTER_Y = "calib_r_cy"
    private const val KEY_R_DEADZONE = "calib_r_dz"
    private const val KEY_R_INVERT_Y = "calib_r_invy"

    private const val KEY_RUMBLE_INTENSITY = "rumble_intensity"  // 0..100

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getProfile(context: Context): GamepadProfile {
        val id = prefs(context).getInt(KEY_PROFILE_ID, GamepadProfile.XBOX_360.id)
        return GamepadProfile.fromId(id)
    }

    fun setProfile(context: Context, profile: GamepadProfile) {
        prefs(context).edit().putInt(KEY_PROFILE_ID, profile.id).apply()
    }

    fun getTransport(context: Context): Transport =
        Transport.fromId(prefs(context).getInt(KEY_TRANSPORT, Transport.USB.id))

    fun setTransport(context: Context, t: Transport) {
        prefs(context).edit().putInt(KEY_TRANSPORT, t.id).apply()
    }

    fun getBluetoothAddress(context: Context): String? =
        prefs(context).getString(KEY_BT_ADDRESS, null)

    fun setBluetoothAddress(context: Context, address: String?) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getLeftCalibration(context: Context): StickCalibration = prefs(context).run {
        StickCalibration(
            centerX = getInt(KEY_L_CENTER_X, 0),
            centerY = getInt(KEY_L_CENTER_Y, 0),
            deadzonePercent = getInt(KEY_L_DEADZONE, 8),
            invertY = getBoolean(KEY_L_INVERT_Y, false)
        )
    }

    fun setLeftCalibration(context: Context, c: StickCalibration) {
        prefs(context).edit()
            .putInt(KEY_L_CENTER_X, c.centerX)
            .putInt(KEY_L_CENTER_Y, c.centerY)
            .putInt(KEY_L_DEADZONE, c.deadzonePercent)
            .putBoolean(KEY_L_INVERT_Y, c.invertY)
            .apply()
    }

    fun getRightCalibration(context: Context): StickCalibration = prefs(context).run {
        StickCalibration(
            centerX = getInt(KEY_R_CENTER_X, 0),
            centerY = getInt(KEY_R_CENTER_Y, 0),
            deadzonePercent = getInt(KEY_R_DEADZONE, 8),
            invertY = getBoolean(KEY_R_INVERT_Y, false)
        )
    }

    fun setRightCalibration(context: Context, c: StickCalibration) {
        prefs(context).edit()
            .putInt(KEY_R_CENTER_X, c.centerX)
            .putInt(KEY_R_CENTER_Y, c.centerY)
            .putInt(KEY_R_DEADZONE, c.deadzonePercent)
            .putBoolean(KEY_R_INVERT_Y, c.invertY)
            .apply()
    }

    // ─── Rumble intensity (0..100, % of game-requested magnitude) ───────────
    fun getRumbleIntensity(context: Context): Int =
        prefs(context).getInt(KEY_RUMBLE_INTENSITY, 100).coerceIn(0, 100)

    fun setRumbleIntensity(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_RUMBLE_INTENSITY, percent.coerceIn(0, 100)).apply()
    }

    // ─── Button mapping ──────────────────────────────────────────────────────
    private fun mapKey(source: SteamButton) = "map_${source.name}"

    fun getMapping(context: Context, source: SteamButton): XboxTarget {
        val default = DEFAULT_MAPPING[source] ?: XboxTarget.NONE
        val ordinal = prefs(context).getInt(mapKey(source), default.ordinal)
        return XboxTarget.values().getOrNull(ordinal) ?: default
    }

    fun setMapping(context: Context, source: SteamButton, target: XboxTarget) {
        prefs(context).edit().putInt(mapKey(source), target.ordinal).apply()
    }

    fun getAllMappings(context: Context): Map<SteamButton, XboxTarget> =
        SteamButton.values().associateWith { getMapping(context, it) }

    fun resetMappings(context: Context) {
        val edit = prefs(context).edit()
        SteamButton.values().forEach { edit.remove(mapKey(it)) }
        edit.apply()
    }
}
