package com.steamcontroller.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.ui.component.*
import com.steamcontroller.android.uinput.GamepadProfile

@Composable
fun ProfilesScreen(onBindApps: (profileId: String) -> Unit, onProfileLoaded: () -> Unit) {
    val context = LocalContext.current
    // Bumped after every write so the list re-reads Prefs.
    var version by remember { mutableIntStateOf(0) }
    val profiles = remember(version) { Prefs.listNamedProfiles(context) }
    val activeId = remember(version) { Prefs.getActiveNamedProfileId(context) }
    fun refresh() { version++ }

    var naming by remember { mutableStateOf<NamedProfile?>(null) }   // rename target
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<NamedProfile?>(null) }

    fun suggestNextName(): String {
        val existing = profiles.map { it.name }.toSet()
        var n = 1
        while ("Profile $n" in existing) n++
        return "Profile $n"
    }

    if (creating) {
        NameDialog("Save current settings as game profile", suggestNextName(), onDismiss = { creating = false }) { name ->
            val newProfile = Prefs.captureCurrentAsProfile(context, name.ifEmpty { suggestNextName() })
            Prefs.saveNamedProfile(context, newProfile)
            Prefs.setActiveNamedProfileId(context, newProfile.id)
            creating = false; refresh()
        }
    }
    naming?.let { profile ->
        NameDialog("Rename profile", profile.name, onDismiss = { naming = null }) { name ->
            Prefs.saveNamedProfile(context, profile.copy(name = name.ifEmpty { profile.name }))
            naming = null; refresh()
        }
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${profile.name}\"?") },
            text = { Text("This profile will be removed permanently. Live settings stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.deleteNamedProfile(context, profile.id)
                    deleting = null; refresh()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.profiles_title), subtitle = stringResource(R.string.main_action_profiles_desc)) {
            HeaderIconButton(onClick = { creating = true }, highlighted = true) {
                Icon(Icons.Filled.Add, stringResource(R.string.profiles_save_current))
            }
        }

        if (profiles.isEmpty()) {
            SectionCard {
                SettingsRow(
                    stringResource(R.string.profiles_empty_title),
                    subtitle = stringResource(R.string.profiles_empty_body),
                    icon = { Icon(painterResource(R.drawable.ic_game_profiles), null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }

        for (profile in profiles) {
            ProfileCard(
                profile, isActive = profile.id == activeId,
                onLoad = {
                    Prefs.applyNamedProfile(context, profile)
                    Toast.makeText(context, "Loaded \"${profile.name}\". Restart the service for full effect.", Toast.LENGTH_LONG).show()
                    refresh(); onProfileLoaded()
                },
                onRename = { naming = profile },
                onDuplicate = {
                    Prefs.saveNamedProfile(context, profile.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "${profile.name} (copy)",
                        boundPackages = emptyList(),  // duplicates start unbound
                    ))
                    refresh()
                },
                onBind = { onBindApps(profile.id) },
                onDelete = { deleting = profile },
            )
        }

        Spacer(Modifier.height(if (isWideScreen()) 16.dp else BottomBarClearance))
    }
}

@Composable
private fun ProfileCard(
    profile: NamedProfile,
    isActive: Boolean,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onBind: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SectionCard(borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (isActive) {
                        Pill(
                            "Active",
                            containerColor = colorResource(R.color.status_connected_container),
                            contentColor = colorResource(R.color.status_connected_on_container),
                        )
                    }
                }
                Text(
                    GamepadProfile.fromId(profile.profileId).displayName,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val bound = profile.boundPackages.size
                if (bound > 0) {
                    Text(
                        "Auto-switch on $bound app${if (bound > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            OutlinedButton(onClick = onLoad, enabled = !isActive) { Text(if (isActive) "Loaded" else "Load") }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.profiles_actions_desc))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Bind to apps…") }, onClick = { menu = false; onBind() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Game profile name") }) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
