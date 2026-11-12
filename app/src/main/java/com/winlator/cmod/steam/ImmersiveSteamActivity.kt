package com.winlator.cmod.steam

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.DialogSystemBarsPolicy

/**
 * Base of the Steam screens. They follow WinLite's own system-bar policy (WinZSystemBars): portrait keeps the
 * status bar visible, landscape is fully immersive. The legacy FLAG_FULLSCREEN / systemUiVisibility flags this
 * class used to force on every resume and focus change fought that policy: the status bar was hidden in
 * portrait and every sheet or dialog slid it back in, shoving the layout.
 */
open class ImmersiveSteamActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        applyAppFullscreen(this)
    }
    override fun onResume() { super.onResume(); applyAppFullscreen(this) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyAppFullscreen(this)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAppFullscreen(this)
    }
}

/** Puts a sheet / dialog window of a Steam screen on the same system-bar policy as the activity. */
@androidx.compose.runtime.Composable
internal fun SteamDialogFullscreen() = DialogSystemBarsPolicy()
