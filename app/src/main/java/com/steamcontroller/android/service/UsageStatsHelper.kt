package com.steamcontroller.android.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Thin wrapper around the platform `UsageStatsManager` for the foreground-app
 * auto-switch feature. Requires `PACKAGE_USAGE_STATS` which is a special-access
 * permission the user grants from Settings.
 */
object UsageStatsHelper {

    /** True if the user has already granted "Usage data access" for this app. */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } catch (_: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Open the system Settings screen where the user can grant access. */
    fun openSettingsScreen(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            // Fallback to app details if the device hides the dedicated screen.
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Look at the last few seconds of activity events and return whichever package
     * is currently focused, or null if the OS didn't surface one yet (rare during
     * boot or right after a screen-off).
     */
    fun getCurrentForegroundApp(context: Context): String? {
        if (!hasPermission(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5_000, now)
        var topPackage: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                topPackage = ev.packageName
            }
        }
        return topPackage
    }
}
