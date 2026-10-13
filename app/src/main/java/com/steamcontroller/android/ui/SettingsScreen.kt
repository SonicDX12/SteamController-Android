package com.steamcontroller.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import com.steamcontroller.android.Prefs
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.R
import com.steamcontroller.android.SubScreen
import com.steamcontroller.android.ThemeMode
import com.steamcontroller.android.ui.component.*

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

        SectionTitle("About", painterResource(R.drawable.ic_help))
        SettingsItem(
            "Source code", onClick = onGithub,
            subtitle = "GitHub · version ${LocalContext.current.run { packageManager.getPackageInfo(packageName, 0).versionName }}", icon = painterResource(R.drawable.ic_github),
        )

        Spacer(Modifier.height(if (isWideScreen()) 16.dp else BottomBarClearance))
    }
}
