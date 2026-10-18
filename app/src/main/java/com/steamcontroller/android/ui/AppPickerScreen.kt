package com.steamcontroller.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.service.UsageStatsHelper
import com.steamcontroller.android.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

/**
 * Lets the user tick installed launcher apps that should auto-switch to a given
 * NamedProfile. The selection is saved back onto the profile's `boundPackages` list.
 */
@Composable
fun AppPickerScreen(profileId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val checked = remember {
        mutableStateListOf<String>().apply {
            addAll(Prefs.listNamedProfiles(context).firstOrNull { it.id == profileId }?.boundPackages.orEmpty())
        }
    }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    // Auto-switch only works once PACKAGE_USAGE_STATS is granted; offer the system screen.
    var askUsage by remember { mutableStateOf(!UsageStatsHelper.hasPermission(context)) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            // Same list the home screen shows: launcher (or Leanback on TV) apps only.
            val pm = context.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val seen = HashSet<String>()
            (pm.queryIntentActivities(main, 0) + pm.queryIntentActivities(leanback, 0)).mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (!seen.add(pkg)) return@mapNotNull null
                try {
                    AppEntry(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap())
                } catch (_: Throwable) { null }
            }.sortedBy { it.label.lowercase() }
        }
    }

    fun save() {
        val existing = Prefs.listNamedProfiles(context).firstOrNull { it.id == profileId }
        if (existing == null) {
            Toast.makeText(context, "Profile no longer exists", Toast.LENGTH_SHORT).show()
        } else {
            Prefs.saveNamedProfile(context, existing.copy(boundPackages = checked.toList()))
            Toast.makeText(context, "Bindings saved (${checked.size})", Toast.LENGTH_SHORT).show()
        }
        onBack()
    }

    if (askUsage) {
        AlertDialog(
            onDismissRequest = { askUsage = false },
            title = { Text("Enable auto-switch?") },
            text = {
                Text(
                    "To swap profiles automatically when you launch a bound app, allow Steam Controller " +
                    "to read \"Usage data access\" in Android Settings. Without it, your bindings are saved " +
                    "but no automatic switching will happen."
                )
            },
            confirmButton = {
                TextButton(onClick = { askUsage = false; UsageStatsHelper.openSettingsScreen(context) }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { askUsage = false }) { Text("Skip") } },
        )
    }

    LazyColumn(
        Modifier.centeredContent().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(stringResource(R.string.app_picker_title), subtitle = stringResource(R.string.app_picker_hint), onBack = onBack) {
                HeaderIconButton(onClick = ::save, highlighted = true) { Icon(Icons.Filled.Check, stringResource(R.string.menu_save)) }
            }
        }
        val list = apps
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            items(list, key = { it.packageName }) { app ->
                val on = app.packageName in checked
                SectionCard(onClick = { if (on) checked.remove(app.packageName) else checked.add(app.packageName) }) {
                    SettingsRow(
                        app.label,
                        icon = { Image(app.icon, null, Modifier.size(32.dp)) },
                        trailing = { ToggleSwitch(on) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
