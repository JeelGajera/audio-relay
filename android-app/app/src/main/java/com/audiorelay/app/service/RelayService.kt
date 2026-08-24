package com.audiorelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiorelay.app.R
import com.audiorelay.app.discovery.NsdDiscovery
import com.audiorelay.app.network.AudioReceiver
import com.audiorelay.app.network.ControlChannel
import com.audiorelay.app.network.Crypto
import com.audiorelay.app.state.ConnectionStatus
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.PairedDeviceStore
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service ("mediaPlayback" type) that owns the whole
 * connection lifecycle: discovery, pairing, the control channel, and the
 * audio receive/playback pipeline. Runs with a partial [PowerManager.WakeLock]
 * and a [WifiManager.MulticastLock] so mDNS and the UDP socket keep working
 * with the screen off — see `docs/architecture.md` §3.2.
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
    private lateinit var discovery: NsdDiscovery
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var mediaSession: MediaSessionCompat

    private var connectJob: Job? = null
    private var pendingPairingCode: CompletableDeferred<String>? = null

    override fun onCreate() {
        super.onCreate()
        store = PairedDeviceStore(this)
        discovery = NsdDiscovery(this)
        mediaSession = MediaSessionCompat(this, "AudioRelay").apply { isActive = true }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:relay").apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("$TAG:mdns").apply { setReferenceCounted(false); acquire() }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))
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
                    connectTo(DiscoveredLaptop(deviceId, name, host, port))
                }
            }
            ACTION_SUBMIT_PAIRING_CODE -> {
                intent.getStringExtra(EXTRA_CODE)?.let { pendingPairingCode?.complete(it) }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectJob?.cancel()
        discovery.stop()
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDiscoveryAndAutoConnect() {
        RelayState.setStatus(ConnectionStatus.DISCOVERING)
        discovery.start { laptops ->
            RelayState.setDiscoveredLaptops(laptops)
            val lastId = store.lastLaptopDeviceId
            if (connectJob?.isActive != true && lastId != null) {
                laptops.firstOrNull { it.deviceId == lastId }?.let { connectTo(it) }
            }
        }
    }

    /** Also called directly by the UI when the user picks a device from the list. */
    fun connectTo(laptop: DiscoveredLaptop) {
        if (connectJob?.isActive == true) return
        connectJob = serviceScope.launch {
            RelayState.setStatus(ConnectionStatus.CONNECTING)
            var receiver: AudioReceiver? = null
            var channel: ControlChannel? = null
            try {
                val activeReceiver = AudioReceiver().also { receiver = it }
                val activeChannel = ControlChannel(
                    host = laptop.host,
                    port = laptop.port,
                    deviceId = store.deviceId,
                    deviceName = Build.MODEL ?: "Android device",
                    audioPort = activeReceiver.localPort,
                ).also { channel = it }

                val ack = activeChannel.connect()
                val paired = if (ack.paired) {
                    val saved = store.getSavedLaptop(ack.device_id)
                        ?: error("laptop reports us as paired but we have no saved key for it")
                    val nonce = ack.nonce ?: error("HELLO_ACK.paired was true but no nonce was sent")
                    activeChannel.repair(ack.device_id, ack.device_name, Crypto.hexToBytes(saved.sessionKeyHex), nonce)
                } else {
                    RelayState.setStatus(ConnectionStatus.PAIRING_CODE_REQUIRED)
                    RelayState.setPendingPairingTarget(laptop)
                    val deferred = CompletableDeferred<String>()
                    pendingPairingCode = deferred
                    val code = deferred.await()
                    activeChannel.pairWithCode(code, ack.device_id, ack.device_name)
                }
                pendingPairingCode = null
                RelayState.setPendingPairingTarget(null)
                store.saveLaptop(paired.laptopDeviceId, paired.laptopDeviceName, Crypto.toHex(paired.sessionKey))

                activeReceiver.configureSession(paired.sessionKey, paired.sessionId, paired.sampleRateHz, paired.channels)
                RelayState.setStatus(ConnectionStatus.STREAMING)
                RelayState.setConnectedDeviceName(paired.laptopDeviceName)
                updateNotification(paired.laptopDeviceName)

                launch { activeReceiver.receiveLoop() }
                launch { activeReceiver.playbackLoop() }
                activeChannel.heartbeatLoop() // suspends until disconnect
            } catch (e: Exception) {
                Log.w(TAG, "connection to ${laptop.name} ended: ${e.message}", e)
            } finally {
                receiver?.close()
                channel?.close()
                RelayState.setStatus(ConnectionStatus.DISCONNECTED)
                RelayState.setConnectedDeviceName(null)
                updateNotification(null)
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
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

        const val ACTION_CONNECT = "com.audiorelay.app.action.CONNECT"
        const val ACTION_SUBMIT_PAIRING_CODE = "com.audiorelay.app.action.SUBMIT_PAIRING_CODE"
        const val ACTION_STOP = "com.audiorelay.app.action.STOP"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
    }
}
