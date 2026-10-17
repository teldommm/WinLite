package com.winlator.cmod.ui.shell

import com.winlator.cmod.container.Shortcut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.winlator.cmod.ui.container.ContainerEditorV2
import com.winlator.cmod.ui.library.GameDetailRoute
import com.winlator.cmod.ui.settings.ComponentManagerRoute
import com.winlator.cmod.ui.settings.ContainersSettingsRoute

// Detail screens — everything that used to be pushed as a Fragment onto FLFragmentContainer's
// back stack. They now live in MainShell's own stack, drawn above the tabs:
//  - entries below the top stay composed (like a fragment back stack keeping its entries), so
//    Containers keeps its scroll/state while the container editor is open on top of it;
//  - push/pop is a short horizontal shared-axis motion (slide 1/10 of the width + fade), the
//    standard "go deeper / come back" transition — replacing the full-height slide-up the
//    fragments used;
//  - system back pops the top entry (MainShell's BackHandler).
sealed class ShellDetail {
    // preloaded: the Shortcut the Library already has in memory for this game, so the page can
    // draw its first frame without reading every container's shortcuts from disk (it used to, on
    // the main thread, in composition - during its own entrance animation). Null = load it.
    data class GameDetail @JvmOverloads constructor(
        val shortcutPath: String,
        val preloaded: Shortcut? = null
    ) : ShellDetail()
    object Containers : ShellDetail()
    data class ContainerEditor(val editContainerId: Int?) : ShellDetail()
    object ComponentManager : ShellDetail()
}

internal class DetailEntry(val detail: ShellDetail, val id: Long) {
    // Starts hidden with target=visible, so AnimatedVisibility animates the entrance on its
    // first composition. Popping flips the target; the entry is removed once the exit settles.
    val transition = MutableTransitionState(false).apply { targetState = true }
    val active: Boolean get() = transition.targetState
}

private const val DETAIL_ENTER_MS = 220
private const val DETAIL_EXIT_MS = 160

@Composable
internal fun DetailLayer(controller: MainShellController) {
    val entries = controller.detailEntries
    val topActiveIndex = entries.indexOfLast { it.active }
    entries.forEachIndexed { index, entry ->
        key(entry.id) {
            val isTop = index == topActiveIndex
            AnimatedVisibility(
                visibleState = entry.transition,
                enter = fadeIn(tween(DETAIL_ENTER_MS, easing = LinearOutSlowInEasing)) +
                    slideInHorizontally(tween(DETAIL_ENTER_MS, easing = LinearOutSlowInEasing)) { it / 10 },
                exit = fadeOut(tween(DETAIL_EXIT_MS, easing = FastOutLinearInEasing)) +
                    slideOutHorizontally(tween(DETAIL_EXIT_MS, easing = FastOutLinearInEasing)) { it / 10 }
            ) {
                // Theme comes from MainShell's single WinZTheme root.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        // Being a pointer-input node makes this the hit target, so touches
                        // never fall through to the tab (or lower detail) underneath.
                        .pointerInput(Unit) { }
                        .then(
                            if (isTop) Modifier
                            else Modifier
                                .clearAndSetSemantics { }
                                .focusProperties { onEnter = { cancelFocusChange() } }
                                .focusGroup()
                        )
                ) {
                    DetailContent(controller, entry.detail, isTop)
                }
            }

            LaunchedEffect(entry.transition.currentState, entry.transition.isIdle) {
                if (!entry.transition.targetState && entry.transition.isIdle && !entry.transition.currentState) {
                    controller.detailEntries.remove(entry)
                }
            }
        }
    }
}

@Composable
private fun DetailContent(controller: MainShellController, detail: ShellDetail, isTop: Boolean) {
    val close: () -> Unit = { controller.popDetail() }
    when (detail) {
        is ShellDetail.GameDetail -> GameDetailRoute(
            shortcutPath = detail.shortcutPath,
            preloaded = detail.preloaded,
            onClose = close,
            onLibraryChanged = { controller.requestRefresh(com.winlator.cmod.R.id.main_menu_shortcuts) }
        )
        ShellDetail.Containers -> ContainersSettingsRoute(
            isTop = isTop,
            onBack = close,
            onOpenEditor = { editId -> controller.pushDetail(ShellDetail.ContainerEditor(editId)) }
        )
        is ShellDetail.ContainerEditor -> ContainerEditorV2(
            editId = detail.editContainerId,
            onBack = close,
            onCreated = close
        )
        ShellDetail.ComponentManager -> ComponentManagerRoute(onClose = close)
    }
}
