package com.focusquest.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.eeg.LinkState
import com.focusquest.app.eeg.PairedDevice
import com.focusquest.app.eeg.SimMode
import com.focusquest.app.eeg.SourceKind
import com.focusquest.app.ui.theme.FqColors

@Composable
fun DeviceScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val hub = vm.hub
    val eeg by hub.state.collectAsStateWithLifecycle()
    val simMode by hub.simMode.collectAsStateWithLifecycle()

    // Bumped whenever something might have changed the paired list / permissions.
    var refresh by remember { mutableIntStateOf(0) }
    var hasPermission by remember { mutableStateOf(hub.hasConnectPermission()) }
    var enabled by remember { mutableStateOf(hub.bluetoothEnabled()) }
    var devices by remember { mutableStateOf(emptyList<PairedDevice>()) }

    LaunchedEffect(refresh) {
        hasPermission = hub.hasConnectPermission()
        enabled = hub.bluetoothEnabled()
        devices = if (hasPermission && enabled) hub.pairedDevices() else emptyList()
    }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Headset", style = MaterialTheme.typography.headlineMedium) }

        // Live readout
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(eeg.deviceName ?: "Not connected", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (eeg.link) {
                                LinkState.Connected -> if (eeg.source == SourceKind.Simulator) "Simulated brain signal" else "Signal quality ${eeg.signalQuality}%"
                                LinkState.Connecting -> "Connecting…"
                                LinkState.Error -> eeg.message ?: "Connection failed"
                                LinkState.Disconnected -> "Connect a paired headset below"
                            },
                            color = if (eeg.link == LinkState.Error) FqColors.Danger else FqColors.TextDim,
                        )
                    }
                    if (eeg.link == LinkState.Connecting) CircularProgressIndicator(Modifier.size(28.dp))
                    if (eeg.connected) OutlinedButton(onClick = { hub.stop() }) { Text("Disconnect") }
                }
                if (eeg.connected) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        AttentionRing(eeg.attention, "Attention", FqColors.Focus)
                        AttentionRing(eeg.meditation, "Calm", FqColors.Calm)
                    }
                    if (!eeg.signalOk) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Weak signal: make sure the sensor touches your forehead and the ear clip is attached.",
                            color = FqColors.Warm,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Brainwave bands", color = FqColors.TextDim)
                    Spacer(Modifier.height(8.dp))
                    BandBars(eeg.bands)
                }
            }
        }

        // Bluetooth headset section
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Paired headsets", Modifier.weight(1f))
                IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        when {
            !hub.bluetoothSupported() -> item { Text("This device doesn't support Bluetooth. You can still use the simulator.", color = FqColors.TextDim) }
            !hasPermission -> item {
                Card {
                    Text("FocusQuest needs Bluetooth permission to talk to your headset.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }) { Text("Allow Bluetooth") }
                }
            }
            !enabled -> item {
                Card {
                    Text("Bluetooth is turned off.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                }
            }
            else -> {
                if (devices.isEmpty()) {
                    item { Text("No paired devices yet.", color = FqColors.TextDim) }
                }
                items(devices, key = { it.address }) { d ->
                    DeviceRow(
                        device = d,
                        active = eeg.source == SourceKind.Headset && eeg.deviceName == d.name && eeg.link != LinkState.Error,
                        onClick = { hub.connectHeadset(d) },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair a new headset in Bluetooth settings") }
                }
            }
        }

        item {
            Card {
                Text("How to pair", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. Switch the headband on (its light blinks).\n" +
                        "2. Open Bluetooth settings and pair it (common PIN: 0000 or 1234).\n" +
                        "3. Come back here and tap it in the list.\n" +
                        "Works with NeuroSky-based headsets such as BrainLink Lite and MindWave Mobile.",
                    color = FqColors.TextDim,
                )
            }
        }

        // Simulator
        item { SectionTitle("No headset? Use the simulator") }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Science, null, tint = FqColors.Calm)
                    Spacer(Modifier.width(10.dp))
                    Text("Generates a realistic attention signal so you can try every game.", Modifier.weight(1f), color = FqColors.TextDim)
                }
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SimMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = simMode == mode,
                            onClick = { hub.setSimMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, SimMode.entries.size),
                        ) { Text(if (mode == SimMode.Auto) "Automatic" else "Hold to focus") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { hub.startSimulator() },
                    enabled = eeg.source != SourceKind.Simulator,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (eeg.source == SourceKind.Simulator) "Simulator running" else "Start simulator") }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PairedDevice, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) FqColors.Primary.copy(alpha = 0.25f) else FqColors.Surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (device.looksLikeHeadset) Icons.Filled.Headphones else Icons.Filled.Bluetooth,
                null,
                tint = if (device.looksLikeHeadset) FqColors.Focus else FqColors.TextDim,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (device.looksLikeHeadset) "EEG headset · ${device.address}" else device.address,
                    color = FqColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(if (active) "Selected" else "Connect", color = FqColors.Primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
