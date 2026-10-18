package com.steamcontroller.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.R
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.component.*
import kotlinx.coroutines.flow.filterNotNull

/** Live controller state — for reference only, not interactive. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val s by ControllerService.stateFlow.collectAsState()
    val raw by ControllerService.rawReportFlow.collectAsState()

    // Report rate, averaged over 30 frames so the number is readable.
    var hz by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L; var count = 0; var accum = 0L
        ControllerService.stateFlow.filterNotNull().collect {
            val now = System.currentTimeMillis()
            if (last != 0L) {
                accum += now - last
                if (++count >= 30) {
                    hz = (1000 / (accum / count).coerceAtLeast(1)).toInt()
                    count = 0; accum = 0
                }
            }
            last = now
        }
    }

    val state = s ?: SteamControllerState()

    @Composable
    fun Chips(vararg chips: Pair<String, Int>) =
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (label, mask) -> Chip(label, state.isButtonPressed(mask)) }
        }

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.debug_title), subtitle = stringResource(R.string.debug_readonly_notice), onBack = onBack) {
            Pill("$hz Hz")
        }

        SectionTitle("Buttons", painterResource(R.drawable.ic_gamepad))
        SectionCard {
            Chips("A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
                "▲" to Buttons.DPAD_UP, "▼" to Buttons.DPAD_DOWN, "◀" to Buttons.DPAD_LEFT, "▶" to Buttons.DPAD_RIGHT)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Chips("LB" to Buttons.LB, "RB" to Buttons.RB, "SEL" to Buttons.VIEW, "◆" to Buttons.STEAM,
                "START" to Buttons.MENU, "QA" to Buttons.QUICK_ACCESS, "L3" to Buttons.LS, "R3" to Buttons.RS)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Chips("LGrip" to Buttons.GRIP_LT, "RGrip" to Buttons.GRIP_RT,
                "L4" to Buttons.L4, "L5" to Buttons.L5, "R4" to Buttons.R4, "R5" to Buttons.R5)
        }

        SectionTitle("Triggers (0–32767)")
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar("LT", state.leftTrigger.toInt(), signed = false)
                AxisBar("RT", state.rightTrigger.toInt(), signed = false)
            }
        }

        SectionTitle("Sticks (±32767)")
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar(stringResource(R.string.debug_left_x), state.leftJoyX.toInt())
                AxisBar(stringResource(R.string.debug_left_y), state.leftJoyY.toInt())
                AxisBar(stringResource(R.string.debug_right_x), state.rightJoyX.toInt())
                AxisBar(stringResource(R.string.debug_right_y), state.rightJoyY.toInt())
            }
        }

        SectionTitle("Trackpads (±32767)")
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar(stringResource(R.string.debug_left_x), state.leftPadX.toInt())
                AxisBar(stringResource(R.string.debug_left_y), state.leftPadY.toInt())
                AxisBar(stringResource(R.string.debug_right_x), state.rightPadX.toInt())
                AxisBar(stringResource(R.string.debug_right_y), state.rightPadY.toInt())
            }
        }

        // The IMU block is only present once raw accel/gyro output is on; until then these stay at zero.
        SectionTitle("IMU")
        SectionCard {
            Row {
                InfoRow("Accel X", state.accelX.toString(), Modifier.weight(1f))
                InfoRow("Accel Y", state.accelY.toString(), Modifier.weight(1f))
                InfoRow("Accel Z", state.accelZ.toString(), Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row {
                InfoRow("Gyro X", state.gyroX.toString(), Modifier.weight(1f))
                InfoRow("Gyro Y", state.gyroY.toString(), Modifier.weight(1f))
                InfoRow("Gyro Z", state.gyroZ.toString(), Modifier.weight(1f))
            }
        }

        SectionTitle("Raw report (hex)")
        SectionCard {
            Text(
                raw?.let(::formatHex) ?: "—",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Chip(label: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant,
    ) {
        Text(
            label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

/** Label, value and a bar; [signed] axes are centred so zero sits in the middle. */
@Composable
private fun AxisBar(label: String, value: Int, signed: Boolean = true) {
    val fraction = if (signed) (value + 32768) / 65535f else value / 32767f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            drawStopIndicator = {},
        )
    }
}

private fun formatHex(buf: ByteArray): String = buildString {
    buf.forEachIndexed { i, b ->
        append("%02X ".format(b.toInt() and 0xFF))
        if (i % 16 == 15) append("\n")
    }
}.trimEnd()
