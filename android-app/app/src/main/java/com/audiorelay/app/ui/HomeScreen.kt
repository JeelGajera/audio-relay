package com.audiorelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiorelay.app.state.ConnectionStatus
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.RelayState

@Composable
fun HomeScreen(
    onSelectLaptop: (DiscoveredLaptop) -> Unit,
    onSubmitPairingCode: (String) -> Unit,
) {
    val status by RelayState.status.collectAsState()
    val connectedDeviceName by RelayState.connectedDeviceName.collectAsState()
    val discoveredLaptops by RelayState.discoveredLaptops.collectAsState()
    val pendingPairingTarget by RelayState.pendingPairingTarget.collectAsState()

    Column(modifier = Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("audio-relay", style = MaterialTheme.typography.headlineMedium)

        StatusLine(status, connectedDeviceName)

        if (status == ConnectionStatus.DISCOVERING || status == ConnectionStatus.CONNECTING) {
            Text("Nearby laptops:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(discoveredLaptops) { laptop ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(laptop.name, style = MaterialTheme.typography.titleSmall)
                            Text("${laptop.host}:${laptop.port}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onSelectLaptop(laptop) }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
            if (discoveredLaptops.isEmpty()) {
                Text(
                    "Searching… make sure the Windows app is running on the same network.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (status == ConnectionStatus.PAIRING_CODE_REQUIRED && pendingPairingTarget != null) {
        PairingCodeDialog(laptopName = pendingPairingTarget!!.name, onSubmit = onSubmitPairingCode)
    }
}

@Composable
private fun StatusLine(status: ConnectionStatus, connectedDeviceName: String?) {
    val text = when (status) {
        ConnectionStatus.IDLE -> "Starting…"
        ConnectionStatus.DISCOVERING -> "Looking for laptops on this network…"
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.PAIRING_CODE_REQUIRED -> "Enter the pairing code shown on the laptop"
        ConnectionStatus.STREAMING -> "● Streaming from ${connectedDeviceName ?: "laptop"}"
        ConnectionStatus.DISCONNECTED -> "Disconnected — will retry automatically"
        ConnectionStatus.RECONNECTING -> "Reconnecting…"
        ConnectionStatus.NETWORK_CHANGED -> "Network changed — looking for the laptop again…"
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PairingCodeDialog(laptopName: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* pairing is required to proceed; nothing useful to dismiss to */ },
        title = { Text("Pair with $laptopName") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
                label = { Text("6-digit code") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6) {
                Text("Pair")
            }
        },
    )
}
