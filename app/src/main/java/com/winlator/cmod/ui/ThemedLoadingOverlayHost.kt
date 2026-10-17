package com.winlator.cmod.ui

import com.winlator.cmod.ui.theme.DialogScrimColor
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// Themed replacement for PreloaderDialog's "standard preloader" mode — an indeterminate
// spinner + message card, matching ThemedDownloadProgressHost's card (the reinstall-imagefs/
// download progress overlay) exactly: same 60dp circular indicator size/stroke, same 0.5f
// scrim, same ThemedDialogSurface + Row layout, just indeterminate since there's no percent
// to show for these (a shutdown, or a "loading"/"downloading" wait with no progress feed).
//
// Shared by XServerDisplayActivity.exit() (R.string.shutdown) and InputControlsFragment's
// profile-list download flow (R.string.loading while fetching index.txt, then
// R.string.downloading_file while pulling the selected profiles) — same visual, so one host
// rather than a near-duplicate object per caller.
object ThemedLoadingOverlayHost {
    @JvmStatic
    fun show(activity: Activity, message: String): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZOverlayTheme { LoadingOverlayContent(message) } }
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

// The card itself, shared with PreloaderOverlayHost's "standard" mode so both look identical.
@Composable
internal fun LoadingOverlayContent(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrimColor),
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(60.dp),
                    color = controlAccentColor(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 5.dp
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
