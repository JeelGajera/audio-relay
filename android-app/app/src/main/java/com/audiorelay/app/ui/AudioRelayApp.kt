package com.audiorelay.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.audiorelay.app.R
import com.audiorelay.app.state.DiscoveredLaptop
import com.audiorelay.app.state.ThemeMode

private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_status, Icons.Filled.Home),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
    ABOUT("about", R.string.nav_about, Icons.Filled.Info),
}

/**
 * App shell.
 *
 * Uses a real [NavHost] rather than the `remember { mutableStateOf(tab) }` it
 * replaces — that reset to Home on every rotation, because `remember` does
 * not survive configuration change. The nav library also gives predictable
 * back behaviour and the screen transitions below for free.
 */
@Composable
fun AudioRelayApp(
    onSelectLaptop: (DiscoveredLaptop) -> Unit,
    onSubmitPairingCode: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onSelectOutputDevice: (String?) -> Unit,
    onSetJitterDepth: (Int) -> Unit,
    onForgetLaptop: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep a single entry per tab rather than
                                // stacking a new copy on every switch.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                destination.icon,
                                contentDescription = stringResource(destination.labelRes),
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Cross-fade rather than slide: these are peer tabs, not a
            // hierarchy, so a directional slide would imply a relationship
            // that isn't there.
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    onSelectLaptop = onSelectLaptop,
                    onSubmitPairingCode = onSubmitPairingCode,
                    onCancelPairing = onCancelPairing,
                )
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(
                    onSelectOutputDevice = onSelectOutputDevice,
                    onSetJitterDepth = onSetJitterDepth,
                    onForgetLaptop = onForgetLaptop,
                    onSetThemeMode = onSetThemeMode,
                    onSetDynamicColor = onSetDynamicColor,
                )
            }
            composable(Destination.ABOUT.route) {
                AboutScreen()
            }
        }
    }
}
