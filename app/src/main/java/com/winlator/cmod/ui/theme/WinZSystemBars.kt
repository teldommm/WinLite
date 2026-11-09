package com.winlator.cmod.ui.theme

import android.content.res.Configuration
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// System bars policy of the app's screens (everything except the running game):
//  - portrait:  the status bar (clock, battery, Wi-Fi, ...) is always visible; the navigation bar
//               stays hidden and comes back with an edge swipe;
//  - landscape: both bars hidden (immersive), swipe from the edge brings them back transiently.
// The window is always edge-to-edge; WinlatorTheme pads its content by WindowInsets.statusBars,
// which is 0 whenever the bar is hidden, so the same layout works in both cases.
object WinZSystemBars {
    @JvmStatic
    fun isPortrait(configuration: Configuration): Boolean =
        configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    @JvmStatic
    fun apply(window: Window, portrait: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Some screens use a theme with windowFullscreen=true (e.g. onboarding); that flag keeps
        // the status bar hidden no matter what the insets controller asks for.
        if (portrait) window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            if (portrait) show(WindowInsetsCompat.Type.statusBars())
            else hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}

// Keeps [window] on the policy above while the composition lives, re-applying on rotation
// (the activities handle orientation changes themselves, they are not recreated).
@Composable
fun ApplySystemBarsPolicy(window: Window?) {
    val portrait = WinZSystemBars.isPortrait(LocalConfiguration.current)
    DisposableEffect(window, portrait) {
        if (window != null) WinZSystemBars.apply(window, portrait)
        onDispose { }
    }
}
