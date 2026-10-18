package com.steamcontroller.android.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.R
import com.steamcontroller.android.ThemeMode
import com.steamcontroller.android.ui.component.focusRing
import com.steamcontroller.android.ui.component.isWideScreen

/**
 * Steam palette from @color/md_* (values-night/ holds the dark one, so colorResource
 * already picks the right set), or the wallpaper palette on Android 12+.
 */
@Composable
fun SteamTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> steamColorScheme(dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun steamColorScheme(dark: Boolean): ColorScheme {
    @Composable fun c(id: Int) = colorResource(id)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(R.color.md_primary), onPrimary = c(R.color.md_on_primary),
        primaryContainer = c(R.color.md_primary_container), onPrimaryContainer = c(R.color.md_on_primary_container),
        secondary = c(R.color.md_secondary), onSecondary = c(R.color.md_on_secondary),
        secondaryContainer = c(R.color.md_secondary_container), onSecondaryContainer = c(R.color.md_on_secondary_container),
        tertiary = c(R.color.md_tertiary), onTertiary = c(R.color.md_on_tertiary),
        tertiaryContainer = c(R.color.md_tertiary_container), onTertiaryContainer = c(R.color.md_on_tertiary_container),
        error = c(R.color.md_error), onError = c(R.color.md_on_error),
        errorContainer = c(R.color.md_error_container), onErrorContainer = c(R.color.md_on_error_container),
        background = c(R.color.md_background), onBackground = c(R.color.md_on_background),
        surface = c(R.color.md_surface), onSurface = c(R.color.md_on_surface),
        surfaceVariant = c(R.color.md_surface_variant), onSurfaceVariant = c(R.color.md_on_surface_variant),
        surfaceTint = c(R.color.md_primary),
        surfaceDim = c(R.color.md_surface_dim), surfaceBright = c(R.color.md_surface_bright),
        surfaceContainerLowest = c(R.color.md_surface_container_lowest),
        surfaceContainerLow = c(R.color.md_surface_container_low),
        surfaceContainer = c(R.color.md_surface_container),
        surfaceContainerHigh = c(R.color.md_surface_container_high),
        surfaceContainerHighest = c(R.color.md_surface_container_highest),
        outline = c(R.color.md_outline), outlineVariant = c(R.color.md_outline_variant),
        inverseSurface = c(R.color.md_inverse_surface), inverseOnSurface = c(R.color.md_inverse_on_surface),
        inversePrimary = c(R.color.md_inverse_primary),
    )
}

data class Tab(val label: String, val icon: Painter, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun AppRoot(tabs: List<Tab>, showTabs: Boolean, content: @Composable () -> Unit) {
    // The root Surface provides the content colour; without it Text renders black on dark themes.
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().glowBackground()) {
            val wide = isWideScreen()
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                if (wide && showTabs) SideRail(tabs)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
            if (!wide && showTabs) {
                FloatingTabBar(tabs, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

/** Two soft colour halos behind the content, drawn as radial gradients that fade to nothing. */
@Composable
fun Modifier.glowBackground(): Modifier {
    val first = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val second = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    return drawBehind {
        val radius = size.maxDimension * 0.6f
        val topRight = Offset(size.width, size.height * 0.15f)
        val bottomLeft = Offset(0f, size.height * 0.8f)
        drawCircle(Brush.radialGradient(listOf(first, Color.Transparent), topRight, radius), radius, topRight)
        drawCircle(Brush.radialGradient(listOf(second, Color.Transparent), bottomLeft, radius), radius, bottomLeft)
    }
}

@Composable
fun FloatingTabBar(tabs: List<Tab>, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp), // keep it opaque
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.padding(bottom = 16.dp)
    ) {
        Row(Modifier.padding(6.dp)) {
            tabs.forEach { tab ->
                Surface(
                    onClick = tab.onClick,
                    shape = CircleShape,
                    color = if (tab.selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (tab.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (tab.selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.width(104.dp).focusRing(CircleShape)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp)) {
                        Icon(tab.icon, tab.label)
                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Large-screen counterpart of [FloatingTabBar]: tab pills stacked left of the content. */
@Composable
fun SideRail(tabs: List<Tab>) {
    Row {
        Column(
            modifier = Modifier.width(200.dp).fillMaxHeight().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            tabs.forEach { tab ->
                Surface(
                    onClick = tab.onClick,
                    shape = CircleShape,
                    color = if (tab.selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (tab.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(
                        if (tab.selected) 1.5.dp else 1.dp,
                        if (tab.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth().focusRing(CircleShape)
                ) {
                    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(tab.icon, null)
                        Spacer(Modifier.width(12.dp))
                        Text(tab.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Box(Modifier.padding(vertical = 48.dp).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}
