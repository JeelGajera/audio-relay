package com.audiorelay.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * A titled card. Every screen is built from these, which is most of what
 * gives the three screens a common rhythm.
 */
@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (title != null || subtitle != null) {
                Box(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/** Label and optional hint on the left, a control on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** Coloured capsule with a leading dot; the dot breathes when [pulsing]. */
@Composable
fun StatusPill(
    label: String,
    color: Color,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "status-pulse-alpha",
    )
    val dotAlpha = if (pulsing) pulse else 1f
    val animatedColor by animateColorAsState(color, label = "status-color")

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(animatedColor.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = dotAlpha)),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = animatedColor)
    }
}

/**
 * Audio-level visualiser.
 *
 * Bars rather than a single meter: a row of bars reacting together reads as
 * "sound is flowing" at a glance, which is the actual question a user has
 * when they look at this screen. The per-bar phase offsets keep it from
 * looking like a single block pumping up and down.
 *
 * Shows a flat baseline at zero rather than disappearing — a visualiser that
 * vanishes when silent is indistinguishable from a broken one.
 */
@Composable
fun LevelVisualizer(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
) {
    val transition = rememberInfiniteTransition(label = "level-phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "level-phase-value",
    )
    val smoothed by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "level-smoothed",
    )

    Row(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(barCount) { index ->
            // Two offset sine components so neighbouring bars differ without
            // the whole row marching in a single visible wave.
            val wobble = 0.5f + 0.5f * sin(phase + index * 0.55f) * sin(phase * 0.37f + index * 0.21f)
            val height = (0.12f + smoothed * wobble).coerceIn(0.06f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.35f + 0.65f * height)),
            )
        }
    }
}
