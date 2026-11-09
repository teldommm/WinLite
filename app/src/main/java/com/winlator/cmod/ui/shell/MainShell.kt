package com.winlator.cmod.ui.shell

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winlator.cmod.R
import com.winlator.cmod.ui.PortraitBottomNavigation
import com.winlator.cmod.ui.ShellChrome
import com.winlator.cmod.ui.filemanager.FileManagerRoute
import com.winlator.cmod.ui.inputcontrols.InputControlsRoute
import com.winlator.cmod.ui.library.LibraryRoute
import com.winlator.cmod.ui.settings.SettingsRoute
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.winlator.cmod.ui.settings.loadWineRuntimeOptions
import androidx.compose.ui.platform.LocalContext

// MainShell — the single Compose root of MainActivity's main screens.
//
// Before: MainActivity.show() did FragmentTransaction.replace() into FLFragmentContainer with a
// full-height slide-up/slide-down, so every tab switch destroyed the previous screen (lost
// scroll, re-ran its disk loading on the main thread mid-animation) and moved the whole screen
// vertically, which reads as "a modal opened", not "switched tab". The portrait nav lived in
// its own ComposeView inside a native FrameLayout, and chrome visibility was toggled by hand
// from ~10 call sites.
//
// Now:
//  - Every tab (Library, Input Controls, Settings, File Manager) is a plain composable route,
//    composed on first visit and then kept alive. Switching is a fade-through driven here.
//    No tab is a Fragment anymore.
//  - The portrait bottom nav is part of this composition; its visibility is derived
//    (portrait && no detail screen open) instead of being set by each screen.
//  - Detail screens (GameDetail, Containers, container editor, Components) are a stack of
//    composables drawn above the tabs (ShellDetails.kt). There are no Fragments left in
//    MainActivity.
//  - System back is handled here (BackHandler): pop a detail, else return to Library, else
//    fall through to the activity (finish, or return to the game in edit-controls mode).
//  - A tab that needs to refresh when it's shown again reacts to shownSerial (see TabContent).

// SAM interface so MainActivity.java can pass this::navigateToMainDestination.
fun interface MainShellListener {
    fun onNavigate(menuItemId: Int)
}

class MainShellController internal constructor(
    initialTab: Int,
    internal val inputControlsProfileId: Int,
    // Opened from a game to edit its controls: back from Input Controls must leave the
    // activity (return to the game), not switch to Library.
    internal val editControlsMode: Boolean
) {
    var selectedTab by mutableIntStateOf(initialTab)
        private set

    internal val detailEntries = mutableStateListOf<DetailEntry>()
    private var nextDetailId = 0L

    // True while any detail screen is open (not counting ones already animating out).
    val detailActive: Boolean
        get() = detailEntries.any { it.active }

    internal val visitedTabs = mutableStateListOf(initialTab)

    // Tabs that have been shown at least once. The first showing is covered by the route's own
    // initial load, so shownSerial is only bumped on later returns.
    internal val shownOnce = HashSet<Int>()

    // Bumped each time a tab is re-shown (after its fade-in) or explicitly asked to refresh;
    // the tab's route reacts with LaunchedEffect(serial).
    internal val shownSerial = mutableStateMapOf<Int, Int>()

    fun pushDetail(detail: ShellDetail) {
        detailEntries.add(DetailEntry(detail, nextDetailId++))
    }

    // Pops the top open detail; false if none is open.
    fun popDetail(): Boolean {
        val top = detailEntries.lastOrNull { it.active } ?: return false
        top.transition.targetState = false
        return true
    }

    fun closeAllDetails() {
        detailEntries.forEach { it.transition.targetState = false }
    }

    internal val backEnabled: Boolean
        get() = detailActive || (!editControlsMode && selectedTab != R.id.main_menu_shortcuts)

    internal fun onBack() {
        if (popDetail()) return
        select(R.id.main_menu_shortcuts)
    }

    // Ask a tab to reload now, e.g. the Library after a detail screen edited a shortcut.
    fun requestRefresh(tabId: Int) {
        if (tabId in visitedTabs) shownSerial[tabId] = (shownSerial[tabId] ?: 0) + 1
    }

    fun select(tabId: Int) {
        if (!isTab(tabId)) return
        if (tabId !in visitedTabs) visitedTabs.add(tabId)
        selectedTab = tabId
    }

    companion object {
        @JvmStatic
        fun isTab(menuItemId: Int): Boolean =
            menuItemId == R.id.main_menu_shortcuts ||
                menuItemId == R.id.main_menu_input_controls ||
                menuItemId == R.id.main_menu_settings ||
                menuItemId == R.id.main_menu_file_manager
    }
}

