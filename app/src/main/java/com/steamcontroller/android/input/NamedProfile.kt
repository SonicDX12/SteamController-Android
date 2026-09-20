package com.steamcontroller.android.input

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named profile snapshot — everything the user can tune, captured under a single name.
 *
 * Profiles live in SharedPreferences as a JSON array. Loading a profile overwrites the
 * matching live preference keys (profileId, transport, sticks, mappings, etc.) so the
 * very next service start uses the profile's settings.
 *
 * `boundPackages` powers the foreground-app auto-switch in Phase 2b: if the currently
 * focused Android app matches any package in this profile's list, the service swaps in.
 */
data class NamedProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val profileId: Int,
    val transport: Int,
    val leftCenterX: Int,
    val leftCenterY: Int,
    val leftDeadzone: Int,
    val leftInvertY: Boolean,
    val rightCenterX: Int,
    val rightCenterY: Int,
    val rightDeadzone: Int,
    val rightInvertY: Boolean,
    val mouseSensitivity: Float,
    val trackpadAsMouse: Boolean,
    val rumbleIntensity: Int,
    /** Serialised as a JSON object mapping `SteamButton.name` → `XboxTarget.ordinal`. */
    val mapping: Map<String, Int>,
    val boundPackages: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_PROFILE_ID, profileId)
        put(KEY_TRANSPORT, transport)
        put(KEY_LCX, leftCenterX); put(KEY_LCY, leftCenterY)
        put(KEY_LDZ, leftDeadzone); put(KEY_LIY, leftInvertY)
        put(KEY_RCX, rightCenterX); put(KEY_RCY, rightCenterY)
        put(KEY_RDZ, rightDeadzone); put(KEY_RIY, rightInvertY)
        put(KEY_MS, mouseSensitivity.toDouble())
        put(KEY_TAM, trackpadAsMouse)
        put(KEY_RI, rumbleIntensity)
        put(KEY_MAPPING, JSONObject(mapping as Map<*, *>))
        put(KEY_BOUND, JSONArray(boundPackages))
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_PROFILE_ID = "profileId"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_LCX = "lcx"; private const val KEY_LCY = "lcy"
        private const val KEY_LDZ = "ldz"; private const val KEY_LIY = "liy"
        private const val KEY_RCX = "rcx"; private const val KEY_RCY = "rcy"
        private const val KEY_RDZ = "rdz"; private const val KEY_RIY = "riy"
        private const val KEY_MS = "mouseSens"
        private const val KEY_TAM = "trackpadAsMouse"
        private const val KEY_RI = "rumbleIntensity"
        private const val KEY_MAPPING = "mapping"
        private const val KEY_BOUND = "boundPackages"

        fun fromJson(obj: JSONObject): NamedProfile {
            val mapObj = obj.optJSONObject(KEY_MAPPING) ?: JSONObject()
            val mapping = mutableMapOf<String, Int>()
            mapObj.keys().forEach { k -> mapping[k] = mapObj.optInt(k, 0) }

            val boundArr = obj.optJSONArray(KEY_BOUND)
            val bound = if (boundArr != null) (0 until boundArr.length()).map { boundArr.getString(it) } else emptyList()

            return NamedProfile(
                id = obj.optString(KEY_ID, UUID.randomUUID().toString()),
                name = obj.optString(KEY_NAME, "Profile"),
                profileId = obj.optInt(KEY_PROFILE_ID, 0),
                transport = obj.optInt(KEY_TRANSPORT, 0),
                leftCenterX = obj.optInt(KEY_LCX, 0),
                leftCenterY = obj.optInt(KEY_LCY, 0),
                leftDeadzone = obj.optInt(KEY_LDZ, 8),
                leftInvertY = obj.optBoolean(KEY_LIY, false),
                rightCenterX = obj.optInt(KEY_RCX, 0),
                rightCenterY = obj.optInt(KEY_RCY, 0),
                rightDeadzone = obj.optInt(KEY_RDZ, 8),
                rightInvertY = obj.optBoolean(KEY_RIY, false),
                mouseSensitivity = obj.optDouble(KEY_MS, 1.0).toFloat(),
                trackpadAsMouse = obj.optBoolean(KEY_TAM, true),
                rumbleIntensity = obj.optInt(KEY_RI, 100),
                mapping = mapping,
                boundPackages = bound,
            )
        }

        fun listToJson(profiles: List<NamedProfile>): String =
            JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<NamedProfile> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
