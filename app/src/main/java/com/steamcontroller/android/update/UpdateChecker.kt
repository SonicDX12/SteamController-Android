package com.steamcontroller.android.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer signed APK than the one currently installed.
 * The app is distributed outside the Play Store, so this is the only update channel.
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/SonicDX12/SteamController-Android/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
    )

    /** Fetches the latest GitHub release. Returns null on any network/parsing failure. */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name")

            val assets = json.getJSONArray("assets")
            val apkAsset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@withContext null

            ReleaseInfo(
                tagName = tag,
                versionName = tag.removePrefix("v"),
                notes = json.optString("body", ""),
                apkUrl = apkAsset.getString("browser_download_url"),
                apkName = apkAsset.getString("name"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** True if [remoteVersion] is a higher semantic version than [currentVersion] ("1.2.0" vs "1.0"). */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val remote = parts(remoteVersion)
        val current = parts(currentVersion)
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