object MainShellHost {
    @JvmStatic
    fun attach(
        composeView: ComposeView,
        initialTab: Int,
        inputControlsProfileId: Int,
        editControlsMode: Boolean,
        listener: MainShellListener
    ): MainShellController {
        val controller = MainShellController(
            if (MainShellController.isTab(initialTab)) initialTab else R.id.main_menu_shortcuts,
            inputControlsProfileId,
            editControlsMode
        )
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        // The one theme root for everything MainActivity shows. WinZTheme also sets up the window
        // (system bars, background, focus handling of this host view, tap-to-dismiss-keyboard);
        // it used to be applied again by every tab route and every detail entry — five or six
        // times over the same host view — so it lives here once instead.
        composeView.setContent { WinZTheme { MainShell(controller, listener) } }
        return controller
    }
}

// Tab switch ("rise"): the incoming tab starts a little below its place, fades in and quickly
// glides up into position over the outgoing tab, which stays where it is (still visible under
// the newcomer for a moment) and fades out only once the new one has mostly covered it. Same
// feel as switching Home / Shorts / Subscriptions in the YouTube app. The direction doesn't
// depend on which tab you come from. Only graphicsLayer alpha/translation are animated — no
// recomposition or relayout of the heavy tab content. The shell's own chrome (nav) stays still.
private const val EXIT_MS = 90
private const val ENTER_MS = 210

// How far below its place the new tab starts, and how long the rise / fades take.
private val TAB_RISE_DISTANCE = 48.dp
private const val TAB_RISE_MS = 230
private const val TAB_ALPHA_IN_MS = 180
private const val TAB_ALPHA_OUT_MS = 140
// The outgoing tab holds full opacity this long so the newcomer has covered it before it fades.
private const val TAB_ALPHA_OUT_DELAY_MS = 80

@Composable
private fun MainShell(controller: MainShellController, listener: MainShellListener) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler(enabled = controller.backEnabled) { controller.onBack() }

    // Warm the Wine/Proton runtime list in the background so container/shortcut settings open
    // with their runtime labels already resolved (see cachedWineRuntimeOptions).
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        runCatching { loadWineRuntimeOptions(appContext) }
    }

    LaunchedEffect(controller.selectedTab) {
        val tab = controller.selectedTab
        if (controller.shownOnce.add(tab)) return@LaunchedEffect
        delay(WinZMotion.DurationTabSlide.toLong())
        controller.shownSerial[tab] = (controller.shownSerial[tab] ?: 0) + 1
    }

    Box(Modifier.fillMaxSize()) {
        controller.visitedTabs.forEach { tab ->
            key(tab) { ShellTab(controller, tab) }
        }

        AnimatedVisibility(
            visible = !landscape && !controller.detailActive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it / 2 },
            exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { it / 2 }
        ) {
            PortraitBottomNavigation(
                selected = controller.selectedTab,
                onNavigate = { listener.onNavigate(it) }
            )
        }

        // Landscape: the nav group and the status card (clock / network / battery — the system
        // bars are hidden in landscape) are one static row in the header band; screens reserve
        // its space (ShellChrome.LandscapeNavReserve) and draw only their title and actions.
        AnimatedVisibility(
            visible = landscape && !controller.detailActive,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2f)
                .padding(top = 2.dp, end = ShellChrome.LandscapeChromeEndMargin),
            enter = fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS))
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ShellChrome.LandscapeChromeGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LandscapeNavGroup(
                    selected = controller.selectedTab,
                    onNavigate = { listener.onNavigate(it) }
                )
                LandscapeStatusCluster()
            }
        }

        Box(Modifier.fillMaxSize().zIndex(3f)) {
            DetailLayer(controller)
        }
    }
}

