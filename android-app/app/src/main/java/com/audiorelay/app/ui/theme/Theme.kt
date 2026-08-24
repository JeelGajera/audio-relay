package com.audiorelay.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.audiorelay.app.state.ThemeMode

/**
 * Status colours with no Material 3 equivalent.
 *
 * "Streaming" is a success state and Material has no success role. Borrowing
 * `primary` for it would be wrong under dynamic colour, where primary is
 * whatever the user's wallpaper happens to be — a green-meaning-good
 * indicator would silently become pink.
 */
@Immutable
data class StatusColors(
    val streaming: Color,
    val warning: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(streaming = StreamingGreenLight, warning = WarningAmberLight)
}

/**
 * The app's theme.
 *
 * Before this existed the app called a bare `MaterialTheme {}`, which meant
 * the stock baseline light palette permanently — a device in dark mode still
 * got a white app.
 *
 * @param themeMode the user's Settings choice; [ThemeMode.SYSTEM] follows the OS.
 * @param dynamicColor Material You wallpaper colours on API 31+. Falls back
 *   to the brand scheme below that, and whenever the user turns it off.
 */
@Composable
fun AudioRelayTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> AudioRelayDarkColors
        else -> AudioRelayLightColors
    }

    val statusColors = if (darkTheme) {
        StatusColors(streaming = StreamingGreenDark, warning = WarningAmberDark)
    } else {
        StatusColors(streaming = StreamingGreenLight, warning = WarningAmberLight)
    }

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AudioRelayTypography,
            shapes = AudioRelayShapes,
            content = content,
        )
    }
}
