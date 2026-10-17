package com.winlator.cmod.ui

import com.winlator.cmod.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Java-friendly callback surface (plain interface, not a Kotlin function type) so
// XServerDisplayActivity.java can implement it with a normal anonymous class, the same
// way it already wires click listeners for every other sidebar item.
interface ScreenPanelCallbacks {
    fun onPipMode()
    fun onToggleFullscreen()
    fun onMagnifier()

    // enabled = the NEW state after the tap; Compose owns the toggle's on/off state,
    // this callback is purely for applying the resulting side effect on the renderer.
    fun onSoftStretch(enabled: Boolean)
}

// Registers the panel's content with the single sidebar composition (IngameSidebar.kt)
// under panelId (R.id.LLSubScreen). Called once from wireSidebarListeners().
object ScreenSidebarPanelHost {
    @JvmStatic
    fun attach(sidebar: IngameSidebarController, panelId: Int, callbacks: ScreenPanelCallbacks) {
        sidebar.setPanel(panelId) { ScreenSidebarPanel(callbacks) }
    }
}

@Composable
private fun ScreenSidebarPanel(callbacks: ScreenPanelCallbacks) {
    // Soft Stretch has no persisted state anywhere else in the app (the old Java field
    // wasn't read by anything but this same click), so Compose owns it outright.
    var softStretchEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle(uiStr(R.string.ui_display_and_effects))
        SidebarActionRow(label = uiStr(R.string.ui_picture_in_picture), onClick = callbacks::onPipMode)
        SidebarGap()
        SidebarActionRow(label = uiStr(R.string.ui_toggle_fullscreen), onClick = callbacks::onToggleFullscreen)
        SidebarGap()
        SidebarActionRow(label = uiStr(R.string.ui_magnifier), onClick = callbacks::onMagnifier)
        SidebarGap()
        // Was a bordered row with an "ON" label — the only on/off control in the sidebar
        // that wasn't a Switch. Now uses the same Switch as every other toggle.
        SidebarToggleRow(
            label = uiStr(R.string.ui_soft_stretch),
            checked = softStretchEnabled,
            onCheckedChange = {
                softStretchEnabled = it
                callbacks.onSoftStretch(it)
            }
        )
    }
}
