package com.audiorelay.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.audiorelay.app.service.RelayService
import com.audiorelay.app.state.DiscoveredLaptop

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way — the
            foreground-service notification still posts, it's just less visible without this permission on API 33+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        startRelayService()

        setContent {
            AudioRelayApp(
                onSelectLaptop = ::connectToLaptop,
                onSubmitPairingCode = ::submitPairingCode,
                onSelectOutputDevice = ::selectOutputDevice,
                onSetJitterDepth = ::setJitterDepth,
                onForgetLaptop = ::forgetLaptop,
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startRelayService() {
        ContextCompat.startForegroundService(this, Intent(this, RelayService::class.java))
    }

    private fun connectToLaptop(laptop: DiscoveredLaptop) {
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_CONNECT
            putExtra(RelayService.EXTRA_DEVICE_ID, laptop.deviceId)
            putExtra(RelayService.EXTRA_NAME, laptop.name)
            putExtra(RelayService.EXTRA_HOST, laptop.host)
            putExtra(RelayService.EXTRA_PORT, laptop.port)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun submitPairingCode(code: String) {
        sendServiceAction(RelayService.ACTION_SUBMIT_PAIRING_CODE) {
            putExtra(RelayService.EXTRA_CODE, code)
        }
    }

    private fun selectOutputDevice(deviceKey: String?) {
        sendServiceAction(RelayService.ACTION_SET_OUTPUT_DEVICE) {
            putExtra(RelayService.EXTRA_DEVICE_KEY, deviceKey)
        }
    }

    private fun setJitterDepth(chunks: Int) {
        sendServiceAction(RelayService.ACTION_SET_JITTER_DEPTH) {
            putExtra(RelayService.EXTRA_JITTER_DEPTH, chunks)
        }
    }

    private fun forgetLaptop(deviceId: String) {
        sendServiceAction(RelayService.ACTION_FORGET_LAPTOP) {
            putExtra(RelayService.EXTRA_DEVICE_ID, deviceId)
        }
    }

    private inline fun sendServiceAction(action: String, configure: Intent.() -> Unit) {
        val intent = Intent(this, RelayService::class.java).apply {
            this.action = action
            configure()
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
