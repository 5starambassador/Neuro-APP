package com.focusquest.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.Screen
import com.focusquest.app.data.Profile
import com.focusquest.app.session.SessionEngine
import com.focusquest.app.ui.theme.FqColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Profile?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        SectionTitle("Players")
        profiles.forEach { p ->
            val active = p.id == settings.activeProfileId
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (active) FqColors.Primary.copy(alpha = 0.22f) else FqColors.Surface,
                modifier = Modifier.fillMaxWidth().clickable { vm.store.setActiveProfile(p.id) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("Age ${p.age}", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (active) Icon(Icons.Filled.CheckCircle, "Active", tint = FqColors.Focus)
                    if (profiles.size > 1) {
                        IconButton(onClick = { toDelete = p }) { Icon(Icons.Filled.Delete, "Delete ${p.name}", tint = FqColors.TextDim) }
                    }
                }
            }
        }
        OutlinedButton(onClick = { vm.push(Screen.AddProfile) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add player")
        }

        SectionTitle("Classroom")
        Card {
            Text("Train with a class over Wi‑Fi", style = MaterialTheme.typography.titleMedium)
            Text(
                "Students join from their phones; a trainer tablet watches everyone live, runs group sessions and creates reports.",
                color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.push(Screen.JoinClass) }, modifier = Modifier.weight(1f)) { Text("Join a class") }
                OutlinedButton(onClick = { vm.push(Screen.Trainer) }, modifier = Modifier.weight(1f)) { Text("Trainer mode") }
            }
        }

        SectionTitle("Training")
        Card {
            ToggleRow(
                "Adaptive difficulty",
                "Raises the goal when focus is easy and lowers it when it's hard, every 30 seconds.",
                settings.adaptiveThreshold,
            ) { v -> vm.store.updateSettings { it.copy(adaptiveThreshold = v) } }
            Spacer(Modifier.height(16.dp))
            Text("Starting goal: ${settings.threshold}", style = MaterialTheme.typography.titleMedium)
            Text("The attention level the games count as focused.", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.threshold.toFloat(),
                onValueChange = { v -> vm.store.updateSettings { it.copy(threshold = (v / 5).roundToInt() * 5) } },
                valueRange = SessionEngine.MIN_THRESHOLD.toFloat()..SessionEngine.MAX_THRESHOLD.toFloat(),
                steps = (SessionEngine.MAX_THRESHOLD - SessionEngine.MIN_THRESHOLD) / 5 - 1,
            )
            Spacer(Modifier.height(8.dp))
            Text("Default session length: ${settings.defaultDurationSec / 60} min", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.defaultDurationSec / 60f,
                onValueChange = { v -> vm.store.updateSettings { it.copy(defaultDurationSec = v.roundToInt() * 60) } },
                valueRange = 1f..20f,
                steps = 18,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow("Vibration", "Buzz on every 15-second focus streak.", settings.haptics) { v ->
                vm.store.updateSettings { it.copy(haptics = v) }
            }
        }

        SectionTitle("About")
        Card {
            Text("FocusQuest 1.0.0", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(DISCLAIMER, color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text("All data stays on this device.", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("This permanently removes the player and all of their session history.") },
            confirmButton = {
                TextButton(onClick = { vm.store.deleteProfile(p.id); toDelete = null }) { Text("Delete", color = FqColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
