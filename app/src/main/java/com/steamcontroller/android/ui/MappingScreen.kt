package com.steamcontroller.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.ButtonCategory
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.ui.component.*

/**
 * Each row shows the physical button, then what it is mapped to. Tapping a row opens a
 * single-choice remap dialog. Changes apply live, no service restart needed.
 */
@Composable
fun MappingScreen(onBack: () -> Unit, onOpenProfiles: () -> Unit) {
    val context = LocalContext.current
    val mappings = remember {
        mutableStateMapOf<SteamButton, XboxTarget>().apply { SteamButton.entries.forEach { put(it, Prefs.getMapping(context, it)) } }
    }
    var editing by remember { mutableStateOf<SteamButton?>(null) }

    editing?.let { source ->
        RemapDialog(source, mappings.getValue(source), onDismiss = { editing = null }) { picked ->
            Prefs.setMapping(context, source, picked)
            mappings[source] = picked
            editing = null
        }
    }

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.mapping_title), onBack = onBack) {
            HeaderIconButton(onClick = onOpenProfiles) {
                Icon(painterResource(R.drawable.ic_game_profiles), stringResource(R.string.profiles_title))
            }
            HeaderIconButton(onClick = {
                Prefs.resetMappings(context)
                SteamButton.entries.forEach { mappings[it] = Prefs.getMapping(context, it) }
            }) { Icon(Icons.Filled.Refresh, stringResource(R.string.menu_reset_defaults)) }
        }
        Text(
            stringResource(R.string.mapping_hint), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val grouped = SteamButton.entries.groupBy { it.category }
        for (category in ButtonCategory.entries) {
            val buttons = grouped[category] ?: continue
            SectionTitle(category.title)
            DividedCard(buttons.map { source ->
                { MappingRow(source, mappings.getValue(source)) { editing = source } }
            })
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MappingRow(source: SteamButton, target: XboxTarget, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonGlyph(sourceIconFor(source), source.shortLabel)
        Text(source.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        ButtonGlyph(targetIconFor(target), shortLabelFor(target))
        Text(
            target.displayName, Modifier.widthIn(max = 120.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (target == XboxTarget.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Kenney input-prompt icon when one exists, otherwise a letter in a tonal circle. */
@Composable
private fun ButtonGlyph(iconRes: Int?, label: String) {
    if (iconRes != null) {
        Image(painterResource(iconRes), null, Modifier.size(32.dp))
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RemapDialog(source: SteamButton, current: XboxTarget, onDismiss: () -> Unit, onPick: (XboxTarget) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remap ${source.displayName}") },
        text = {
            LazyColumn {
                items(XboxTarget.entries) { target ->
                    ChoiceRow(
                        target.displayName, target == current, { onPick(target) },
                        leading = if (target == current) null else { { ButtonGlyph(targetIconFor(target), shortLabelFor(target)) } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Kenney SC icon for each source button. */
private fun sourceIconFor(b: SteamButton): Int = when (b) {
    SteamButton.A -> R.drawable.sc_btn_a
    SteamButton.B -> R.drawable.sc_btn_b
    SteamButton.X -> R.drawable.sc_btn_x
    SteamButton.Y -> R.drawable.sc_btn_y
    SteamButton.LB -> R.drawable.sc_btn_lb
    SteamButton.RB -> R.drawable.sc_btn_rb
    SteamButton.LT -> R.drawable.sc_btn_lt
    SteamButton.RT -> R.drawable.sc_btn_rt
    SteamButton.MENU -> R.drawable.sc_btn_menu
    SteamButton.VIEW -> R.drawable.sc_btn_view
    SteamButton.STEAM -> R.drawable.sc_btn_steam
    SteamButton.QUICK_ACCESS -> R.drawable.sc_btn_qa
    SteamButton.L4 -> R.drawable.sc_btn_l4
    SteamButton.L5 -> R.drawable.sc_btn_l5
    SteamButton.R4 -> R.drawable.sc_btn_r4
    SteamButton.R5 -> R.drawable.sc_btn_r5
    SteamButton.GRIP_LT -> R.drawable.sc_btn_lg
    SteamButton.GRIP_RT -> R.drawable.sc_btn_rg
    SteamButton.LS, SteamButton.RS -> R.drawable.sc_btn_stick_press
}

/** Steam Input icons for every Xbox-native target. Keyboard / screenshot / NONE fall back to a letter. */
private fun targetIconFor(t: XboxTarget): Int? = when (t) {
    XboxTarget.A      -> R.drawable.sc_btn_a_color
    XboxTarget.B      -> R.drawable.sc_btn_b_color
    XboxTarget.X      -> R.drawable.sc_btn_x_color
    XboxTarget.Y      -> R.drawable.sc_btn_y_color
    XboxTarget.LB     -> R.drawable.xbox_btn_lb
    XboxTarget.RB     -> R.drawable.xbox_btn_rb
    XboxTarget.LT_TRIGGER -> R.drawable.sc_btn_lt
    XboxTarget.RT_TRIGGER -> R.drawable.sc_btn_rt
    XboxTarget.SELECT -> R.drawable.xbox_btn_select
    XboxTarget.START  -> R.drawable.xbox_btn_start
    XboxTarget.MODE   -> R.drawable.xbox_btn_logo
    XboxTarget.THUMBL -> R.drawable.xbox_btn_l3
    XboxTarget.THUMBR -> R.drawable.xbox_btn_r3
    else -> null
}

/** Compact label for the letter fallback. */
private fun shortLabelFor(t: XboxTarget): String = when (t) {
    XboxTarget.NONE -> "—"
    XboxTarget.A -> "A"; XboxTarget.B -> "B"; XboxTarget.X -> "X"; XboxTarget.Y -> "Y"
    XboxTarget.LB -> "L1"; XboxTarget.RB -> "R1"
    XboxTarget.LT_TRIGGER -> "L2"; XboxTarget.RT_TRIGGER -> "R2"
    XboxTarget.SELECT -> "···"; XboxTarget.START -> "≡"; XboxTarget.MODE -> "◆"
    XboxTarget.THUMBL -> "L3"; XboxTarget.THUMBR -> "R3"
    XboxTarget.SCREENSHOT -> "📸"
    XboxTarget.KB_VOLUME_UP -> "V+"
    XboxTarget.KB_VOLUME_DOWN -> "V-"
    XboxTarget.KB_PLAY_PAUSE -> "⏯"
    XboxTarget.KB_BACK -> "⮌"
    XboxTarget.KB_HOME -> "🏠"
    XboxTarget.KB_ENTER -> "↵"
    XboxTarget.KB_DPAD_CENTER -> "●"
    XboxTarget.KB_ESCAPE -> "ESC"
    XboxTarget.KB_TAB -> "⇥"
    XboxTarget.KB_SPACE -> "␣"
    XboxTarget.KB_BACKSPACE -> "⌫"
    XboxTarget.KB_MENU -> "☰"
}
