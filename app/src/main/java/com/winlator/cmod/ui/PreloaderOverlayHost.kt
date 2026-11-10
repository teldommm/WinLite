package com.winlator.cmod.ui

import com.winlator.cmod.ui.theme.OnArtwork
import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.ui.library.LocalArtworkMotion
import com.winlator.cmod.ui.library.kenBurns
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer

// What PreloaderOverlayHost draws. Owned by core/PreloaderDialog (Java), which resolves the
// artwork/title and keeps this up to date; Compose just observes it, so late changes (the
// TheGamesDB banner arriving after the screen is already up) show without re-adding the view.
class PreloaderOverlayState {
    // false = "standard" card (spinner + message), true = game launch screen.
    var launchMode by mutableStateOf(false)
    // Standard mode: the message. Launch mode: the status line under the title.
    var message by mutableStateOf("")
    var title by mutableStateOf("")
    var artwork by mutableStateOf<Bitmap?>(null)
    // "Animated artwork" setting, read when the screen is shown.
    var motion by mutableStateOf(false)

    // Enter / exit of the whole overlay (see PreloaderOverlayHost.dismiss). While it fades out it
    // no longer blocks touches or the back key, so the game underneath is usable at once.
    internal val visible = MutableTransitionState(false).apply { targetState = true }
    internal var exiting by mutableStateOf(false)
}

// Compose replacement for the old android.app.Dialog + preloader_dialog.xml. Added to the
// activity's content root like the other Themed*Host overlays instead of living in a window of
// its own: touches and the back key are swallowed so it stays modal, as the dialog was
// (setCancelable(false)).
object PreloaderOverlayHost {
    @JvmStatic
    fun show(activity: Activity, state: PreloaderOverlayState): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZOverlayTheme { PreloaderOverlayContent(state) } }
            tag = state
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return composeView
    }

    @JvmStatic
    fun dismiss(view: View?) {
        if (view == null) return
        val state = view.tag as? PreloaderOverlayState
        if (state == null || view.parent == null) {
            (view.parent as? ViewGroup)?.removeView(view)
            return
        }
        // Fade out first: removing it in one frame flashed the game's first frame in.
        state.exiting = true
        state.visible.targetState = false
        view.postDelayed({ (view.parent as? ViewGroup)?.removeView(view) }, EXIT_MILLIS)
    }

    private const val EXIT_MILLIS = 260L
}

@Composable
private fun PreloaderOverlayContent(state: PreloaderOverlayState) {
    // The dialog was not cancelable: back must not reach the activity while it is up.
    BackHandler(enabled = !state.exiting) { }
    AnimatedVisibility(
        state.visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(280)),
        exit = fadeOut(tween(240))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Modal: a pointer-input node is the hit target, so nothing below receives touches
                // (until it starts to fade out).
                .then(if (state.exiting) Modifier else Modifier.pointerInput(Unit) { })
        ) {
            if (state.launchMode) LaunchScreen(state) else LoadingOverlayContent(state.message)
        }
    }
}

@Composable
private fun LaunchScreen(state: PreloaderOverlayState) {
    val artwork = state.artwork
    val accent = controlAccentColor()
    val overArtwork = artwork != null
    // The text colours and the scrim follow the artwork with a short ease, so a banner that
    // arrives after the screen is up (TheGamesDB) does not flip them in one frame.
    val titleColor by animateColorAsState(
        if (overArtwork) OnArtwork.Text else MaterialTheme.colorScheme.onBackground, tween(320), label = "launchTitle"
    )
    val statusColor by animateColorAsState(
        if (overArtwork) OnArtwork.LaunchStatus else MaterialTheme.colorScheme.onSurfaceVariant, tween(320), label = "launchStatus"
    )
    val scrimAlpha by animateFloatAsState(if (overArtwork) 1f else 0f, tween(400), label = "launchScrim")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Cross-fades when the picture changes or shows up late.
        Crossfade(targetState = artwork, animationSpec = tween(420), label = "launchArt") { shown ->
            if (shown != null) {
                val image = remember(shown) { shown.asImageBitmap() }
                // kenBurns scales the layer past its bounds, so the parent has to clip.
                CompositionLocalProvider(LocalArtworkMotion provides state.motion) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().kenBurns(amplitude = 0.10f, periodMs = 26_000, pan = 0.8f),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        // Same stops as the old loading_art_scrim drawable (top to bottom).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Brush.verticalGradient(OnArtwork.LaunchScrim))
        )

        // Title and status rise in a beat after the picture.
        val textShown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            textShown,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            enter = fadeIn(tween(320, delayMillis = 120)) + slideInVertically(tween(380, delayMillis = 120)) { it / 3 }
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 22.dp)) {
                Text(
                    state.title,
                    color = titleColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(7.dp))
                // Status changes cross-fade instead of swapping.
                AnimatedContent(
                    targetState = state.message,
                    transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
                    label = "launchMessage"
                ) { message ->
                    Text(message, color = statusColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        LinearProgressIndicator(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            color = accent,
            trackColor = accent.copy(alpha = 0x35 / 255f)
        )
    }
}
