package com.audiorelay.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiorelay.app.service.RelayService
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.state.ThemeMode
import com.audiorelay.app.ui.theme.AudioRelayTheme
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way — the
            foreground-service notification still posts, it's just less visible without this permission on API 33+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate: the splash screen replaces the window's
        // starting background, so installing it later means a visible flash of
        // the default theme first.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Draws behind the system bars. Mandatory from targetSdk 35, and the
        // reason the screens below apply their own window insets.
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        startRelayService()

        setContent {
            val themeMode by RelayState.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by RelayState.dynamicColor.collectAsStateWithLifecycle()

            AudioRelayTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                AudioRelayApp(
                    onSelectLaptop = ::connectToLaptop,
                    onSubmitPairingCode = ::submitPairingCode,
                    onCancelPairing = ::cancelPairing,
                    onSelectOutputDevice = ::selectOutputDevice,
                    onSetJitterDepth = ::setJitterDepth,
                    onForgetLaptop = ::forgetLaptop,
                    onSetThemeMode = ::setThemeMode,
                    onSetDynamicColor = ::setDynamicColor,
                    onToggleRelay = ::toggleRelay,
                )
            }
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

    /**
     * The Home screen's Start/Stop switch. Stopping fully tears the service
     * down (see `RelayService.ACTION_STOP`); starting again means launching
     * a fresh instance — same call [startRelayService] makes on first
     * launch, since a stopped service has already been destroyed rather
     * than merely paused.
     */
    private fun toggleRelay(enabled: Boolean) {
        if (enabled) {
            startRelayService()
        } else {
            sendServiceAction(RelayService.ACTION_STOP) {}
        }
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

    private fun cancelPairing() {
        sendServiceAction(RelayService.ACTION_CANCEL_PAIRING) {}
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

    private fun setThemeMode(mode: ThemeMode) {
        sendServiceAction(RelayService.ACTION_SET_THEME_MODE) {
            putExtra(RelayService.EXTRA_THEME_MODE, mode.name)
        }
    }

    private fun setDynamicColor(enabled: Boolean) {
        sendServiceAction(RelayService.ACTION_SET_DYNAMIC_COLOR) {
            putExtra(RelayService.EXTRA_DYNAMIC_COLOR, enabled)
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
