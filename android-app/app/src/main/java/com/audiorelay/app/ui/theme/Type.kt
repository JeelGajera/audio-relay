package com.audiorelay.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Type scale.
 *
 * Mostly the Material 3 default — it is a good scale and there is no reason
 * to fight it — with the display and headline sizes pulled in a little. This
 * is a utility with one screen of content at a time, so the stock display
 * sizes (up to 57sp) are wildly oversized for it.
 */
internal val AudioRelayTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineLarge = headlineLarge.copy(fontSize = 28.sp, lineHeight = 36.sp),
        headlineMedium = headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
        headlineSmall = headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * The pairing code, and nothing else.
 *
 * Monospace with a wide letter spacing: the digits are read off one screen
 * and typed into another, so they need to be unambiguous and easy to track
 * across. Tabular monospace also stops the boxes from twitching as digits
 * change.
 */
val PairingCodeDigitStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    textAlign = TextAlign.Center,
)