@Composable
private fun ShellTab(controller: MainShellController, tab: Int) {
    val selected = controller.selectedTab == tab

    // Only the very first tab of the session starts fully visible. Every tab composed later is
    // composed *because* it was just selected, and must rise in like any other switch — which
    // animateFloatAsState can't do (it starts at its first target), hence Animatable.
    val initiallyShown = remember { selected && controller.visitedTabs.size == 1 }
    val alpha = remember { Animatable(if (initiallyShown) 1f else 0f) }
    // Vertical offset as a fraction of the rise distance: 0 = in place, 1 = fully below.
    val offset = remember { Animatable(if (initiallyShown) 0f else 1f) }

    LaunchedEffect(selected) {
        if (selected) {
            // Rising from below. A tab that is still partly visible (switched back mid-exit)
            // continues from where it is instead of jumping.
            if (alpha.value == 0f) offset.snapTo(1f)
            launch { alpha.animateTo(1f, tween(TAB_ALPHA_IN_MS, easing = LinearOutSlowInEasing)) }
            offset.animateTo(0f, tween(TAB_RISE_MS, easing = FastOutSlowInEasing))
        } else {
            // Stays put under the newcomer and fades out once it has mostly covered it.
            alpha.animateTo(
                0f,
                tween(TAB_ALPHA_OUT_MS, delayMillis = TAB_ALPHA_OUT_DELAY_MS, easing = FastOutLinearInEasing)
            )
        }
    }

    // Out of reach while faded out, or while a detail screen covers the tabs.
    val hidden by remember {
        derivedStateOf { (controller.selectedTab != tab && alpha.value == 0f) || controller.detailActive }
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (selected) 1f else 0f)
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = offset.value * TAB_RISE_DISTANCE.toPx()
            }
            // A fully faded-out tab can't take focus (D-pad/gamepad traversal would otherwise
            // walk into an invisible screen) and exposes no accessibility nodes.
            .then(
                if (hidden) {
                    Modifier
                        .clearAndSetSemantics { }
                        .focusProperties { onEnter = { cancelFocusChange() } }
                        .focusGroup()
                } else {
                    Modifier
                }
            )
    ) {
        TabContent(controller, tab)

        // Tabs stay composed after their first visit and are stacked in one Box; graphicsLayer
        // alpha only hides them visually, it doesn't stop hit-testing. A pointer touch that
        // lands where the selected tab has no pointer-input node (empty space) would fall
        // through to the next sibling below — e.g. the File Manager's rows, opening its
        // file menu / drive picker over Library. Being a pointer-input node makes this overlay
        // the hit target, so an unselected tab never receives touches (same trick as
        // DetailLayer). Not tied to alpha: the outgoing tab must stop at once, not after
        // its fade-out.
        if (!selected) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { })
        }
    }
}

@Composable
private fun TabContent(controller: MainShellController, tab: Int) {
    val serial = controller.shownSerial[tab] ?: 0
    when (tab) {
        R.id.main_menu_shortcuts -> LibraryRoute(
            shownSerial = serial,
            active = controller.selectedTab == tab,
            detailOpen = controller.detailActive
        )
        R.id.main_menu_input_controls -> InputControlsRoute(
            initialProfileId = controller.inputControlsProfileId,
            shownSerial = serial
        )
        R.id.main_menu_settings -> SettingsRoute(shownSerial = serial)
        R.id.main_menu_file_manager -> FileManagerRoute(shownSerial = serial)
    }
}
