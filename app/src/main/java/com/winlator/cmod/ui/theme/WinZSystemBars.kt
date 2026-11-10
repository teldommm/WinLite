package com.winlator.cmod.ui.theme

import android.content.res.Configuration
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
        // The fullscreen themes (onboarding / setup) also set windowTranslucentStatus/Navigation.
        // While those flags are on, the system draws its own dark gradient behind the clock,
        // Wi-Fi and battery and ignores statusBarColor, so the area looked different from the
        // WinLite theme. Clear them and let the bars paint the theme colour (setLegacySystemBarColors).
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
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

// ModalBottomSheet (material3) and Dialog live in their own window, which does not inherit the
// activity's immersive state: opening a sheet in landscape made the system status bar (clock,
// battery, Wi-Fi) slide back in and shove the layout. Call this first thing inside the sheet /
// dialog content to put that window on the same policy as the activity. The window provider is
// either the hosting view itself (material3 sheet) or its parent (Compose Dialog).
@Composable
fun DialogSystemBarsPolicy() {
    val view = LocalView.current
    val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
    ApplySystemBarsPolicy(window)
}
