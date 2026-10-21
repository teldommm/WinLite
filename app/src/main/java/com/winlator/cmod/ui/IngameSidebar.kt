package com.winlator.cmod.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.WinlatorThemeType
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import com.winlator.cmod.ui.theme.hairlineColor

// The whole in-game sidebar (шторка) as ONE composition.
//
// Before: left_sidebar.xml owned a native LinearLayout card (sidebar_panel_bg: rounded
// background + outline + elevation, colored through the IngameSidebarTheme_* XML overlay),
// a 58dp rail ComposeView, and a native ScrollView holding five separate panel
// ComposeViews whose visibility XServerDisplayActivity toggled by hand with View.animate().
// That meant two color sources (XML overlay for the card, WinZTheme for everything inside)
// and six independent compositions.
//
// Now: one ComposeView. The card, rail and panel area are drawn here from WinZTheme only;
// panel switching is an AnimatedContent keyed on the rail's selectedId; each panel keeps
// its own scroll position. Java keeps addressing panels by the same R.id.LLSubXxx ints
// (now plain <item type="id"> entries in res/values/ids_sidebar.xml, no views behind them),
// so openSidebarPanel()/activeSidebarPanelId/rail items didn't have to change identity.

internal class SidebarPanelEntry(val generation: Int, val content: @Composable () -> Unit)

class IngameSidebarController internal constructor(initialSelectedId: Int) {
    // Same object Java already drives via setSelectedId()/setPaused().
    val railState = SidebarRailState(initialSelectedId)

    internal val panels = mutableStateMapOf<Int, SidebarPanelEntry>()

    // Every (re)registration bumps the generation, and the panel is composed under
    // key(generation). That reproduces exactly what calling ComposeView.setContent() again
    // used to do: a fresh composition, so panels that seed their UI state with
    // remember { mutableStateOf(state.x) } (Input after returning from the profile editor)
    // pick up the new snapshot instead of keeping stale remembered values.
    internal fun setPanel(panelId: Int, content: @Composable () -> Unit) {
        val next = (panels[panelId]?.generation ?: 0) + 1
        panels[panelId] = SidebarPanelEntry(next, content)
    }
}

object IngameSidebarHost {
    @JvmStatic
    fun attach(
        composeView: ComposeView,
        railItems: List<SidebarRailItemData>,
        initialSelectedId: Int,
        railCallbacks: SidebarRailCallbacks
    ): IngameSidebarController {
        val controller = IngameSidebarController(initialSelectedId)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                IngameSidebar(controller, railItems, railCallbacks)
            }
        }
        return controller
    }
}

// Outer card fill — the values the IngameSidebarTheme_* overlay used to feed
// sidebar_panel_bg, expressed in WinZTheme terms: Black = its #151515 background at ~90%
// (#E6151515), White/AMOLED = their own translucent surface (#E6FFFFFF / #E6050505).
// The outline was ingameSidebarOutline, which already equalled outlineVariant in all three.
@Composable
internal fun sidebarPanelColor(): Color =
    if (WinlatorThemeManager.currentTheme() == WinlatorThemeType.BLACK) {
        MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface
    }

@Composable
private fun IngameSidebar(
    controller: IngameSidebarController,
    railItems: List<SidebarRailItemData>,
    railCallbacks: SidebarRailCallbacks
) {
    val shape = MaterialTheme.shapes.extraLarge // 22dp, same as sidebar_panel_bg
    val scrollStates = remember { HashMap<Int, ScrollState>() }
    val slideOffsetPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    // Margins match the old IngameSidebarCard (start 6 / top 10 / end 10 / bottom 10).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 6.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .shadow(elevation = 8.dp, shape = shape, clip = false)
                .clip(shape)
                .background(sidebarPanelColor())
                .border(BorderStroke(1.dp, hairlineColor()), shape)
        ) {
            SidebarRail(
                state = controller.railState,
                items = railItems,
                callbacks = railCallbacks,
                modifier = Modifier.width(58.dp).fillMaxHeight()
            )

            // Every registered panel stays composed; only the selected one is visible. This used
            // to be an AnimatedContent, which disposed a panel when you switched away — and since
            // panels seed their UI from the snapshot taken when they were attached (Graphics,
            // HUD, Screen), coming back showed stale values: e.g. Super Resolution "off" while it
            // was on, so picking a ReShade effect skipped turning FSR off and both ran at once.
            // The old per-panel ComposeViews were only hidden, never disposed; this restores that.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                controller.panels.forEach { (panelId, entry) ->
                    key(panelId) {
                        SidebarPanelLayer(
                            selected = controller.railState.selectedId == panelId,
                            scroll = scrollStates.getOrPut(panelId) { ScrollState(0) },
                            slideOffsetPx = slideOffsetPx,
                            entry = entry
                        )
                    }
                }
            }
        }
    }
}

// One panel of the sidebar. Same motion the native openSidebarPanel() did — fade in plus an 8dp
// slide from the left over 130ms — while the outgoing panel fades out; hidden panels keep their
// composition (and scroll position) but take no input, focus or accessibility.
@Composable
private fun SidebarPanelLayer(selected: Boolean, scroll: ScrollState, slideOffsetPx: Int, entry: SidebarPanelEntry) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(if (selected) 130 else 90),
        label = "sidebarPanel"
    )
    val hidden = !selected && progress == 0f
    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (selected) 1f else 0f)
            .graphicsLayer {
                alpha = progress
                translationX = if (selected) -slideOffsetPx * (1f - progress) else 0f
            }
            .then(
                if (selected) Modifier
                else Modifier
                    .pointerInput(Unit) { }
                    .clearAndSetSemantics { }
                    .focusProperties { onEnter = { cancelFocusChange() } }
                    .focusGroup()
            )
            .then(if (hidden) Modifier else Modifier.verticalScroll(scroll))
            // Paddings match the old ScrollView's inner FrameLayout (18 / 20 / 18 / 18).
            .padding(start = 18.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            key(entry.generation) { entry.content() }
        }
    }
}
