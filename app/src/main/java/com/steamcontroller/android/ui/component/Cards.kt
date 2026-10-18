package com.steamcontroller.android.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Room left under scrolling content so the floating bottom bar never hides its last item. */
val BottomBarClearance = 112.dp

/** Widest a single column of content grows to on large screens, so lines stay readable. */
val ContentMaxWidth = 840.dp

/** True on screens wide enough for two panes, such as an unfolded Fold or a tablet. */
@Composable
fun isWideScreen() = LocalConfiguration.current.screenWidthDp >= 600

/** Fills the screen but keeps the content a centered column no wider than [ContentMaxWidth]. */
fun Modifier.centeredContent() = fillMaxSize()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ContentMaxWidth)

val SectionShape = RoundedCornerShape(18.dp)

/**
 * Bold outline while the element (or a child) holds D-pad focus. Compose's default focus
 * cue is a faint overlay, unreadable from a sofa on Android TV. Touch never focuses
 * clickables, so phones never see it.
 */
fun Modifier.focusRing(shape: Shape, outset: Dp = 0.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent
        // An outset ring leaves a gap, so it still reads on a filled button of the same colour.
        val gap = outset.toPx()
        val stroke = 3.dp.toPx()
        val inset = if (gap > 0f) -gap - stroke / 2 else stroke / 2
        val outline = shape.createOutline(Size(size.width - 2 * inset, size.height - 2 * inset), layoutDirection, this)
        translate(inset, inset) { drawOutline(outline, color, style = Stroke(stroke)) }
    }
}

@Composable
fun cardFill(): Color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)

/** Rounded, outlined card every section of the app is built from. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.focusRing(SectionShape) else Modifier)
            .clip(SectionShape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        shape = SectionShape,
        color = cardFill(),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(content = content)
    }
}

/** Large screen title, with optional subtitle, back arrow and round action buttons on the right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            HeaderIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Card washed with a diagonal gradient of the theme's containers, for the item it stands for. */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(Brush.linearGradient(listOf(colors.tertiaryContainer, colors.primaryContainer, colors.secondaryContainer)))
            .border(1.dp, colors.outline.copy(alpha = 0.5f), SectionShape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/** Round tonal button used in [ScreenHeader] (help, add, reset…). */
@Composable
fun HeaderIconButton(onClick: () -> Unit, highlighted: Boolean = false, icon: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else cardFill(),
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(52.dp).focusRing(CircleShape, outset = 4.dp)
    ) {
        IconButton(onClick = onClick, content = icon)
    }
}

@Composable
fun SectionTitle(text: String, icon: Painter? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing?.invoke()
    }
}

/** Icon, title, description and trailing content laid out as one settings row. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A settings row in its own card, leading to another screen or action. */
@Composable
fun SettingsItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
) {
    SectionCard(onClick = onClick, enabled = enabled, modifier = if (enabled) Modifier else Modifier.alpha(0.38f)) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            icon = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            trailing = trailing
        )
    }
}

/** A settings row in its own card with a switch; tapping anywhere toggles it. */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true
) {
    SettingsItem(
        title = title, subtitle = subtitle, icon = icon, enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { ToggleSwitch(checked = checked, enabled = enabled) }
    )
}

/** Switch showing a check or a cross in its thumb. Not clickable itself: its row is. */
@Composable
fun ToggleSwitch(checked: Boolean, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = {
            Icon(if (checked) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(SwitchDefaults.IconSize))
        }
    )
}

/** Small rounded label, such as a status or a version. */
@Composable
fun Pill(
    text: String,
    icon: Painter? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(shape = CircleShape, color = containerColor, contentColor = contentColor) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One labelled value of an info card: small label above, value below. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Card stacking [rows] with dividers between them. */
@Composable
fun DividedCard(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    SectionCard(modifier = modifier, borderColor = borderColor) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/** Selectable row of a [DividedCard]: a check circle, a title, an optional subtitle and badge. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
            selected -> Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Check, null, Modifier.padding(6.dp), tint = colors.onPrimary)
            }
            else -> Box(Modifier.size(32.dp).border(2.dp, colors.outline, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        badge?.invoke()
    }
}

/** Large pill-shaped action button; [primary] fills it, otherwise it is outlined. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val buttonModifier = modifier.height(56.dp).focusRing(CircleShape, outset = 4.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = buttonModifier, content = content)
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = buttonModifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            content = content
        )
    }
}
