package com.audiorelay.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale, rounded a little more generously than the Material default.
 *
 * The softer corners are what read as "current" rather than as a 2021 app,
 * and they pair with the card-heavy layout the screens use — tight radii on
 * large surfaces look unfinished.
 */
internal val AudioRelayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
