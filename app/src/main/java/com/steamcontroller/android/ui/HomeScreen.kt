package com.steamcontroller.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.component.*
import com.steamcontroller.android.uinput.GamepadProfile

@Composable
fun HomeScreen(
    serviceRunning: Boolean,
    shizukuReady: Boolean,
    transport: Transport,
    onTransport: (Transport) -> Unit,
    profile: GamepadProfile,
    onProfile: (GamepadProfile) -> Unit,
    /** Paired controllers as (address, label). */
    btDevices: List<Pair<String, String>>,
    btAddress: String?,
    onBtDevice: (String) -> Unit,
    onRefreshBt: () -> Unit,
    onToggleService: () -> Unit,
    onGithub: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) ConnectionHelpDialog { showHelp = false }

    val header: @Composable () -> Unit = {
        ScreenHeader(stringResource(R.string.main_title), subtitle = stringResource(R.string.main_subtitle)) {
            HeaderIconButton(onClick = onGithub) {
                Icon(painterResource(R.drawable.ic_github), stringResource(R.string.main_open_github_desc))
            }
            HeaderIconButton(onClick = { showHelp = true }) {
                Icon(painterResource(R.drawable.ic_help), stringResource(R.string.main_help_desc))
            }
        }
    }
    val status: @Composable () -> Unit = { StatusCard(shizukuReady) }
    val start: @Composable () -> Unit = {
        ActionButton(
            text = stringResource(if (serviceRunning) R.string.btn_stop else R.string.btn_start),
            onClick = onToggleService,
            primary = !serviceRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    val connection: @Composable () -> Unit = {
        ConnectionSection(transport, onTransport, btDevices, btAddress, onBtDevice, onRefreshBt)
    }
    val mode: @Composable () -> Unit = { ControlModeSection(profile, onProfile) }

    if (isWideScreen()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            header()
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status(); start()
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    connection(); mode()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    } else {
        Column(
            Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header(); status(); start(); connection(); mode()
            Spacer(Modifier.height(BottomBarClearance))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(shizukuReady: Boolean) {
    // stateFlow only carries a value once a HID frame has actually been parsed, which is a
    // far more honest "connected" than "the user pressed Start".
    val connected = ControllerService.stateFlow.collectAsState().value != null
    val battery by ControllerService.batteryFlow.collectAsState()
    val mode by ControllerService.modeFlow.collectAsState()
    // Re-read on every mode change: the profile can move under us from the notification.
    val context = LocalContext.current
    val modeLabel = when (mode) {
        ControllerService.InjectionMode.UINPUT -> "${Prefs.getProfile(context).displayName} (uinput)"
        ControllerService.InjectionMode.SHIZUKU_INJECT -> "Shizuku inject (limited)"
        ControllerService.InjectionMode.NONE -> null
    }

    GradientCard {
        Image(
            painterResource(R.drawable.sc2026_controller_black),
            contentDescription = stringResource(R.string.main_sc2026_illustration_desc),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (connected) "Controller ready" else "Controller disconnected",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shizukuReady) {
                Pill(
                    "Shizuku ready",
                    containerColor = colorResource(R.color.status_connected_container),
                    contentColor = colorResource(R.color.status_connected_on_container),
                )
            } else {
                Pill(
                    "Shizuku not ready",
                    containerColor = colorResource(R.color.status_idle_container),
                    contentColor = colorResource(R.color.status_idle_on_container),
                )
            }
            battery?.let { Pill("Battery: ${it.describe()}") }
            modeLabel?.let { Pill(it) }
        }
    }
}

@Composable
private fun ConnectionSection(
    transport: Transport,
    onTransport: (Transport) -> Unit,
    btDevices: List<Pair<String, String>>,
    btAddress: String?,
    onBtDevice: (String) -> Unit,
    onRefreshBt: () -> Unit,
) {
    SectionTitle("Connection", painterResource(R.drawable.ic_usb))
    DividedCard(listOf(
        { ChoiceRow(stringResource(R.string.main_transport_usb), transport == Transport.USB, { onTransport(Transport.USB) },
            subtitle = "Wired cable or wireless Puck") },
        { ChoiceRow(stringResource(R.string.main_transport_bt), transport == Transport.BLUETOOTH, { onTransport(Transport.BLUETOOTH) },
            subtitle = "Pair the controller in Android settings first") },
    ))
    if (transport != Transport.BLUETOOTH) return

    SectionTitle(stringResource(R.string.main_bt_device_hint), painterResource(R.drawable.ic_bluetooth)) {
        IconButton(onClick = onRefreshBt) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.main_refresh_bt_desc), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (btDevices.isEmpty()) {
        SectionCard { SettingsRow("No paired Steam Controller found", subtitle = "Pair it in Android Bluetooth settings, then refresh.") }
    } else {
        DividedCard(btDevices.map { (address, label) ->
            { ChoiceRow(label, address == btAddress, { onBtDevice(address) }) }
        })
    }
}

@Composable
private fun ControlModeSection(profile: GamepadProfile, onProfile: (GamepadProfile) -> Unit) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.main_control_mode_label), painterResource(R.drawable.ic_gamepad))
    DividedCard(listOf(
        { ChoiceRow(stringResource(R.string.main_mode_gamepad), !profile.isMouseMode,
            // Gamepad restores whichever pad the user last picked (Xbox 360 on first run).
            { if (profile.isMouseMode) onProfile(Prefs.getLastGamepadProfile(context)) },
            subtitle = "Games see a real controller") },
        { ChoiceRow(stringResource(R.string.main_mode_desktop), profile.isMouseMode, { onProfile(GamepadProfile.MOUSE) },
            subtitle = "Mouse and keyboard, for Android TV and apps") },
    ))
    if (profile.isMouseMode) return

    SectionTitle(stringResource(R.string.main_emulated_controller_label))
    val pads = GamepadProfile.entries.filter { !it.isMouseMode }
    DividedCard(pads.map { pad ->
        { ChoiceRow(pad.displayName, pad == profile, { onProfile(pad) }) }
    })
}

@Composable
private fun ConnectionHelpDialog(onDismiss: () -> Unit) {
    @Composable fun Para(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
    @Composable fun Label(text: String) =
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    @Composable fun Bullet(html: String) = Row {
        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(AnnotatedString.fromHtml(html), style = MaterialTheme.typography.bodyMedium)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        title = { Text(stringResource(R.string.help_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Para(stringResource(R.string.help_para_puck_slots))
                Para(stringResource(R.string.help_para_bt_independent))
                Para(stringResource(R.string.help_para_power_off))
                Label(stringResource(R.string.help_section_wireless))
                Para(stringResource(R.string.help_para_wireless_intro))
                Bullet("<b>Puck (right slot)</b> — hold <b>A + R1 + Steam</b>, chime + white LED.")
                Bullet("<b>Puck (left slot)</b> — hold <b>A + L1 + Steam</b>, chime + white LED.")
                Bullet("<b>Bluetooth</b> — hold <b>B + R1 + Steam</b>, chime + blue LED.")
                Label(stringResource(R.string.help_section_wired))
                Bullet("Controller is <b>off</b> — plug it into the device. Chime + green LED.")
                Bullet("Controller is <b>on</b> in another mode — hold <b>Steam</b> while plugging it in. Chime + green LED.")
            }
        },
    )
}
