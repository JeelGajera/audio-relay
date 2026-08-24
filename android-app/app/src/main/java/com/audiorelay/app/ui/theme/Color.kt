package com.audiorelay.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The brand colour schemes, used when Material You dynamic colour is
 * unavailable (below API 31) or the user has turned it off.
 *
 * Seeded from the same deep navy as the app icon and the Windows app's
 * palette, so the two halves of the product look related rather than merely
 * adjacent. Tonal relationships follow the Material 3 spec: a light scheme
 * takes a dark primary on light surfaces, a dark scheme inverts that.
 */

// --- Dark ---
private val DarkPrimary = Color(0xFF9EC5FF)
private val DarkOnPrimary = Color(0xFF003259)
private val DarkPrimaryContainer = Color(0xFF17497D)
private val DarkOnPrimaryContainer = Color(0xFFD3E4FF)
private val DarkSecondary = Color(0xFFBBC7DB)
private val DarkOnSecondary = Color(0xFF253141)
private val DarkSecondaryContainer = Color(0xFF3B4858)
private val DarkOnSecondaryContainer = Color(0xFFD7E3F8)
private val DarkTertiary = Color(0xFFD6BEE4)
private val DarkOnTertiary = Color(0xFF3B2948)
private val DarkTertiaryContainer = Color(0xFF523F5F)
private val DarkOnTertiaryContainer = Color(0xFFF2DAFF)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkBackground = Color(0xFF101418)
private val DarkOnBackground = Color(0xFFE0E2E8)
private val DarkSurface = Color(0xFF101418)
private val DarkOnSurface = Color(0xFFE0E2E8)
private val DarkSurfaceVariant = Color(0xFF42474E)
private val DarkOnSurfaceVariant = Color(0xFFC2C7CF)
private val DarkOutline = Color(0xFF8C9199)
private val DarkOutlineVariant = Color(0xFF42474E)
private val DarkSurfaceContainerLowest = Color(0xFF0B0F12)
private val DarkSurfaceContainerLow = Color(0xFF181C20)
private val DarkSurfaceContainer = Color(0xFF1C2024)
private val DarkSurfaceContainerHigh = Color(0xFF272A2F)
private val DarkSurfaceContainerHighest = Color(0xFF31353A)

// --- Light ---
private val LightPrimary = Color(0xFF00639B)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD0E4FF)
private val LightOnPrimaryContainer = Color(0xFF001D34)
private val LightSecondary = Color(0xFF52606F)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFD5E4F7)
private val LightOnSecondaryContainer = Color(0xFF0F1D2A)
private val LightTertiary = Color(0xFF6B5778)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFF2DAFF)
private val LightOnTertiaryContainer = Color(0xFF251431)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightBackground = Color(0xFFF8F9FF)
private val LightOnBackground = Color(0xFF191C20)
private val LightSurface = Color(0xFFF8F9FF)
private val LightOnSurface = Color(0xFF191C20)
private val LightSurfaceVariant = Color(0xFFDEE3EB)
private val LightOnSurfaceVariant = Color(0xFF42474E)
private val LightOutline = Color(0xFF72777F)
private val LightOutlineVariant = Color(0xFFC2C7CF)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF2F3FA)
private val LightSurfaceContainer = Color(0xFFECEEF4)
private val LightSurfaceContainerHigh = Color(0xFFE7E8EE)
private val LightSurfaceContainerHighest = Color(0xFFE1E2E9)

internal val AudioRelayDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

internal val AudioRelayLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

/**
 * Status colours that aren't part of the Material palette.
 *
 * "Streaming" is a success state and Material 3 has no success role, so it is
 * defined here rather than borrowed from `primary` — borrowing would make it
 * change meaning under dynamic colour, where primary is whatever the user's
 * wallpaper happens to be.
 */
internal val StreamingGreenDark = Color(0xFF6FD68A)
internal val StreamingGreenLight = Color(0xFF1A7F37)
internal val WarningAmberDark = Color(0xFFE3B341)
internal val WarningAmberLight = Color(0xFF9A6700)
