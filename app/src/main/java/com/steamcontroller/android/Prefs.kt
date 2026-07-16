package com.steamcontroller.android

import android.content.Context
import com.steamcontroller.android.input.DEFAULT_MAPPING
import com.steamcontroller.android.input.NamedProfile
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
    private const val KEY_LAST_GAMEPAD_PROFILE_ID = "last_gamepad_profile_id"
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
    private const val KEY_MOUSE_SENSITIVITY = "mouse_sensitivity_x10"  // 1..30 → 0.1x..3.0x
    private const val KEY_TRACKPAD_AS_MOUSE = "trackpad_as_mouse_gamepad"  // sidecar mouse while in Xbox/PS profiles
    private const val KEY_NAMED_PROFILES = "named_profiles_json"
    private const val KEY_ACTIVE_NAMED_PROFILE_ID = "active_named_profile_id"

    private const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
    private const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"

    private const val KEY_SAVED_SHOW_IME_HARD_KB = "saved_show_ime_with_hard_keyboard"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getProfile(context: Context): GamepadProfile {
        val id = prefs(context).getInt(KEY_PROFILE_ID, GamepadProfile.XBOX_360.id)
        return GamepadProfile.fromId(id)
    }

    fun setProfile(context: Context, profile: GamepadProfile) {
        val edit = prefs(context).edit().putInt(KEY_PROFILE_ID, profile.id)
        // Remember the last *non-Mouse* profile so the Gamepad/Desktop toggle on
        // the new UI can revert to it when the user flips back to Gamepad mode.
        if (!profile.isMouseMode) {
            edit.putInt(KEY_LAST_GAMEPAD_PROFILE_ID, profile.id)
        }
        edit.apply()
    }

    /** The last gamepad profile the user actively chose (defaults to Xbox 360). */
    fun getLastGamepadProfile(context: Context): GamepadProfile {
        val id = prefs(context).getInt(KEY_LAST_GAMEPAD_PROFILE_ID, GamepadProfile.XBOX_360.id)
        val p = GamepadProfile.fromId(id)
        return if (p.isMouseMode) GamepadProfile.XBOX_360 else p
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

    /** Mouse cursor sensitivity multiplier, 0.1x..3.0x. */
    fun getMouseSensitivity(context: Context): Float =
        (prefs(context).getInt(KEY_MOUSE_SENSITIVITY, 10).coerceIn(1, 30)) / 10f

    fun setMouseSensitivity(context: Context, multiplier: Float) {
        val v = (multiplier * 10f).toInt().coerceIn(1, 30)
        prefs(context).edit().putInt(KEY_MOUSE_SENSITIVITY, v).apply()
    }

    /** Whether the right trackpad / left trackpad drive a sidecar mouse cursor while a gamepad profile is active. */
    fun getTrackpadAsMouseInGamepad(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRACKPAD_AS_MOUSE, true)

    fun setTrackpadAsMouseInGamepad(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRACKPAD_AS_MOUSE, enabled).apply()
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

    // ─── Named profiles ──────────────────────────────────────────────────────
    // Profiles are stored as a single JSON array under KEY_NAMED_PROFILES.
    // Loading a profile overwrites every "live" preference key it captures.

    fun listNamedProfiles(context: Context): List<NamedProfile> =
        NamedProfile.listFromJson(prefs(context).getString(KEY_NAMED_PROFILES, "") ?: "")

    fun saveNamedProfile(context: Context, profile: NamedProfile) {
        val existing = listNamedProfiles(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == profile.id }
        if (idx >= 0) existing[idx] = profile else existing.add(profile)
        persistNamedProfiles(context, existing)
    }

    fun deleteNamedProfile(context: Context, id: String) {
        val existing = listNamedProfiles(context).filter { it.id != id }
        persistNamedProfiles(context, existing)
        if (getActiveNamedProfileId(context) == id) setActiveNamedProfileId(context, null)
    }

    private fun persistNamedProfiles(context: Context, profiles: List<NamedProfile>) {
        prefs(context).edit().putString(KEY_NAMED_PROFILES, NamedProfile.listToJson(profiles)).apply()
    }

    fun getActiveNamedProfileId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_NAMED_PROFILE_ID, null)

    fun setActiveNamedProfileId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_NAMED_PROFILE_ID, id).apply()
    }

    /** Snapshot every live preference into a new NamedProfile under `name`.
     *  Note: transport (USB/BT) is intentionally NOT captured — it's a physical-link
     *  choice, not a game-tuning one, so loading a profile shouldn't drag the user
     *  back to USB when they're docked over BT. The field stays in NamedProfile
     *  schema for backward compat with V1.2 saves but isn't written or applied. */
    fun captureCurrentAsProfile(context: Context, name: String, existingId: String? = null): NamedProfile {
        val leftCal  = getLeftCalibration(context)
        val rightCal = getRightCalibration(context)
        val mappingMap = SteamButton.values().associate { it.name to getMapping(context, it).ordinal }
        val boundPkgs = existingId?.let { id -> listNamedProfiles(context).firstOrNull { it.id == id }?.boundPackages }
            ?: emptyList()
        return NamedProfile(
            id = existingId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            profileId = getProfile(context).id,
            transport = 0,  // unused — see captureCurrentAsProfile kdoc
            leftCenterX = leftCal.centerX,
            leftCenterY = leftCal.centerY,
            leftDeadzone = leftCal.deadzonePercent,
            leftInvertY = leftCal.invertY,
            rightCenterX = rightCal.centerX,
            rightCenterY = rightCal.centerY,
            rightDeadzone = rightCal.deadzonePercent,
            rightInvertY = rightCal.invertY,
            mouseSensitivity = getMouseSensitivity(context),
            trackpadAsMouse = getTrackpadAsMouseInGamepad(context),
            rumbleIntensity = getRumbleIntensity(context),
            mapping = mappingMap,
            boundPackages = boundPkgs,
        )
    }

    /** Apply a stored profile to the live preferences. Caller should restart the service.
     *  Transport (USB/BT) is intentionally NOT touched — the user picks the link in
     *  the main UI, profiles only configure the controller behaviour. */
    fun applyNamedProfile(context: Context, profile: NamedProfile) {
        setProfile(context, GamepadProfile.fromId(profile.profileId))
        setLeftCalibration(context, StickCalibration(
            centerX = profile.leftCenterX,
            centerY = profile.leftCenterY,
            deadzonePercent = profile.leftDeadzone,
            invertY = profile.leftInvertY,
        ))
        setRightCalibration(context, StickCalibration(
            centerX = profile.rightCenterX,
            centerY = profile.rightCenterY,
            deadzonePercent = profile.rightDeadzone,
            invertY = profile.rightInvertY,
        ))
        setMouseSensitivity(context, profile.mouseSensitivity)
        setTrackpadAsMouseInGamepad(context, profile.trackpadAsMouse)
        setRumbleIntensity(context, profile.rumbleIntensity)
        // Mapping — ordinals stored against XboxTarget. Out-of-range values fall back to NONE.
        val targets = XboxTarget.values()
        SteamButton.values().forEach { btn ->
            val ord = profile.mapping[btn.name] ?: return@forEach
            val target = targets.getOrNull(ord) ?: XboxTarget.NONE
            setMapping(context, btn, target)
        }
        setActiveNamedProfileId(context, profile.id)
    }

    // ─── Update checker ──────────────────────────────────────────────────────
    fun getLastUpdateCheckAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheckAt(context: Context, timestampMillis: Long) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, timestampMillis).apply()
    }

    /** Version the user chose to skip via "Skip this version" — suppresses the auto-check dialog only. */
    fun getSkippedUpdateVersion(context: Context): String? =
        prefs(context).getString(KEY_SKIPPED_UPDATE_VERSION, null)

    fun setSkippedUpdateVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED_UPDATE_VERSION, version).apply()
    }

    // ─── show_ime_with_hard_keyboard override ──────────────────────────────────
    // Stores the pre-override value of the Settings.Secure key so it can be restored
    // when the controller disconnects. Written once per override (not overwritten
    // while an override is already pending), so a crash-without-restore doesn't
    // clobber the true original on the next connect/disconnect cycle.
    fun getSavedShowImeHardKeyboard(context: Context): String? =
        prefs(context).getString(KEY_SAVED_SHOW_IME_HARD_KB, null)

    fun setSavedShowImeHardKeyboard(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SAVED_SHOW_IME_HARD_KB, value).apply()
    }

    fun clearSavedShowImeHardKeyboard(context: Context) {
        prefs(context).edit().remove(KEY_SAVED_SHOW_IME_HARD_KB).apply()
    }
}
