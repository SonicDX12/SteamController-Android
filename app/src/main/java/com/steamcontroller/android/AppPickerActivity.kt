package com.steamcontroller.android

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.steamcontroller.android.databinding.ActivityAppPickerBinding
import com.steamcontroller.android.service.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user tick installed launcher apps that should auto-switch to a given
 * NamedProfile. The selection is saved back onto the profile's `boundPackages` list.
 */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PRESELECTED = "preselected_packages"
    }

    private lateinit var binding: ActivityAppPickerBinding
    private var profileId: String = ""
    private val checkedPackages = mutableSetOf<String>()

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        intent.getStringArrayListExtra(EXTRA_PRESELECTED)?.let { checkedPackages.addAll(it) }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                persistAndFinish()
                true
            } else false
        }

        loadAppsAsync()
        maybePromptUsageAccess()
    }

    /**
     * Auto-switch only works once the user grants PACKAGE_USAGE_STATS access in Settings.
     * If we're about to bind apps without that, surface a one-tap path to the system screen.
     */
    private fun maybePromptUsageAccess() {
        if (UsageStatsHelper.hasPermission(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable auto-switch?")
            .setMessage(
                "To swap profiles automatically when you launch a bound app, allow Steam Controller " +
                "to read \"Usage data access\" in Android Settings. Without it, your bindings are saved " +
                "but no automatic switching will happen."
            )
            .setPositiveButton("Open Settings") { d, _ -> UsageStatsHelper.openSettingsScreen(this); d.dismiss() }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun loadAppsAsync() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { queryLauncherApps() }
            renderRows(entries)
        }
    }

    /**
     * Returns every app the launcher would normally show. Filters by intent CATEGORY_LAUNCHER
     * (or LEANBACK_LAUNCHER on Android TV) so the list is the same one the user sees on their
     * home screen — no system services or background-only packages.
     */
    private fun queryLauncherApps(): List<AppEntry> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

        val combined = pm.queryIntentActivities(mainIntent, 0) + pm.queryIntentActivities(leanbackIntent, 0)
        val seen = HashSet<String>()
        return combined.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (!seen.add(pkg)) return@mapNotNull null
            try {
                AppEntry(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm),
                )
            } catch (_: Throwable) { null }
        }.sortedBy { it.label.lowercase() }
    }

    private fun renderRows(entries: List<AppEntry>) {
        binding.appsContainer.removeAllViews()
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.item_app_row, binding.appsContainer, false)
            row.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(entry.icon)
            row.findViewById<TextView>(R.id.tvAppName).text = entry.label

            val sw = row.findViewById<MaterialSwitch>(R.id.switchAppBound)
            sw.isChecked = entry.packageName in checkedPackages
            sw.setOnCheckedChangeListener { _, checked ->
                if (checked) checkedPackages.add(entry.packageName)
                else checkedPackages.remove(entry.packageName)
            }
            row.setOnClickListener { sw.toggle() }

            binding.appsContainer.addView(row)
        }
    }

    private fun persistAndFinish() {
        if (profileId.isEmpty()) { finish(); return }
        val existing = Prefs.listNamedProfiles(this).firstOrNull { it.id == profileId }
        if (existing == null) {
            Toast.makeText(this, "Profile no longer exists", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Prefs.saveNamedProfile(this, existing.copy(boundPackages = checkedPackages.toList()))
        Toast.makeText(this, "Bindings saved (${checkedPackages.size})", Toast.LENGTH_SHORT).show()
        finish()
    }
}
