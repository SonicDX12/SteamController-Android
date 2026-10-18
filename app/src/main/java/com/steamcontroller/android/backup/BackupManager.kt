package com.steamcontroller.android.backup

import android.content.Context
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.uinput.GamepadProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports every user-tunable setting (live prefs + Game Profiles) as a single JSON
 * file, so nothing is lost across an app uninstall/reinstall (this app isn't on Play Store,
 * so there's no automatic Android Backup Service coverage to rely on).
 *
 * Import is additive for Game Profiles (upsert by id via Prefs.saveNamedProfile) rather than
 * a destructive replace — restoring onto a fresh install behaves like a full restore, while
 * restoring onto an existing install just merges in whatever the backup had.
 */
object BackupManager {

    private const val SCHEMA_VERSION = 1

    fun export(context: Context): String {
        val leftCal = Prefs.getLeftCalibration(context)
        val rightCal = Prefs.getRightCalibration(context)

        val live = JSONObject().apply {
            put("gamepadProfileId", Prefs.getProfile(context).id)
            put("lastGamepadProfileId", Prefs.getLastGamepadProfile(context).id)
            put("leftCal", calToJson(leftCal))
            put("rightCal", calToJson(rightCal))
            put("rumbleIntensity", Prefs.getRumbleIntensity(context))
            put("mouseSensitivity", Prefs.getMouseSensitivity(context).toDouble())
            put("trackpadAsMouse", Prefs.getTrackpadAsMouseInGamepad(context))
            put("mapping", JSONObject().apply {
                Prefs.getAllMappings(context).forEach { (btn, target) -> put(btn.name, target.ordinal) }
            })
        }

        val profiles = JSONArray().apply {
            Prefs.listNamedProfiles(context).forEach { put(it.toJson()) }
        }

        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("liveSettings", live)
            put("namedProfiles", profiles)
            put("activeNamedProfileId", Prefs.getActiveNamedProfileId(context))
        }.toString(2)
    }

    /** Returns the number of Game Profiles restored, or throws on malformed JSON. */
    fun import(context: Context, json: String): Int {
        val root = JSONObject(json)

        root.optJSONObject("liveSettings")?.let { live ->
            live.optInt("gamepadProfileId", -1).takeIf { it >= 0 }?.let {
                Prefs.setProfile(context, GamepadProfile.fromId(it))
            }
            live.optJSONObject("leftCal")?.let { Prefs.setLeftCalibration(context, calFromJson(it)) }
            live.optJSONObject("rightCal")?.let { Prefs.setRightCalibration(context, calFromJson(it)) }
            if (live.has("rumbleIntensity")) Prefs.setRumbleIntensity(context, live.optInt("rumbleIntensity", 100))
            if (live.has("mouseSensitivity")) Prefs.setMouseSensitivity(context, live.optDouble("mouseSensitivity", 1.0).toFloat())
            if (live.has("trackpadAsMouse")) Prefs.setTrackpadAsMouseInGamepad(context, live.optBoolean("trackpadAsMouse", true))
            live.optJSONObject("mapping")?.let { mapObj ->
                val targets = XboxTarget.values()
                SteamButton.values().forEach { btn ->
                    if (mapObj.has(btn.name)) {
                        val target = targets.getOrNull(mapObj.optInt(btn.name, -1)) ?: XboxTarget.NONE
                        Prefs.setMapping(context, btn, target)
                    }
                }
            }
        }

        val profilesArr = root.optJSONArray("namedProfiles")
        var restoredCount = 0
        if (profilesArr != null) {
            for (i in 0 until profilesArr.length()) {
                Prefs.saveNamedProfile(context, NamedProfile.fromJson(profilesArr.getJSONObject(i)))
                restoredCount++
            }
        }

        if (!root.isNull("activeNamedProfileId") && root.has("activeNamedProfileId")) {
            Prefs.setActiveNamedProfileId(context, root.optString("activeNamedProfileId", null))
        }

        return restoredCount
    }

    private fun calToJson(c: StickCalibration): JSONObject = JSONObject().apply {
        put("cx", c.centerX)
        put("cy", c.centerY)
        put("dz", c.deadzonePercent)
        put("invY", c.invertY)
    }

    private fun calFromJson(o: JSONObject): StickCalibration = StickCalibration(
        centerX = o.optInt("cx", 0),
        centerY = o.optInt("cy", 0),
        deadzonePercent = o.optInt("dz", 8),
        invertY = o.optBoolean("invY", false),
    )
}
