package com.steamcontroller.android

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.steamcontroller.android.databinding.ActivitySettingsBinding

/**
 * App-wide preferences.
 *
 * Distinct from CalibrationActivity, which tunes the controller, and from
 * MappingActivity, which remaps its inputs. What lands here is set once and then
 * left alone — which is why the theme picker moved off the main screen, where it
 * was competing for attention with the transport and profile controls.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupThemeToggle()
    }

    private fun setupThemeToggle() {
        val dynamicAvailable = DynamicColors.isDynamicColorAvailable()
        val current = Prefs.getThemeMode(this)

        binding.btnThemeDynamic.isEnabled = dynamicAvailable
        if (!dynamicAvailable) {
            binding.tvThemeHint.visibility = View.VISIBLE
            binding.tvThemeHint.text = getString(R.string.theme_dynamic_unavailable)
        }

        // A stored DYNAMIC choice can outlive the device that could honour it — for
        // instance after a restore onto an older phone — so fall back rather than
        // leaving the group with nothing checked.
        val effective = if (current == ThemeMode.DYNAMIC && !dynamicAvailable) ThemeMode.STEAM else current
        binding.toggleTheme.check(
            if (effective == ThemeMode.DYNAMIC) R.id.btnThemeDynamic else R.id.btnThemeSteam
        )

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val picked = if (checkedId == R.id.btnThemeDynamic) ThemeMode.DYNAMIC else ThemeMode.STEAM
            if (picked == Prefs.getThemeMode(this)) return@addOnButtonCheckedListener
            Prefs.setThemeMode(this, picked)
            // The overlay is applied per-activity at creation, so the whole stack has to
            // come back. MainActivity re-reads the preference in its own onCreate.
            recreate()
        }
    }
}
