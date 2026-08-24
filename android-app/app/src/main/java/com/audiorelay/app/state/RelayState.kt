package com.audiorelay.app.state

import com.audiorelay.app.audio.OutputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredLaptop(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
)

enum class ConnectionStatus {
    IDLE,
    DISCOVERING,
    CONNECTING,
    PAIRING_CODE_REQUIRED,
    STREAMING,
    DISCONNECTED,

    /** Dropped, and waiting out the reconnect backoff before trying again. */
    RECONNECTING,

    /**
     * The device moved to a different network (or lost one), so discovery has
     * been restarted from scratch. Distinct from [DISCONNECTED] because the
     * cause and the expected recovery are different — see
     * `service/RelayService.kt`.
     */
    NETWORK_CHANGED,
}

/**
 * Process-wide observable state, read by the Compose UI and written by
 * [com.audiorelay.app.service.RelayService]. The service and activity run
 * in the same process, so a plain singleton with `StateFlow`s is simpler
 * than a bound-service/Messenger setup for what's currently a
 * single-session app — see `AGENTS.md` on not over-engineering for
 * hypothetical future needs.
 */
object RelayState {
    private val _status = MutableStateFlow(ConnectionStatus.IDLE)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _discoveredLaptops = MutableStateFlow<List<DiscoveredLaptop>>(emptyList())
    val discoveredLaptops: StateFlow<List<DiscoveredLaptop>> = _discoveredLaptops.asStateFlow()

    /** Set while [ConnectionStatus.PAIRING_CODE_REQUIRED] — the UI reads this to prompt the user. */
    private val _pendingPairingTarget = MutableStateFlow<DiscoveredLaptop?>(null)
    val pendingPairingTarget: StateFlow<DiscoveredLaptop?> = _pendingPairingTarget.asStateFlow()

    // --- Settings screen state (see ui/SettingsScreen.kt) ---

    private val _availableOutputDevices = MutableStateFlow<List<OutputDevice>>(emptyList())
    val availableOutputDevices: StateFlow<List<OutputDevice>> = _availableOutputDevices.asStateFlow()

    private val _preferredOutputDeviceKey = MutableStateFlow<String?>(null)
    val preferredOutputDeviceKey: StateFlow<String?> = _preferredOutputDeviceKey.asStateFlow()

    private val _jitterTargetDepthChunks = MutableStateFlow(SettingsStore.DEFAULT_JITTER_DEPTH_CHUNKS)
    val jitterTargetDepthChunks: StateFlow<Int> = _jitterTargetDepthChunks.asStateFlow()

    private val _pairedLaptops = MutableStateFlow<List<PairedLaptop>>(emptyList())
    val pairedLaptops: StateFlow<List<PairedLaptop>> = _pairedLaptops.asStateFlow()

    fun setStatus(status: ConnectionStatus) {
        _status.value = status
    }

    fun setConnectedDeviceName(name: String?) {
        _connectedDeviceName.value = name
    }

    fun setDiscoveredLaptops(laptops: List<DiscoveredLaptop>) {
        _discoveredLaptops.value = laptops
    }

    fun setPendingPairingTarget(target: DiscoveredLaptop?) {
        _pendingPairingTarget.value = target
    }

    fun setAvailableOutputDevices(devices: List<OutputDevice>) {
        _availableOutputDevices.value = devices
    }

    fun setPreferredOutputDeviceKey(key: String?) {
        _preferredOutputDeviceKey.value = key
    }

    fun setJitterTargetDepthChunks(chunks: Int) {
        _jitterTargetDepthChunks.value = chunks
    }

    fun setPairedLaptops(laptops: List<PairedLaptop>) {
        _pairedLaptops.value = laptops
    }
}

data class PairedLaptop(val deviceId: String, val name: String)
