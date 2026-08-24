package com.audiorelay.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiorelay.app.state.DiscoveredLaptop

private enum class Tab(val label: String) { HOME("Home"), SETTINGS("Settings"), ABOUT("About") }

/**
 * Minimalist app shell (docs/roadmap.md Phase 6): three screens, a bottom
 * nav bar, no navigation library — matches `windows-app`'s three-tab
 * layout (`windows-app/src/ui/mod.rs`) so both apps feel like the same
 * product.
 */
@Composable
fun AudioRelayApp(
    onSelectLaptop: (DiscoveredLaptop) -> Unit,
    onSubmitPairingCode: (String) -> Unit,
    onSelectOutputDevice: (String?) -> Unit,
    onSetJitterDepth: (Int) -> Unit,
    onForgetLaptop: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.HOME) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.HOME,
                            onClick = { tab = Tab.HOME },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text(Tab.HOME.label) },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.SETTINGS,
                            onClick = { tab = Tab.SETTINGS },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(Tab.SETTINGS.label) },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.ABOUT,
                            onClick = { tab = Tab.ABOUT },
                            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            label = { Text(Tab.ABOUT.label) },
                        )
                    }
                },
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                    when (tab) {
                        Tab.HOME -> HomeScreen(onSelectLaptop, onSubmitPairingCode)
                        Tab.SETTINGS -> SettingsScreen(onSelectOutputDevice, onSetJitterDepth, onForgetLaptop)
                        Tab.ABOUT -> AboutScreen()
                    }
                }
            }
        }
    }
}
