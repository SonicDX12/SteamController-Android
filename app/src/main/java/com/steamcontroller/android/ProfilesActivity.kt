package com.steamcontroller.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.backup.BackupManager
import com.steamcontroller.android.databinding.ActivityProfilesBinding
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.uinput.GamepadProfile
import java.text.SimpleDateFormat
import java.util.Locale

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(this).toByteArray()) }
            Toast.makeText(this, R.string.backup_export_toast, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.backup_export_failed_toast, t.message), Toast.LENGTH_LONG).show()
        }
    }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: throw IllegalStateException("empty file")
            val count = BackupManager.import(this, json)
            renderList()
            Toast.makeText(this, getString(R.string.backup_import_toast, count), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.backup_import_failed_toast, t.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_backup -> {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
                    exportBackupLauncher.launch("steamcontroller_backup_$stamp.json")
                    true
                }
                R.id.action_import_backup -> {
                    importBackupLauncher.launch(arrayOf("application/json"))
                    true
                }
                else -> false
            }
        }
        binding.fabSaveCurrent.setOnClickListener { promptSaveCurrent() }

        renderList()
    }

    override fun onResume() {
        super.onResume()
        renderList()  // re-render in case AppPickerActivity changed bindings
    }

    private fun renderList() {
        val profiles = Prefs.listNamedProfiles(this)
        val activeId = Prefs.getActiveNamedProfileId(this)

        binding.profilesContainer.removeAllViews()
        binding.emptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        for (profile in profiles) addRow(profile, isActive = (profile.id == activeId))
    }

    private fun addRow(profile: NamedProfile, isActive: Boolean) {
        val row = layoutInflater.inflate(R.layout.item_profile_row, binding.profilesContainer, false)
        row.findViewById<TextView>(R.id.tvProfileName).text = profile.name

        val gp = GamepadProfile.fromId(profile.profileId)
        row.findViewById<TextView>(R.id.tvProfileDetails).text = gp.displayName

        val tvBound = row.findViewById<TextView>(R.id.tvProfileBound)
        if (profile.boundPackages.isNotEmpty()) {
            tvBound.visibility = View.VISIBLE
            tvBound.text = "🎯 Auto-switch on ${profile.boundPackages.size} app${if (profile.boundPackages.size > 1) "s" else ""}"
        } else {
            tvBound.visibility = View.GONE
        }

        val badge = row.findViewById<TextView>(R.id.tvActiveBadge)
        badge.visibility = if (isActive) View.VISIBLE else View.GONE

        val btnLoad = row.findViewById<MaterialButton>(R.id.btnLoadProfile)
        btnLoad.text = if (isActive) "Loaded" else "Load"
        btnLoad.isEnabled = !isActive
        btnLoad.setOnClickListener { applyProfile(profile) }

        row.findViewById<MaterialButton>(R.id.btnProfileMenu).setOnClickListener { anchor ->
            showRowMenu(anchor, profile)
        }

        binding.profilesContainer.addView(row)
    }

    private fun showRowMenu(anchor: View, profile: NamedProfile) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 2, 1, "Duplicate")
        popup.menu.add(0, 3, 2, "Bind to apps…")
        popup.menu.add(0, 4, 3, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptRename(profile)
                2 -> duplicateProfile(profile)
                3 -> launchAppBinding(profile)
                4 -> confirmDelete(profile)
            }
            true
        }
        popup.show()
    }

    private fun promptSaveCurrent() {
        val input = EditText(this).apply {
            hint = "Game profile name"
            setText(suggestNextName())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save current settings as game profile")
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val name = input.text.toString().trim().ifEmpty { suggestNextName() }
                val newProfile = Prefs.captureCurrentAsProfile(this, name)
                Prefs.saveNamedProfile(this, newProfile)
                Prefs.setActiveNamedProfileId(this, newProfile.id)
                renderList()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        showKeyboardFor(input)
    }

    /**
     * Explicitly force-shows the IME instead of relying on Android's auto-show-on-focus
     * heuristic. While the emulated gamepad is connected (esp. over Bluetooth), Android/OEM
     * skins can classify the physical controller as a hardware keyboard/mouse and suppress
     * auto-show entirely — SHOW_FORCED bypasses that heuristic instead of fighting it via
     * system Settings, which is fragile and OEM-dependent.
     */
    private fun showKeyboardFor(input: EditText) {
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
        }
    }

    private fun promptRename(profile: NamedProfile) {
        val input = EditText(this).apply { setText(profile.name); setSelection(text.length) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename profile")
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val newName = input.text.toString().trim().ifEmpty { profile.name }
                Prefs.saveNamedProfile(this, profile.copy(name = newName))
                renderList()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        showKeyboardFor(input)
    }

    private fun duplicateProfile(profile: NamedProfile) {
        val copy = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${profile.name} (copy)",
            boundPackages = emptyList(),  // duplicates start unbound
        )
        Prefs.saveNamedProfile(this, copy)
        renderList()
    }

    private fun confirmDelete(profile: NamedProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This profile will be removed permanently. Live settings stay as they are.")
            .setPositiveButton("Delete") { d, _ ->
                Prefs.deleteNamedProfile(this, profile.id)
                renderList()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyProfile(profile: NamedProfile) {
        Prefs.applyNamedProfile(this, profile)
        Toast.makeText(this, "Loaded \"${profile.name}\". Restart the service for full effect.", Toast.LENGTH_LONG).show()
        renderList()
    }

    private fun launchAppBinding(profile: NamedProfile) {
        val intent = Intent(this, AppPickerActivity::class.java)
            .putExtra(AppPickerActivity.EXTRA_PROFILE_ID, profile.id)
            .putStringArrayListExtra(AppPickerActivity.EXTRA_PRESELECTED, ArrayList(profile.boundPackages))
        startActivity(intent)
    }

    private fun suggestNextName(): String {
        val existing = Prefs.listNamedProfiles(this).map { it.name }.toSet()
        var n = 1
        while ("Profile $n" in existing) n++
        return "Profile $n"
    }
}
