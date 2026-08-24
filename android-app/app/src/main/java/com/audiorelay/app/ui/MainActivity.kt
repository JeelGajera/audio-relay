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
            StatusScreen(
                onSelectLaptop = ::connectToLaptop,
                onSubmitPairingCode = ::submitPairingCode,
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
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SUBMIT_PAIRING_CODE
            putExtra(RelayService.EXTRA_CODE, code)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
