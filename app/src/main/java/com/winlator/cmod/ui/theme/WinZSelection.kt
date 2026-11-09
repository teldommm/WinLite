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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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

    // The pill's own animation state lives here, not in the host composable, so a state hoisted
    // above the host keeps the pill's position when the host leaves the composition and a new
    // one takes its place (e.g. the Library switching between its pager and grid layouts when a
    // filter has no games) — the pill then glides to the new spot instead of appearing there.
    internal val rect = Animatable(Rect.Zero, Rect.VectorConverter)
    internal val alpha = Animatable(0f)

    internal fun hasTarget(key: Any): Boolean {
        if (tick < 0) return false // read to subscribe the caller's snapshot
        return targets.containsKey(key)
    }

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

// Marker (compared by identity) for "the selected key has no item".
private val NO_TARGET = Rect(-1f, -1f, -1f, -1f)

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
    // Optional outline drawn on the pill itself (it travels and fades with it), e.g. the accent
    // ring of the selected navigation item. Unspecified = fill only.
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val rect = state.rect
    val alpha = state.alpha

    LaunchedEffect(state, selectedKey) {
        snapshotFlow {
            when {
                // Nothing to highlight: no selection, or the selection has no item in this group.
                selectedKey == null || !state.hasTarget(selectedKey) -> NO_TARGET
                // null = the item exists but isn't measured in the current host yet (right after
                // the host was replaced); wait for the real position instead of fading out.
                else -> state.rectOf(selectedKey)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { target ->
                when {
                    // Nothing to highlight (selection has no item in this group).
                    target === NO_TARGET -> alpha.animateTo(0f, tween(WinZMotion.DurationS))
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
                    if (borderColor != Color.Unspecified) {
                        // Stroke is centred on its path: inset by half the width so the ring stays
                        // inside the pill's bounds like the fill.
                        val w = borderWidth.toPx()
                        drawRoundRect(
                            color = borderColor.copy(alpha = borderColor.alpha * a),
                            topLeft = r.topLeft + Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(r.width - w, r.height - w),
                            cornerRadius = CornerRadius((cornerRadius.toPx() - w / 2).coerceAtLeast(0f)),
                            style = Stroke(w)
                        )
                    }
                }
            },
        content = content
    )
}
