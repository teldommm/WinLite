package com.winlator.cmod.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZSystemBars
import com.winlator.cmod.ui.theme.setLegacySystemBarColors

fun applyAppFullscreen(activity: Activity?) {
    if (activity == null) return

    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setLegacySystemBarColors(android.graphics.Color.TRANSPARENT)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // Portrait keeps the status bar visible, landscape is fully immersive.
    WinZSystemBars.apply(window, WinZSystemBars.isPortrait(activity.resources.configuration))
}

// Re-applies the app's fullscreen/immersive window setup while a landscape screen is shown
// (the system can bring the bars back, e.g. after a dialog). This used to also force the native
// Toolbar and bottom nav hidden on every frame; neither exists as a View anymore — MainShell
// owns all chrome — so only the fullscreen part remains.
@Composable
fun KeepLandscapeChromeHidden(activity: MainActivity?) {
    LaunchedEffect(activity) {
        applyAppFullscreen(activity)
        withFrameNanos { }
        applyAppFullscreen(activity)
    }
}

// Shared geometry between MainShell's floating landscape chrome and the screens' own landscape
// headers. The shell draws the destinations (Library / Input Controls / Settings) as one static
// group pinned top-end, centred in the header band, with the status card (clock / network /
// battery) to its right; each screen draws only its title and its own actions, and keeps
// LandscapeNavReserve free at the end so they never sit under either of them.
object ShellChrome {
    val LandscapeHeaderHeight = 54.dp

    // Nav group: 3 × 44dp cells + 2 × 2dp spacing + 2 × 3dp padding = 142dp wide, 44 + 2 × 3 = 50dp
    // tall. The status card uses the same height. Keep these in sync with LandscapeNavGroup.
    val LandscapeNavWidth = 142.dp
    val LandscapeNavHeight = 50.dp

    // Gap between the nav group and the status card, and the margin from the screen's end edge.
    val LandscapeChromeGap = 8.dp
    val LandscapeChromeEndMargin = 14.dp

    // Below this width (dp) the status card drops the battery percentage and the charging bolt.
    private const val COMPACT_LANDSCAPE_WIDTH_DP = 700

    @Composable
    fun isCompactLandscape(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_LANDSCAPE_WIDTH_DP

    // Fixed per mode: the card's content (time, icons, percentage) never changes its width.
    fun landscapeStatusWidth(compact: Boolean): Dp = if (compact) 116.dp else 160.dp

    // Everything the shell pins top-end, plus an 8dp clearance, measured from the screen edge:
    // nav group + gap + status card + end margin + clearance. Composable because the status card's
    // width depends on the screen width; every caller already sits in a composable body.
    val LandscapeNavReserve: Dp
        @Composable get() = LandscapeNavWidth +
            LandscapeChromeGap +
            landscapeStatusWidth(isCompactLandscape()) +
            LandscapeChromeEndMargin +
            8.dp

    // Portrait: the bottom nav floats over tab content (64dp bar + 12dp bottom margin); anything
    // anchored to the bottom of a tab (FABs, last list rows) must clear it, plus a 12dp gap.
    val PortraitNavClearance = 88.dp

    // Portrait main header band (title row / search row share it, so opening search doesn't
    // push the content below).
    val PortraitHeaderHeight = 60.dp
}

// Landscape header for a main tab: title + screen actions. Navigation is not part of it anymore —
// MainShell draws it once, so it no longer fades out and back in with every tab switch.
//  startPadding / containerEndPadding: for callers that already sit inside horizontally padded
//  containers (Library's list and pager), so the reserve is measured from the screen edge.
@Composable
fun LandscapeScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
    startPadding: Dp = 20.dp,
    containerEndPadding: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {}
) {
    KeepLandscapeChromeHidden(LocalContext.current as? MainActivity)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ShellChrome.LandscapeHeaderHeight)
            .padding(
                start = startPadding,
                end = (ShellChrome.LandscapeNavReserve - containerEndPadding).coerceAtLeast(0.dp)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = if (onArtwork) Color.White else MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        actions()
    }
}

// Portrait counterpart to LandscapeScreenHeader: in portrait the bottom navigation is drawn
// by MainShell, so the screen only needs its title.
@Composable
fun PortraitMainHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
