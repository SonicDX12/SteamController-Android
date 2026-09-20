package com.steamcontroller.android

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * Applies the chosen colour scheme to every Activity, present and future.
 *
 * Registering here rather than in each Activity's onCreate keeps the two themes to a
 * single decision point: the base theme (Theme.SteamController) always carries the Steam
 * palette, and the dynamic overlay is layered on top only when the user asked for it.
 *
 * The precondition is re-evaluated per Activity creation, so switching theme is just a
 * pref write plus recreate() — no manifest theme juggling, no Activity restart loop.
 */
class SteamControllerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> Prefs.getThemeMode(this) == ThemeMode.DYNAMIC }
                .build(),
        )
    }
}
