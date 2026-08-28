package com.audiorelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiorelay.app.R
import com.audiorelay.app.audio.OutputDeviceRepository
import com.audiorelay.app.discovery.NsdDiscovery
import com.audiorelay.app.network.AudioReceiver
import com.audiorelay.app.network.ControlChannel
import com.audiorelay.app.network.ControlMessage
import com.audiorelay.app.network.Crypto
import com.audiorelay.app.state.ConnectionStatus
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.PairedDeviceStore
import com.audiorelay.app.state.PairedLaptop
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.state.SettingsStore
import com.audiorelay.app.state.ThemeMode
import com.audiorelay.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service ("mediaPlayback" type) that owns the whole
 * connection lifecycle: discovery, pairing, the control channel, the audio
 * receive/playback pipeline, and applying user settings (output device,
 * jitter buffer depth — see `state/SettingsStore.kt`). Runs with a partial
 * [PowerManager.WakeLock], a [WifiManager.MulticastLock] and a
 * [WifiManager.WifiLock] so mDNS and the UDP socket keep working with the
 * screen off, and so the Wi-Fi radio does not park between beacons and
 * deliver audio in bursts — see `docs/architecture.md` §3.2.
 *
 * Talks to the UI only through [RelayState] (see that file for why a plain
 * singleton is enough here, rather than a bound-service/Messenger setup).
 *
 * **Unverified on real hardware** — see `docs/roadmap.md` Phase 0,
 * specifically whether this actually survives Doze/background restrictions
 * end-to-end on a real device.
 */
class RelayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var store: PairedDeviceStore
    private lateinit var settings: SettingsStore
    private lateinit var outputDevices: OutputDeviceRepository
    private lateinit var discovery: NsdDiscovery
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var multicastLock: WifiManager.MulticastLock

    /**
     * Keeps Wi-Fi out of power-save for the duration of a session.
     *
     * A wake lock keeps the *CPU* awake; it does nothing for the Wi-Fi
     * radio, which independently parks between beacons and lets the access
     * point buffer traffic on its behalf. The AP then releases that traffic
     * in bursts, which for a steady 200 packet/s audio stream means packets
     * arriving bunched and late rather than evenly — measured on a real
     * device as ~9% of packets arriving too late to play, buffer depth
     * swinging between empty and double its target, and a resync every few
     * seconds. It sounds exactly like a lossy network, but the loss is
     * local.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` additionally asks the firmware for a
     * low-latency profile, which is what this workload actually is.
     */
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var deviceChangeCallback: AudioDeviceCallback? = null

    private var connectJob: Job? = null
    private var pendingPairingCode: CompletableDeferred<String>? = null

    /** The receiver for the currently active session, if any — lets Settings changes (output device) apply live, without a reconnect. */
    @Volatile private var activeReceiver: AudioReceiver? = null

    // --- reconnect supervision (docs/roadmap.md Phase 4) ---

    /** Last laptop we tried to reach, so a retry knows where to go. */
    @Volatile private var lastTarget: DiscoveredLaptop? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    /** Set while we're deliberately tearing a session down, so its `finally` doesn't schedule a retry. */
    @Volatile private var suppressReconnect = false

    // --- network-change handling (docs/roadmap.md Phase 4) ---

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeJob: Job? = null

    @Volatile private var activeNetwork: Network? = null

    @Volatile private var lastLinkAddresses: List<String>? = null

    override fun onCreate() {
        super.onCreate()
        store = PairedDeviceStore(this)
        settings = SettingsStore(this)
        outputDevices = OutputDeviceRepository(this)
        discovery = NsdDiscovery(this)
        mediaSession = MediaSessionCompat(this, "AudioRelay").apply { isActive = true }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:relay").apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("$TAG:mdns").apply { setReferenceCounted(false); acquire() }
        wifiLock = runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiManager.createWifiLock(mode, "$TAG:audio").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "could not acquire a Wi-Fi lock; expect bursty delivery", it) }
            .getOrNull()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))

        publishSettingsState()
        refreshOutputDevices()
        deviceChangeCallback = outputDevices.registerChangeCallback { refreshOutputDevices() }
        refreshPairedLaptops()

        registerNetworkCallback()
        startDiscoveryAndAutoConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val name = intent.getStringExtra(EXTRA_NAME)
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (deviceId != null && name != null && host != null && port > 0) {
                    // An explicit user request shouldn't wait behind a backoff
                    // accumulated by earlier automatic attempts.
                    reconnectJob?.cancel()
                    reconnectAttempt = 0
                    val target = DiscoveredLaptop(deviceId, name, host, port)
                    // ...nor behind an attempt that is already in flight.
                    // `connectTo` refuses to start while one is active, so a
                    // tap on Connect used to do nothing at all whenever an
                    // automatic attempt was mid-retry — the user saw it
                    // "connect by itself and keep retrying" instead of being
                    // asked for a code. Tearing the in-flight one down first
                    // makes the tap authoritative.
                    serviceScope.launch {
                        cancelActiveSession()
                        connectTo(target)
                    }
                }
            }
            ACTION_SUBMIT_PAIRING_CODE -> {
                intent.getStringExtra(EXTRA_CODE)?.let { pendingPairingCode?.complete(it) }
            }
            ACTION_SET_OUTPUT_DEVICE -> {
                val key = intent.getStringExtra(EXTRA_DEVICE_KEY) // null = automatic routing
                settings.preferredOutputDeviceKey = key
                RelayState.setPreferredOutputDeviceKey(key)
                activeReceiver?.updatePreferredOutputDevice(key?.let { outputDevices.findByKey(it) })
            }
            ACTION_SET_JITTER_DEPTH -> {
                val depth = intent.getIntExtra(EXTRA_JITTER_DEPTH, SettingsStore.DEFAULT_JITTER_DEPTH_MS)
                settings.jitterTargetDepthMs = depth
                RelayState.setJitterTargetDepthMs(settings.jitterTargetDepthMs)
                // Applies on the next session — rebuilding a live jitter buffer's
                // depth mid-stream isn't worth the complexity for a setting this
                // low-frequency; see docs/roadmap.md Phase 5.
            }
            ACTION_CANCEL_PAIRING -> {
                // The user backed out of the pairing sheet. Cancel the waiting
                // session rather than leaving it blocked on a code that will
                // never arrive.
                serviceScope.launch { cancelActiveSession() }
                RelayState.setStatus(ConnectionStatus.DISCOVERING)
            }
            ACTION_SET_THEME_MODE -> {
                val mode = ThemeMode.fromStoredName(intent.getStringExtra(EXTRA_THEME_MODE))
                settings.themeMode = mode
                RelayState.setThemeMode(mode)
            }
            ACTION_SET_DYNAMIC_COLOR -> {
                val enabled = intent.getBooleanExtra(EXTRA_DYNAMIC_COLOR, true)
                settings.dynamicColor = enabled
                RelayState.setDynamicColor(enabled)
            }
            ACTION_FORGET_LAPTOP -> {
                intent.getStringExtra(EXTRA_DEVICE_ID)?.let { forgotten ->
                    store.forgetLaptop(forgotten)
                    // If that is the laptop we are connected to right now,
                    // the session has to end too. Erasing only the stored key
                    // left the stream running on a key we had just discarded,
                    // and the next connection attempt would try to reuse a
                    // session we could no longer prove we owned — so pairing
                    // never got offered and the app had to be restarted.
                    // Dropping it now means the next connect starts a clean
                    // handshake and asks for a code, which is what the user
                    // just asked for by forgetting it.
                    if (lastTarget?.deviceId == forgotten) {
                        lastTarget = null
                        reconnectJob?.cancel()
                        reconnectAttempt = 0
                        serviceScope.launch {
                            cancelActiveSession()
                            RelayState.setConnectedDeviceName(null)
                            startDiscoveryAndAutoConnect()
                        }
                    }
                }
                refreshPairedLaptops()
            }
            ACTION_STOP -> {
                suppressReconnect = true
                reconnectJob?.cancel()
                // Without this, RelayState keeps whatever status the active
                // session last set (e.g. STREAMING) — cancelActiveSession's
                // `finally` skips its own status update while
                // suppressReconnect is set, so nothing else corrects it. A
                // stale status is exactly what made this feel broken: the
                // app looked like it was still relaying after Stop.
                serviceScope.launch {
                    cancelActiveSession()
                    RelayState.setConnectedDeviceName(null)
                    RelayState.setDiscoveredLaptops(emptyList())
                    // A pairing prompt left pending here would reappear over
                    // the next session, attached to a request nothing is
                    // waiting on any more.
                    RelayState.setPendingPairingTarget(null)
                    RelayState.setStatus(ConnectionStatus.STOPPED)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        suppressReconnect = true
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        discovery.stop()
        deviceChangeCallback?.let { outputDevices.unregisterChangeCallback(it) }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        mediaSession.isActive = false
        mediaSession.release()
        // Cancels connectJob, reconnectJob and networkChangeJob together —
        // they're all children of this scope.
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishSettingsState() {
        RelayState.setPreferredOutputDeviceKey(settings.preferredOutputDeviceKey)
        RelayState.setJitterTargetDepthMs(settings.jitterTargetDepthMs)
        RelayState.setThemeMode(settings.themeMode)
        RelayState.setDynamicColor(settings.dynamicColor)
    }

    private fun refreshOutputDevices() {
        RelayState.setAvailableOutputDevices(outputDevices.listOutputDevices())
    }

    private fun refreshPairedLaptops() {
        RelayState.setPairedLaptops(store.listPairedLaptops().map { (id, name) -> PairedLaptop(id, name) })
    }

    /**
     * [status] lets a network-triggered restart keep showing
     * [ConnectionStatus.NETWORK_CHANGED] until something is actually found,
     * rather than immediately claiming a normal discovery pass.
     */
    private fun startDiscoveryAndAutoConnect(status: ConnectionStatus = ConnectionStatus.DISCOVERING) {
        RelayState.setStatus(status)
        discovery.start { laptops ->
            RelayState.setDiscoveredLaptops(laptops)
            val lastId = store.lastLaptopDeviceId
            if (connectJob?.isActive != true && lastId != null) {
                laptops.firstOrNull { it.deviceId == lastId }?.let { connectTo(it) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reconnect supervision
    // ---------------------------------------------------------------------

    /**
     * Schedules a retry against [lastTarget] after an exponential backoff.
     * Called when a session ends on its own — a dropped socket, a missed
     * heartbeat — as opposed to us tearing it down deliberately.
     *
     * Without this, a dropped connection only ever recovered if NSD happened
     * to re-announce the service, which it has no obligation to do.
     */
    private fun scheduleReconnect() {
        val target = lastTarget ?: return
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = ReconnectBackoff.delayMsFor(reconnectAttempt)
            reconnectAttempt++
            RelayState.setStatus(
                if (reconnectAttempt > ATTEMPTS_BEFORE_HELP) {
                    ConnectionStatus.CONNECTION_TROUBLE
                } else {
                    ConnectionStatus.RECONNECTING
                },
            )
            Log.i(TAG, "reconnecting to ${target.name} in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            connectTo(target)
        }
    }

    /** Tears down the active session without letting it schedule its own retry. */
    private suspend fun cancelActiveSession() {
        suppressReconnect = true
        try {
            // Close the receiver *before* joining, not just inside the job's
            // own `finally`. The playback loop is normally parked inside
            // `AudioTrack.write`, which is not a cancellable suspension
            // point — and if AudioFlinger has evicted the track, that write
            // never returns at all. Waiting for the job first therefore hung
            // forever, which is why stopping the relay sometimes did
            // nothing. Closing here releases the track and the socket, which
            // is what actually unblocks both loops so the join can complete.
            activeReceiver?.close()
            connectJob?.cancelAndJoin()
            connectJob = null
        } finally {
            suppressReconnect = false
        }
    }

    // ---------------------------------------------------------------------
    // Network-change handling
    // ---------------------------------------------------------------------

    /**
     * Watches the *default* network specifically. That callback fires when
     * the system switches which network apps use — Wi-Fi to cellular, one
     * SSID to another, joining a hotspot — which is exactly the event we
     * care about, and far less chatty than a broad network request that also
     * reports things like signal-strength changes.
     */
    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = manager

        // Seed from the network we're already on. Registering replays
        // onAvailable for the current default immediately, and without this
        // seed that replay would look like a switch — restarting discovery
        // and flashing "network changed" on every single launch.
        activeNetwork = runCatching { manager.activeNetwork }.getOrNull()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = activeNetwork
                if (previous == network) return // the startup replay, or a duplicate
                activeNetwork = network
                lastLinkAddresses = null
                onNetworkChanged(
                    if (previous == null) "a network became available" else "default network switched",
                )
            }

            override fun onLost(network: Network) {
                if (activeNetwork != network) return
                activeNetwork = null
                lastLinkAddresses = null
                onNetworkChanged("network lost")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network != activeNetwork) return
                // Catches a DHCP renewal that lands us on a different subnet —
                // same Network object, but every socket and the mDNS group
                // membership are now bound to the wrong address.
                val addresses = linkProperties.linkAddresses
                    .mapNotNull { it.address?.hostAddress }
                    .sorted()
                val previous = lastLinkAddresses
                lastLinkAddresses = addresses
                if (previous != null && previous != addresses) {
                    onNetworkChanged("interface addresses changed")
                }
            }
        }

        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                Log.w(TAG, "could not register network callback; falling back to heartbeat-only recovery", it)
                networkCallback = null
            }
    }

    /**
     * Debounced entry point. Android fires these callbacks in bursts during a
     * transition (lost, available, link properties, all within a few hundred
     * milliseconds), and reacting to each one would tear down and rebuild the
     * connection several times over.
     */
    private fun onNetworkChanged(reason: String) {
        Log.i(TAG, "network change: $reason")
        networkChangeJob?.cancel()
        networkChangeJob = serviceScope.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MS)
            applyNetworkChange()
        }
    }

    private suspend fun applyNetworkChange() {
        reconnectJob?.cancel()
        cancelActiveSession()

        // A new network deserves an immediate attempt rather than inheriting
        // whatever backoff the previous network's failures had built up.
        reconnectAttempt = 0

        // NSD's sockets and multicast group membership belong to the old
        // interface, so discovery has to be torn down and restarted rather
        // than left running.
        discovery.stop()
        RelayState.setDiscoveredLaptops(emptyList())
        RelayState.setConnectedDeviceName(null)
        updateNotification(null)
        startDiscoveryAndAutoConnect(ConnectionStatus.NETWORK_CHANGED)
    }

    /**
     * Prompts for the pairing code and submits it, re-prompting if the
     * laptop rejects it.
     *
     * A mistyped digit must not cost the whole connection: without this the
     * rejection tore the session down and went back to the reconnect
     * backoff, so the user's next attempt was a fresh connection rather
     * than a second try at the prompt still in front of them.
     */
    private suspend fun pairInteractively(
        channel: ControlChannel,
        laptop: DiscoveredLaptop,
        ack: ControlMessage.HelloAck,
    ): ControlChannel.Paired {
        var lastRejection: String? = null
        // The laptop allows a bounded number of attempts per connection;
        // stay under it so the last try still gets a real answer.
        repeat(MAX_PAIRING_CODE_ATTEMPTS) {
            RelayState.setPairingError(lastRejection)
            RelayState.setStatus(ConnectionStatus.PAIRING_CODE_REQUIRED)
            RelayState.setPendingPairingTarget(laptop)
            val deferred = CompletableDeferred<String>()
            pendingPairingCode = deferred
            val code = deferred.await()
            try {
                val paired = channel.pairWithCode(code, ack.nonce, ack.device_id, ack.device_name)
                RelayState.setPairingError(null)
                return paired
            } catch (e: ControlChannel.PairingRejected) {
                Log.w(TAG, "pairing code rejected: ${e.message}")
                lastRejection = e.message
            }
        }
        RelayState.setPairingError(null)
        throw ControlChannel.PairingRejected("too many incorrect pairing codes")
    }

    /** Also called directly by the UI when the user picks a device from the list. */
    fun connectTo(laptop: DiscoveredLaptop) {
        if (connectJob?.isActive == true) return
        lastTarget = laptop
        connectJob = serviceScope.launch {
            RelayState.setStatus(ConnectionStatus.CONNECTING)
            var receiver: AudioReceiver? = null
            var channel: ControlChannel? = null
            try {
                val sessionReceiver = AudioReceiver().also {
                    receiver = it
                    activeReceiver = it
                }
                val activeChannel = ControlChannel(
                    host = laptop.host,
                    port = laptop.port,
                    deviceId = store.deviceId,
                    deviceName = Build.MODEL ?: "Android device",
                    audioPort = sessionReceiver.localPort,
                ).also { channel = it }

                val ack = activeChannel.connect()
                // `ack.paired` (the laptop's own memory of us) and having a
                // locally-saved key are independent facts — "Forget" on
                // this phone only touches this phone's storage, so a laptop
                // we forgot still reports us as paired. Falling back to the
                // pairing-code flow whenever we don't actually have a key,
                // regardless of what the laptop claims, is what lets this
                // self-heal with a fresh code instead of retrying a REPAIR
                // that can never succeed.
                val savedLaptop = if (ack.paired) store.getSavedLaptop(ack.device_id) else null
                val paired = if (savedLaptop != null) {
                    try {
                        activeChannel.repair(ack.device_id, ack.device_name, Crypto.hexToBytes(savedLaptop.sessionKeyHex), ack.nonce)
                    } catch (e: ControlChannel.PairingRejected) {
                        // The laptop refused the key we had stored for it, so
                        // that key is stale and no amount of retrying will
                        // make it work. Discard it and pair properly instead
                        // — on this same connection, which the laptop still
                        // accepts a pairing attempt on. Retrying it as though
                        // it were a network blip is what used to leave the
                        // phone reconnecting forever while the laptop sat
                        // there displaying a code nobody was ever asked for.
                        Log.w(TAG, "stored key rejected (${e.message}); pairing again", e)
                        store.forgetLaptop(ack.device_id)
                        refreshPairedLaptops()
                        pairInteractively(activeChannel, laptop, ack)
                    }
                } else {
                    pairInteractively(activeChannel, laptop, ack)
                }
                pendingPairingCode = null
                RelayState.setPendingPairingTarget(null)
                store.saveLaptop(paired.laptopDeviceId, paired.laptopDeviceName, Crypto.toHex(paired.sessionKey))
                refreshPairedLaptops()

                val preferredDevice = settings.preferredOutputDeviceKey?.let { outputDevices.findByKey(it) }
                sessionReceiver.configureSession(
                    sessionKey = paired.sessionKey,
                    sessionId = paired.sessionId,
                    sampleRateHz = paired.sampleRateHz,
                    channels = paired.channels,
                    jitterTargetDepthMs = settings.jitterTargetDepthMs,
                    preferredOutputDevice = preferredDevice,
                )
                RelayState.setStatus(ConnectionStatus.STREAMING)
                RelayState.setConnectedDeviceName(paired.laptopDeviceName)
                updateNotification(paired.laptopDeviceName)
                reconnectAttempt = 0 // a session that actually reached streaming clears the backoff

                launch { sessionReceiver.receiveLoop() }
                launch { sessionReceiver.playbackLoop() }
                activeChannel.heartbeatLoop() // suspends until disconnect
            } catch (e: CancellationException) {
                // We cancelled this session on purpose (network change,
                // shutdown). Rethrow so the job completes as cancelled rather
                // than looking like it finished normally — swallowing this is
                // what breaks structured concurrency.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connection to ${laptop.name} ended: ${e.message}", e)
            } finally {
                receiver?.close()
                channel?.close()
                activeReceiver = null
                RelayState.setPlaybackLevel(0f)
                // Clear pairing state unconditionally: if the session died
                // while waiting for a code, the prompt would otherwise stay on
                // screen forever with nothing behind it.
                pendingPairingCode = null
                RelayState.setPendingPairingTarget(null)
                RelayState.setPairingError(null)
                RelayState.setConnectedDeviceName(null)
                updateNotification(null)
                // When we cancelled this session ourselves (network change,
                // shutdown), the caller owns what happens next and has already
                // set an appropriate status.
                if (!suppressReconnect) {
                    RelayState.setStatus(ConnectionStatus.DISCONNECTED)
                    scheduleReconnect()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(streamingFrom: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (streamingFrom != null) {
            getString(R.string.notification_streaming, streamingFrom)
        } else {
            getString(R.string.notification_idle)
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // ACTION_STOP existed but was never reachable from anywhere — the
            // only way to stop the service was to force-stop the app.
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(streamingFrom: String?) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(streamingFrom))
    }

    companion object {
        private const val TAG = "RelayService"
        private const val NOTIFICATION_CHANNEL_ID = "audio_relay_streaming"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L // 12h safety cap, not an expected session length

        /**
         * Collapses the burst of callbacks Android emits during a single
         * network transition into one teardown/restart.
         */
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 750L

        /**
         * Tries at the pairing prompt per connection. The laptop allows a
         * few per connection; staying under that means the final attempt
         * still gets a real answer rather than the connection dying first.
         */
        private const val MAX_PAIRING_CODE_ATTEMPTS = 3

        /**
         * Failed reconnects before the UI stops saying "retrying shortly"
         * and starts telling the user what to actually check. Retrying
         * continues underneath — a laptop that is merely asleep should
         * still be picked up — but silently looping forever with no
         * explanation is what made this infuriating to sit in front of.
         */
        private const val ATTEMPTS_BEFORE_HELP = 4

        const val ACTION_CONNECT = "com.audiorelay.app.action.CONNECT"
        const val ACTION_SUBMIT_PAIRING_CODE = "com.audiorelay.app.action.SUBMIT_PAIRING_CODE"
        const val ACTION_SET_OUTPUT_DEVICE = "com.audiorelay.app.action.SET_OUTPUT_DEVICE"
        const val ACTION_SET_JITTER_DEPTH = "com.audiorelay.app.action.SET_JITTER_DEPTH"
        const val ACTION_FORGET_LAPTOP = "com.audiorelay.app.action.FORGET_LAPTOP"
        const val ACTION_STOP = "com.audiorelay.app.action.STOP"
        const val ACTION_CANCEL_PAIRING = "com.audiorelay.app.action.CANCEL_PAIRING"
        const val ACTION_SET_THEME_MODE = "com.audiorelay.app.action.SET_THEME_MODE"
        const val ACTION_SET_DYNAMIC_COLOR = "com.audiorelay.app.action.SET_DYNAMIC_COLOR"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_DEVICE_KEY = "device_key"
        const val EXTRA_JITTER_DEPTH = "jitter_depth_ms"
        const val EXTRA_THEME_MODE = "theme_mode"
        const val EXTRA_DYNAMIC_COLOR = "dynamic_color"
    }
}
