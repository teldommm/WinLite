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
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

@Composable
private fun PreloaderOverlayContent(state: PreloaderOverlayState) {
    // The dialog was not cancelable: back must not reach the activity while it is up.
    BackHandler(enabled = true) { }
    Box(
        Modifier
            .fillMaxSize()
            // Modal: a pointer-input node is the hit target, so nothing below receives touches.
            .pointerInput(Unit) { }
    ) {
        if (state.launchMode) LaunchScreen(state) else LoadingOverlayContent(state.message)
    }
}

@Composable
private fun LaunchScreen(state: PreloaderOverlayState) {
    val artwork = state.artwork
    val accent = controlAccentColor()
    val overArtwork = artwork != null
    val titleColor = if (overArtwork) OnArtwork.Text else MaterialTheme.colorScheme.onBackground
    val statusColor = if (overArtwork) OnArtwork.LaunchStatus else MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (artwork != null) {
            val image = remember(artwork) { artwork.asImageBitmap() }
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
            // Same stops as the old loading_art_scrim drawable (top to bottom).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(OnArtwork.LaunchScrim))
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 22.dp)
        ) {
            Text(
                state.title,
                color = titleColor,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(state.message, color = statusColor, style = MaterialTheme.typography.bodyMedium)
        }

        LinearProgressIndicator(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            color = accent,
            trackColor = accent.copy(alpha = 0x35 / 255f)
        )
    }
}
