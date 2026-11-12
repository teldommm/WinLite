package com.winlator.cmod.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The app's progress indicators. Every bar and spinner uses the blue control accent over a
// surfaceVariant track (the same pair the switches and the env-variable pill use), and a
// determinate bar glides to its new value with the WinZMotion curve instead of stepping.

/** Linear bar. `progress == null` (or negative) means indeterminate. */
@Composable
fun WinZProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    color: Color = controlAccentColor(),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    if (progress == null || progress < 0f) {
        LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
    } else {
        val shown by animateFloatAsState(
            progress.coerceIn(0f, 1f),
            tween(WinZMotion.DurationM, easing = FastOutSlowInEasing),
            label = "winzProgress",
        )
        LinearProgressIndicator(progress = { shown }, modifier = modifier, color = color, trackColor = trackColor)
    }
}

/** Determinate ring that glides to its new value. */
@Composable
fun WinZProgressRing(
    progress: Float,
    size: Dp = 24.dp,
    strokeWidth: Dp = 5.dp,
    modifier: Modifier = Modifier,
    color: Color = controlAccentColor(),
) {
    val shown by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        tween(WinZMotion.DurationM, easing = FastOutSlowInEasing),
        label = "winzProgressRing",
    )
    CircularProgressIndicator(
        progress = { shown },
        modifier = modifier.size(size),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = strokeWidth,
    )
}

/** Indeterminate spinner in the accent colour. Pass `color` for a spinner on a filled button. */
@Composable
fun WinZSpinner(
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
    modifier: Modifier = Modifier,
    color: Color = controlAccentColor(),
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        color = color,
        trackColor = Color.Transparent,
    )
}
