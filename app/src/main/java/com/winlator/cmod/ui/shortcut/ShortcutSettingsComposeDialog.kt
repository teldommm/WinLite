package com.winlator.cmod.ui.shortcut

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.theme.ApplySystemBarsPolicy
import com.winlator.cmod.ui.theme.WinZSystemBars
import com.winlator.cmod.ui.theme.WinZTheme
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object ShortcutSettingsComposeDialog {
    // onShortcutsChanged: called after an edit that changes what the Library shows (rename,
    // container switch) — the Library reloads, GameDetail asks MainActivity to.
    // onMoved: the shortcut moved to another container; gets the new .desktop path (the game page
    // reopens on it). Optional.
    @JvmStatic
    @JvmOverloads
    fun show(activity: AppCompatActivity, shortcut: Shortcut, onShortcutsChanged: Runnable, onMoved: ((String) -> Unit)? = null) {
        val dialog = ComponentDialog(activity)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            decorView.setPadding(0, 0, 0, 0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            // Portrait keeps the status bar visible, landscape is immersive; the composable
            // below keeps it that way across rotation.
            WinZSystemBars.apply(this, WinZSystemBars.isPortrait(activity.resources.configuration))
        }
        dialog.setContentView(ComposeView(activity).apply {
            setContent { WinZTheme { ApplySystemBarsPolicy(dialog.window); ShortcutEditorV2(activity, shortcut, { onShortcutsChanged.run() }, dialog::dismiss, onMoved) } }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
