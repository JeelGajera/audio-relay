package com.audiorelay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiorelay.app.R
import com.audiorelay.app.state.ConnectionStatus
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.ui.components.LevelVisualizer
import com.audiorelay.app.ui.components.SectionCard
import com.audiorelay.app.ui.components.StatusPill
import com.audiorelay.app.ui.theme.LocalStatusColors

@Composable
fun HomeScreen(
    onSelectLaptop: (DiscoveredLaptop) -> Unit,
    onSubmitPairingCode: (String) -> Unit,
    onCancelPairing: () -> Unit,
) {
    val status by RelayState.status.collectAsStateWithLifecycle()
    val connectedDeviceName by RelayState.connectedDeviceName.collectAsStateWithLifecycle()
    val discoveredLaptops by RelayState.discoveredLaptops.collectAsStateWithLifecycle()
    val pendingPairingTarget by RelayState.pendingPairingTarget.collectAsStateWithLifecycle()
    val playbackLevel by RelayState.playbackLevel.collectAsStateWithLifecycle()

    val statusColors = LocalStatusColors.current
    val pillColor = when (status) {
        ConnectionStatus.STREAMING -> statusColors.streaming
        ConnectionStatus.DISCONNECTED, ConnectionStatus.NETWORK_CHANGED -> statusColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.padding(top = 24.dp))

        Text(
            text = stringResource(headlineFor(status)),
            style = MaterialTheme.typography.headlineMedium,
        )
        StatusPill(
            label = stringResource(
                statusLabelFor(status),
                connectedDeviceName ?: stringResource(R.string.status_generic_laptop),
            ),
            color = pillColor,
            pulsing = status == ConnectionStatus.STREAMING,
        )

        AnimatedVisibility(visible = status == ConnectionStatus.STREAMING) {
            SectionCard(
                title = stringResource(R.string.home_now_playing),
                subtitle = stringResource(R.string.home_now_playing_hint),
            ) {
                LevelVisualizer(level = playbackLevel, color = statusColors.streaming)
            }
        }

        AnimatedVisibility(visible = status.showsDeviceList()) {
            DiscoveredLaptops(discoveredLaptops, onSelectLaptop)
        }
    }

    val pairingTarget = pendingPairingTarget
    if (status == ConnectionStatus.PAIRING_CODE_REQUIRED && pairingTarget != null) {
        PairingCodeSheet(
            laptopName = pairingTarget.name,
            onSubmit = onSubmitPairingCode,
            onDismiss = onCancelPairing,
        )
    }
}

private fun ConnectionStatus.showsDeviceList(): Boolean = when (this) {
    ConnectionStatus.IDLE,
    ConnectionStatus.DISCOVERING,
    ConnectionStatus.CONNECTING,
    ConnectionStatus.NETWORK_CHANGED,
    -> true
    else -> false
}

private fun headlineFor(status: ConnectionStatus): Int = when (status) {
    ConnectionStatus.STREAMING -> R.string.home_headline_streaming
    ConnectionStatus.PAIRING_CODE_REQUIRED -> R.string.home_headline_pairing
    ConnectionStatus.DISCONNECTED, ConnectionStatus.RECONNECTING -> R.string.home_headline_reconnecting
    ConnectionStatus.NETWORK_CHANGED -> R.string.home_headline_network_changed
    else -> R.string.home_headline_looking
}

private fun statusLabelFor(status: ConnectionStatus): Int = when (status) {
    ConnectionStatus.IDLE -> R.string.status_idle
    ConnectionStatus.DISCOVERING -> R.string.status_discovering
    ConnectionStatus.CONNECTING -> R.string.status_connecting
    ConnectionStatus.PAIRING_CODE_REQUIRED -> R.string.status_pairing_required
    ConnectionStatus.STREAMING -> R.string.status_streaming
    ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
    ConnectionStatus.RECONNECTING -> R.string.status_reconnecting
    ConnectionStatus.NETWORK_CHANGED -> R.string.status_network_changed
}

@Composable
private fun DiscoveredLaptops(
    laptops: List<DiscoveredLaptop>,
    onSelectLaptop: (DiscoveredLaptop) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.home_nearby_title),
        subtitle = stringResource(R.string.home_nearby_hint),
    ) {
        if (laptops.isEmpty()) {
            // A spinner alongside the explanation: an empty list with only
            // text reads as "none found and given up" rather than "looking".
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    stringResource(R.string.home_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SectionCard
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(laptops, key = { it.deviceId }) { laptop ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(laptop.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${laptop.host}:${laptop.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onSelectLaptop(laptop) }) {
                        Text(stringResource(R.string.action_connect))
                    }
                }
            }
        }
    }
}
