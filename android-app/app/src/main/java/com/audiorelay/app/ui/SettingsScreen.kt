package com.audiorelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.state.SettingsStore

@Composable
fun SettingsScreen(
    onSelectOutputDevice: (String?) -> Unit,
    onSetJitterDepth: (Int) -> Unit,
    onForgetLaptop: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        OutputDevicePicker(onSelectOutputDevice)
        HorizontalDivider()
        JitterDepthSlider(onSetJitterDepth)
        HorizontalDivider()
        PairedLaptopsList(onForgetLaptop)
    }
}

@Composable
private fun OutputDevicePicker(onSelectOutputDevice: (String?) -> Unit) {
    val devices by RelayState.availableOutputDevices.collectAsState()
    val selectedKey by RelayState.preferredOutputDeviceKey.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Audio output", style = MaterialTheme.typography.titleMedium)
        Text(
            "Which device audio gets sent to. Automatic follows Android's normal routing (whatever's already connected).",
            style = MaterialTheme.typography.bodySmall,
        )
        val selectedLabel = if (selectedKey == null) {
            "Automatic"
        } else {
            devices.firstOrNull { it.key == selectedKey }?.label ?: "Automatic"
        }
        Box {
            Button(onClick = { expanded = true }) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Automatic") },
                    onClick = {
                        expanded = false
                        onSelectOutputDevice(null)
                    },
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label) },
                        onClick = {
                            expanded = false
                            onSelectOutputDevice(device.key)
                        },
                    )
                }
            }
        }
        if (devices.isEmpty()) {
            Text("No output devices detected yet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JitterDepthSlider(onSetJitterDepth: (Int) -> Unit) {
    val current by RelayState.jitterTargetDepthChunks.collectAsState()
    var sliderValue by remember(current) { mutableStateOf(current.toFloat()) }

    Column {
        Text("Jitter buffer depth", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lower = less delay. Higher = more resistant to network hiccups (e.g. on a phone hotspot). Applies on the next reconnect.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetJitterDepth(sliderValue.toInt()) },
                valueRange = SettingsStore.MIN_JITTER_DEPTH_CHUNKS.toFloat()..SettingsStore.MAX_JITTER_DEPTH_CHUNKS.toFloat(),
                steps = SettingsStore.MAX_JITTER_DEPTH_CHUNKS - SettingsStore.MIN_JITTER_DEPTH_CHUNKS - 1,
                modifier = Modifier.weight(1f),
            )
            Text("~${sliderValue.toInt() * 10}ms", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PairedLaptopsList(onForgetLaptop: (String) -> Unit) {
    val laptops by RelayState.pairedLaptops.collectAsState()

    Column {
        Text("Paired laptops", style = MaterialTheme.typography.titleMedium)
        if (laptops.isEmpty()) {
            Text("No laptop has paired with this phone yet.", style = MaterialTheme.typography.bodySmall)
        }
        laptops.forEach { laptop ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(laptop.name, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onForgetLaptop(laptop.deviceId) }) { Text("Forget") }
            }
        }
    }
}
