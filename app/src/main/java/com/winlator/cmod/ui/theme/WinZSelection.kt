package com.winlator.cmod.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Sliding selection pill — one highlight shape that travels between the items of an exclusive
// group (bottom/landscape navigation, All / Favorites / Recent chips) instead of every item
// fading its own highlight in and out, which reads as the pill "teleporting".
//
// Usage:
//     val pill = rememberSlidingPillState()
//     SlidingPillHost(pill, selectedKey, color, cornerRadius) {
//         items.forEach { Item(Modifier.slidingPillTarget(pill, it.key)) }
//     }
//
// The items stay transparent; the host draws the single pill behind them. Item bounds are
// measured relative to the host with LayoutCoordinates.localPositionOf, so ancestor transforms
// (the tab transition in MainShell) and the item's own press-scale can't skew the target if the
// target modifier sits on a node without its own graphicsLayer.
//
// A selectedKey that has no target (e.g. File Manager is selected, which has no nav button)
// fades the pill out; when a target comes back the pill fades in at its place instead of
// sliding in from wherever it was last.
@Stable
class SlidingPillState {
    private var container: LayoutCoordinates? = null
    private val targets = HashMap<Any, LayoutCoordinates>()

    // Bumped whenever a measurement arrives, so snapshotFlow in the host re-reads the rect.
    private var tick by mutableIntStateOf(0)

    internal fun setContainer(coordinates: LayoutCoordinates) {
        container = coordinates
        tick++
    }

    internal fun setTarget(key: Any, coordinates: LayoutCoordinates) {
        targets[key] = coordinates
        tick++
    }

    // Bounds of the item for [key] in the host's coordinate space; null until both are measured.
    internal fun rectOf(key: Any): Rect? {
        if (tick < 0) return null // read to subscribe the caller's snapshot
        val host = container ?: return null
        val item = targets[key] ?: return null
        if (!host.isAttached || !item.isAttached) return null
        val size = item.size.toSize()
        if (size.width <= 0f || size.height <= 0f) return null
        return Rect(host.localPositionOf(item, Offset.Zero), size)
    }
}

@Composable
fun rememberSlidingPillState(): SlidingPillState = remember { SlidingPillState() }

// Marks an item as a place the pill can slide to. Put it on a node WITHOUT its own press-scale
// graphicsLayer (wrap the scaled content in an outer node that carries this modifier).
fun Modifier.slidingPillTarget(state: SlidingPillState, key: Any): Modifier =
    this.onGloballyPositioned { state.setTarget(key, it) }

// Scrolls the nearest scrolling parent so this item is on screen when it becomes selected (a chip
// half out of view in a horizontally scrolling row, an entry below the fold of a list). Waits
// one frame so the node is laid out before it asks. Put it after slidingPillTarget in the chain.
@Composable
fun Modifier.keepInViewWhen(selected: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            try {
                requester.bringIntoView()
            } catch (ignored: IllegalStateException) {
                // Not attached any more (left while waiting): nothing to scroll to.
            }
        }
    }
    return this.bringIntoViewRequester(requester)
}

@Composable
fun SlidingPillHost(
    state: SlidingPillState,
    selectedKey: Any?,
    color: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(state, selectedKey) {
        snapshotFlow { selectedKey?.let { state.rectOf(it) } }
            .distinctUntilChanged()
            .collectLatest { target ->
                when {
                    // Nothing to highlight (selection has no item in this group).
                    target == null -> alpha.animateTo(0f, tween(WinZMotion.DurationS))
                    // Very first placement: appear in place, no slide from the corner.
                    rect.value == Rect.Zero -> {
                        rect.snapTo(target)
                        alpha.snapTo(1f)
                    }
                    // Coming back after being hidden: fade in at the new place.
                    alpha.value == 0f -> {
                        rect.snapTo(target)
                        alpha.animateTo(1f, tween(WinZMotion.DurationS))
                    }
                    else -> coroutineScope {
                        launch { alpha.animateTo(1f, tween(100)) }
                        rect.animateTo(target, WinZMotion.selection())
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { state.setContainer(it) }
            .drawBehind {
                val a = alpha.value
                val r = rect.value
                if (a > 0f && r.width > 0f && r.height > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = color.alpha * a),
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(cornerRadius.toPx())
                    )
                }
            },
        content = content
    )
}
