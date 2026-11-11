package com.winlator.cmod.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Entrance animations for content that arrives after the screen is already on display (a list
// loaded from disk, a catalog fetched from the network). Without them the content "teleports" in.

// A list card: fades in and rises ~14dp, staggered by position. Only the first REVEAL_ROWS rows
// (what the first screen shows) play it; rows further down appear at once, so scrolling never
// waits for an entrance.
// `revealed` remembers which ids already played it, so a card scrolled out of a LazyColumn and
// back, or the same card after a filter / category switch, appears at once instead of replaying.
private const val REVEAL_ROWS = 8

@Composable
fun RevealOnEnter(
    id: String,
    index: Int,
    revealed: MutableSet<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val already = remember(id) { index >= REVEAL_ROWS || id in revealed }
    val progress = remember(id) { Animatable(if (already) 1f else 0f) }
    LaunchedEffect(id) {
        if (!already) {
            revealed.add(id)
            delay(index * 35L)
            progress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 14.dp.toPx()
        }
    ) { content() }
}

// A whole screen body: fades in once, when it first enters the composition (e.g. when the
// "model is still loading" placeholder is replaced by the real screen). Later updates of the
// same screen don't replay it.
@Composable
fun FadeInOnShow(enabled: Boolean = true, durationMillis: Int = 260, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) { if (enabled) alpha.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

// A row (or group of rows) that appears or disappears when a setting changes: it unfolds in height
// and fades, so the rows below slide instead of jumping. Put the leading gap/divider inside.
@Composable
fun RevealSection(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(220, delayMillis = 40)),
        exit = shrinkVertically(tween(210, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
    ) {
        Column { content() }
    }
}

// A row that is new (just added to a list) unfolds and fades in; a row that was already there
// (first composition, scrolling back) is shown at once. `isNew` is read once, when the row enters.
@Composable
fun AppearOnAdd(isNew: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(!isNew).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(220, delayMillis = 40))
    ) {
        Column { content() }
    }
}
