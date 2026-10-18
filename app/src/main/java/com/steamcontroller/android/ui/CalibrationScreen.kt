package com.steamcontroller.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.component.*

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var leftCal by remember { mutableStateOf(Prefs.getLeftCalibration(context)) }
    var rightCal by remember { mutableStateOf(Prefs.getRightCalibration(context)) }
    var rumble by remember { mutableFloatStateOf(Prefs.getRumbleIntensity(context).toFloat()) }
    var sensitivity by remember { mutableFloatStateOf(Prefs.getMouseSensitivity(context)) }
    var trackpadMouse by remember { mutableStateOf(Prefs.getTrackpadAsMouseInGamepad(context)) }
    val state by ControllerService.stateFlow.collectAsState()

    fun setLeft(c: StickCalibration) { leftCal = c; Prefs.setLeftCalibration(context, c) }
    fun setRight(c: StickCalibration) { rightCal = c; Prefs.setRightCalibration(context, c) }

    val lx = state?.leftJoyX?.toInt() ?: 0
    val ly = state?.leftJoyY?.toInt() ?: 0
    val rx = state?.rightJoyX?.toInt() ?: 0
    val ry = state?.rightJoyY?.toInt() ?: 0

    @Composable
    fun StickColumn(label: String, cal: StickCalibration, rawX: Int, rawY: Int, onChange: (StickCalibration) -> Unit, modifier: Modifier) {
        val (x, y) = cal.apply(rawX, rawY)
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            StickPad(x / 32767f, y / 32767f, cal.deadzonePercent / 100f, Modifier.size(120.dp))
            Text(
                "raw: %5d, %5d".format(rawX, rawY),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("${stringResource(R.string.calib_deadzone_label)}: ${cal.deadzonePercent}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = cal.deadzonePercent.toFloat(), valueRange = 0f..40f,
                onValueChange = { onChange(cal.copy(deadzonePercent = it.toInt())) },
            )
            Row(
                Modifier.clickable { onChange(cal.copy(invertY = !cal.invertY)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.calib_invert_y), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                ToggleSwitch(cal.invertY)
            }
        }
    }

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.calib_title), onBack = onBack) {
            HeaderIconButton(onClick = {
                setLeft(StickCalibration()); setRight(StickCalibration())
            }) { Icon(Icons.Filled.Refresh, stringResource(R.string.menu_reset_defaults)) }
        }

        SectionTitle("Sticks", painterResource(R.drawable.ic_gamepad))
        SectionCard {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StickColumn("Left", leftCal, lx, ly, ::setLeft, Modifier.weight(1f))
                StickColumn("Right", rightCal, rx, ry, ::setRight, Modifier.weight(1f))
            }
        }
        ActionButton(
            stringResource(R.string.calib_calibrate_all), primary = true, modifier = Modifier.fillMaxWidth(),
            onClick = {
                setLeft(leftCal.copy(centerX = lx, centerY = ly))
                setRight(rightCal.copy(centerX = rx, centerY = ry))
                Toast.makeText(context, "Sticks calibrated", Toast.LENGTH_SHORT).show()
            },
        )
        Text(
            stringResource(R.string.calib_calibrate_hint),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Rumble", painterResource(R.drawable.ic_calibration))
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("${stringResource(R.string.calib_test_intensity)}: ${rumble.toInt()}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = rumble, valueRange = 0f..100f,
                    onValueChange = { rumble = (it / 5).toInt() * 5f; Prefs.setRumbleIntensity(context, rumble.toInt()) },
                )
                ActionButton(
                    stringResource(R.string.calib_test_rumble), modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (ControllerService.modeFlow.value == ControllerService.InjectionMode.NONE) {
                            Toast.makeText(context, "Start the service first", Toast.LENGTH_SHORT).show()
                        } else {
                            context.startService(Intent(context, ControllerService::class.java).apply {
                                action = ControllerService.ACTION_TEST_RUMBLE
                            })
                        }
                    },
                )
            }
        }

        SectionTitle("Desktop mode", painterResource(R.drawable.ic_mouse))
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("${stringResource(R.string.calib_mouse_sensitivity)}: %.1f×".format(sensitivity), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = sensitivity, valueRange = 0.1f..3f,
                    onValueChange = { sensitivity = Math.round(it * 10) / 10f; Prefs.setMouseSensitivity(context, sensitivity) },
                )
            }
        }
        // UInputGamepad re-reads this pref at most every 250ms, so flipping it is live.
        SettingsSwitchItem(
            stringResource(R.string.calib_trackpad_switch_label), trackpadMouse,
            onCheckedChange = { trackpadMouse = it; Prefs.setTrackpadAsMouseInGamepad(context, it) },
            subtitle = stringResource(R.string.calib_trackpad_hint),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Stick range ring, crosshair, deadzone disc and the current calibrated position. */
@Composable
private fun StickPad(x: Float, y: Float, deadzone: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val radius = size.minDimension / 2f - 3.dp.toPx()
        val c = center
        drawCircle(colors.surfaceContainerHighest, radius, c)
        drawCircle(colors.outline, radius, c, style = Stroke(1.5.dp.toPx()))
        drawLine(colors.outlineVariant, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius))
        drawLine(colors.outlineVariant, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y))
        if (deadzone > 0f) drawCircle(colors.primary.copy(alpha = 0.18f), radius * deadzone, c)
        // The stick's travel is square, so a diagonal reaches ~1.16: clamp the length, not each axis.
        val k = 1f / maxOf(1f, kotlin.math.hypot(x, y))
        drawCircle(colors.primary, 6.dp.toPx(), Offset(c.x + x * k * radius, c.y + y * k * radius))
    }
}
