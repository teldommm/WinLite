package com.winlator.cmod.ui

import com.winlator.cmod.ui.theme.WinZProgressBar
import com.winlator.cmod.ui.theme.TablerIcons
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.hairlineColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared destructive action UI; filesystem work stays off the UI thread. */
object ConfirmActionDialog {
    @JvmStatic fun show(fragment: Fragment, title: String, message: String, confirm: String, action: Runnable, success: Runnable) {
        val activity = fragment.requireActivity()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val tag = "confirm-destructive-action"
        if (root.findViewWithTag<android.view.View>(tag) != null) return
        val view = ComposeView(activity).apply { this.tag = tag; setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
        val close = { view.disposeComposition(); root.removeView(view) }
        view.setContent { WinZTheme {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            ThemedDialog(onDismissRequest = { if (!busy) close() }) {
                val local = androidx.compose.ui.platform.LocalView.current
                DisposableEffect(local) {
                    val window = (local.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                    window?.decorView?.systemUiVisibility = activity.window.decorView.systemUiVisibility
                    if (activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
                        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    onDispose { }
                }
                ThemedDialogTitle(title, leading = { Icon(TablerIcons.Trash, null, tint = destructiveColor()) })
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (busy) { Spacer(Modifier.height(10.dp)); WinZProgressBar(null) }
                if (error.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !busy, onClick = close,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        border = BorderStroke(1.dp, hairlineColor())
                    ) { Text(uiStr(R.string.steam_ui_cancel)) }
                    Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = Color.White), onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { action.run() } }
                            busy = false
                            result.onSuccess { close(); if (fragment.isAdded) success.run() }.onFailure { error = it.message.orEmpty() }
                        }
                    }) { Text(confirm) }
                }
            }
        } }
        root.addView(view)
    }
}
