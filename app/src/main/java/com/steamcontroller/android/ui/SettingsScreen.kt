package com.steamcontroller.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.steamcontroller.android.Prefs
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.R
import com.steamcontroller.android.SubScreen
import com.steamcontroller.android.ThemeMode
import com.steamcontroller.android.backup.BackupManager
import com.steamcontroller.android.update.UpdateChecker
import com.steamcontroller.android.ui.component.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide preferences, as opposed to controller tuning (Calibration) or input
 * remapping (Mapping). What lands here is set once and then left alone.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onOpen: (SubScreen) -> Unit,
    onGithub: () -> Unit,
    onCheckUpdates: () -> Unit,
    /** A restored backup rewrote Prefs: the caller re-reads whatever it caches. */
    onBackupRestored: () -> Unit,
) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // A stored DYNAMIC choice can outlive the device that could honour it (restore onto an
    // older phone), so show the palette actually in force.
    val effective = if (themeMode == ThemeMode.DYNAMIC && !dynamicAvailable) ThemeMode.STEAM else themeMode

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title))

        SectionTitle("Controller", painterResource(R.drawable.ic_gamepad))
        SettingsItem(
            stringResource(R.string.main_action_calibration_title), onClick = { onOpen(SubScreen.CALIBRATION) },
            subtitle = stringResource(R.string.main_action_calibration_desc), icon = painterResource(R.drawable.ic_calibration),
        )
        SettingsItem(
            stringResource(R.string.main_action_mapping_title), onClick = { onOpen(SubScreen.MAPPING) },
            subtitle = stringResource(R.string.main_action_mapping_desc), icon = painterResource(R.drawable.ic_mapping),
        )
        SettingsItem(
            stringResource(R.string.btn_debug), onClick = { onOpen(SubScreen.DEBUG) },
            subtitle = stringResource(R.string.main_action_debug_desc), icon = painterResource(R.drawable.ic_debug),
        )

        SectionTitle("Connection", painterResource(R.drawable.ic_usb))
        val context = LocalContext.current
        var autoLaunch by remember { mutableStateOf(Prefs.getAutoLaunch(context)) }
        SettingsSwitchItem(
            stringResource(R.string.settings_auto_launch), autoLaunch,
            onCheckedChange = { checked ->
                autoLaunch = checked
                Prefs.setAutoLaunch(context, checked)
                // Android 12+ only lets an app start a foreground service from the background
                // if it is exempt from battery optimisation — that is what the BT receiver needs.
                val power = context.getSystemService(PowerManager::class.java)
                if (checked && !power.isIgnoringBatteryOptimizations(context.packageName)) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")))
                }
            },
            subtitle = stringResource(R.string.settings_auto_launch_desc),
        )

        SectionTitle(stringResource(R.string.settings_theme), painterResource(R.drawable.ic_settings))
        DividedCard(listOf(
            { ChoiceRow(stringResource(R.string.settings_theme_steam), effective == ThemeMode.STEAM,
                { onThemeMode(ThemeMode.STEAM) }, subtitle = "The blue of the controller itself") },
            { ChoiceRow(stringResource(R.string.settings_theme_dynamic), effective == ThemeMode.DYNAMIC,
                { onThemeMode(ThemeMode.DYNAMIC) }, enabled = dynamicAvailable,
                subtitle = if (dynamicAvailable) "Follows your wallpaper" else stringResource(R.string.theme_dynamic_unavailable)) },
        ))

        // Not on the Play Store, so no Android Backup Service: this file is the only way
        // settings and game profiles survive an uninstall.
        SectionTitle("Backup", painterResource(R.drawable.ic_game_profiles))
        val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(context).toByteArray()) }
                Toast.makeText(context, R.string.backup_export_toast, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(context, context.getString(R.string.backup_export_failed_toast, t.message), Toast.LENGTH_LONG).show()
            }
        }
        val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: throw IllegalStateException("empty file")
                val count = BackupManager.import(context, json)
                onBackupRestored()
                Toast.makeText(context, context.getString(R.string.backup_import_toast, count), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Toast.makeText(context, context.getString(R.string.backup_import_failed_toast, t.message), Toast.LENGTH_LONG).show()
            }
        }
        SettingsItem(
            stringResource(R.string.menu_export_backup), subtitle = "Settings, mappings and game profiles to a JSON file",
            onClick = {
                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportBackup.launch("steamcontroller_backup_$stamp.json")
            },
        )
        SettingsItem(
            stringResource(R.string.menu_import_backup), subtitle = "Profiles in the file are added or updated, none are removed",
            onClick = { importBackup.launch(arrayOf("application/json")) },
        )

        SectionTitle("About", painterResource(R.drawable.ic_help))
        SettingsItem(
            stringResource(R.string.main_check_update_desc), onClick = onCheckUpdates,
            subtitle = "Version ${BuildConfig.VERSION_NAME} · checked automatically once a day",
            icon = painterResource(R.drawable.ic_update),
        )
        SettingsItem(
            "Source code", onClick = onGithub,
            subtitle = "SonicDX12/SteamController-Android on GitHub", icon = painterResource(R.drawable.ic_github),
        )

        Spacer(Modifier.height(if (isWideScreen()) 16.dp else BottomBarClearance))
    }
}

/** "Update available" prompt. The release notes come from GitHub and can be long, so they scroll. */
@Composable
fun UpdateDialog(release: UpdateChecker.ReleaseInfo, onUpdate: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("${stringResource(R.string.update_dialog_title)} · ${release.tagName}") },
        text = {
            Text(
                release.notes.ifBlank { release.tagName },
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_dialog_button_update)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.update_dialog_button_skip)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.update_dialog_button_later)) }
            }
        },
    )
}
