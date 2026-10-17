package com.winlator.cmod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import kotlin.math.roundToInt

// One entry in the controls-profile dropdown. Plain data holder so this file doesn't need
// to depend on com.winlator.cmod.inputcontrols.ControlsProfile — Java builds this list from it.
data class InputProfileOption(val id: Int, val name: String)

// Everything the panel needs to render one frame. Java rebuilds this fresh (from
// SharedPreferences / inputControlsView / the activity's own fields) and calls attach()
// again whenever the underlying state can change outside the panel itself (e.g. after
// returning from the profile editor) — same "just re-render" approach as the Screen panel.
data class InputPanelState(
    val profiles: List<InputProfileOption>,
    val selectedProfileId: Int, // -1 = disabled
    val showTouchscreenControls: Boolean,
    val touchscreenTimeout: Boolean,
    val touchscreenHaptics: Boolean,
    val controlsOpacityPercent: Int,
    val relativeMouse: Boolean,
    val touchMode: Int,          // TouchpadView.MODE_TRACKPAD (0) / MODE_TOUCHSCREEN (1) / MODE_OFF (2)
    val cursorSpeedPercent: Int, // global Cursor Speed, 10..200 (Settings > Cursor speed); also drives stick mouse
    val tapToClick: Boolean
)

interface InputPanelCallbacks {
    // Fired whenever the profile picker or either touchscreen switch changes — always carries
    // the full current snapshot, mirroring the old applySidebarInputControls(), which re-read
    // everything every time any one of them changed.
    fun onControlsSettingsChanged(
        profileId: Int,
        showTouchscreenControls: Boolean,
        touchscreenHaptics: Boolean
    )

    /** Global setting, saved immediately (like show controls / haptics). */
    fun onTouchscreenTimeout(enabled: Boolean)

    fun onEditProfiles(selectedProfileId: Int)
    /** commit = false while dragging (live preview only), true once on release (persist). */
    fun onControlsOpacity(percent: Int, commit: Boolean)
    fun onShowKeyboard()
    fun onVibration()
    /** TouchpadView.MODE_TRACKPAD / MODE_TOUCHSCREEN / MODE_OFF (touch surface off). */
    fun onTouchMode(mode: Int)
    /** commit = false while dragging (live), true once on release (persist). */
    fun onCursorSpeed(percent: Int, commit: Boolean)
    fun onTapToClick(enabled: Boolean)
    fun onRelativeMouse(enabled: Boolean)
}

object InputSidebarPanelHost {
    @JvmStatic
    // Called again whenever the underlying state changes outside the panel; each call
    // re-registers with a new generation, so the panel recomposes from scratch with the
    // new snapshot (same effect the old repeated setContent() had).
    fun attach(sidebar: IngameSidebarController, panelId: Int, state: InputPanelState, callbacks: InputPanelCallbacks) {
        sidebar.setPanel(panelId) { InputSidebarPanel(state, callbacks) }
    }
}

@Composable
private fun InputSidebarPanel(state: InputPanelState, callbacks: InputPanelCallbacks) {
    var profileId by remember { mutableStateOf(state.selectedProfileId) }
    var showControls by remember { mutableStateOf(state.showTouchscreenControls) }
    var haptics by remember { mutableStateOf(state.touchscreenHaptics) }
    var timeout by remember { mutableStateOf(state.touchscreenTimeout) }
    var relativeMouse by remember { mutableStateOf(state.relativeMouse) }
    var touchMode by remember { mutableStateOf(state.touchMode) }
    var tapToClick by remember { mutableStateOf(state.tapToClick) }

    fun pushControlsSettings() {
        callbacks.onControlsSettingsChanged(profileId, showControls, haptics)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle(uiStr(R.string.ui_controls))

        SidebarCard {
            ProfileRow(
                profiles = state.profiles,
                selectedProfileId = profileId,
                onSelect = { newId ->
                    profileId = newId
                    pushControlsSettings()
                },
                onEditClick = { callbacks.onEditProfiles(profileId) }
            )
            // Opacity belongs to the selected layout, so it only shows while one is selected.
            if (profileId != -1) {
                Spacer(Modifier.height(6.dp))
                var opacityDraft by remember {
                    mutableStateOf(state.controlsOpacityPercent.coerceIn(10, 100).toFloat())
                }
                SidebarSlider(
                    label = uiStr(R.string.ui_controls_opacity),
                    valueText = "${opacityDraft.roundToInt()}%",
                    value = opacityDraft,
                    onValueChange = {
                        opacityDraft = it
                        callbacks.onControlsOpacity(it.toInt(), false)
                    },
                    onValueChangeFinished = { callbacks.onControlsOpacity(opacityDraft.toInt(), true) },
                    valueRange = 10f..100f
                )
            }
            Spacer(Modifier.height(6.dp))
            SidebarInlineToggle(
                label = stringResource(R.string.show_touchscreen_controls),
                checked = showControls,
                onCheckedChange = {
                    showControls = it
                    pushControlsSettings()
                }
            )
            // Fades the controls out after 5 s idle; the next touch only brings them back.
            SidebarInlineToggle(
                label = stringResource(R.string.enable_touchscreen_timeout),
                checked = timeout,
                onCheckedChange = {
                    timeout = it
                    callbacks.onTouchscreenTimeout(it)
                }
            )
            SidebarInlineToggle(
                label = stringResource(R.string.enable_touchscreen_haptics),
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    pushControlsSettings()
                }
            )
        }

        // Touch Mode: how the finger on the free area drives the mouse — Trackpad, Touchscreen,
        // or Off (the touch surface does nothing; what used to be "Disable Mouse").
        // Cursor Speed stays in all three: Trackpad elements, mouse-move buttons, stick / D-pad /
        // gamepad mouse moves and a captured physical mouse follow it too, and none of those
        // depend on the touch surface.
        SidebarGap()
        SidebarCard {
            SidebarDropdownField(
                caption = uiStr(R.string.ui_touch_mode),
                options = listOf(uiStr(R.string.ui_trackpad), uiStr(R.string.ui_touchscreen), uiStr(R.string.ui_off)),
                selectedIndex = touchMode.coerceIn(0, 2),
                onSelect = { index ->
                    touchMode = index
                    callbacks.onTouchMode(index)
                }
            )
            Spacer(Modifier.height(6.dp))
            var cursorSpeedDraft by remember {
                mutableStateOf(state.cursorSpeedPercent.coerceIn(10, 200).toFloat())
            }
            SidebarSlider(
                label = uiStr(R.string.cursor_speed),
                valueText = "${cursorSpeedDraft.roundToInt()}%",
                value = cursorSpeedDraft,
                onValueChange = {
                    cursorSpeedDraft = it
                    callbacks.onCursorSpeed(it.roundToInt(), false)
                },
                onValueChangeFinished = { callbacks.onCursorSpeed(cursorSpeedDraft.roundToInt(), true) },
                valueRange = 10f..200f
            )
        }

        SidebarGap()
        SidebarActionRow(label = uiStr(R.string.ui_show_keyboard), onClick = callbacks::onShowKeyboard)
        SidebarGap()
        SidebarActionRow(label = uiStr(R.string.vibration), onClick = callbacks::onVibration)
        SidebarGap()
        SidebarToggleRow(
            label = uiStr(R.string.ui_relative_mouse),
            checked = relativeMouse,
            onCheckedChange = {
                relativeMouse = it
                callbacks.onRelativeMouse(it)
            }
        )
        // Tap to Click only concerns the touch surface (Trackpad: taps click; Touchscreen: touching
        // presses the button), so it is hidden while Touch Mode is Off. Its value is kept as is and
        // applies again when a mode is picked. Relative Mouse stays: it also drives Trackpad
        // elements, stick/button mouse and a physical mouse.
        if (touchMode != 2) {
            SidebarGap()
            SidebarToggleRow(
                label = uiStr(R.string.ui_tap_to_click),
                checked = tapToClick,
                onCheckedChange = {
                    tapToClick = it
                    callbacks.onTapToClick(it)
                }
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profiles: List<InputProfileOption>,
    selectedProfileId: Int,
    onSelect: (Int) -> Unit,
    onEditClick: () -> Unit
) {
    val disabledLabel = "-- ${stringResource(R.string.disabled)} --"
    // Index 0 is always "Disabled" (id -1); profiles follow in order. An id that no longer
    // exists (profile deleted in the editor) falls back to Disabled, as before.
    val options = listOf(disabledLabel) + profiles.map { it.name }
    val selectedIndex = if (selectedProfileId < 0) 0
        else profiles.indexOfFirst { it.id == selectedProfileId }.let { if (it < 0) 0 else it + 1 }

    SidebarCaption(uiStr(R.string.ui_controls_profile))
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SidebarDropdownField(
            caption = null,
            options = options,
            selectedIndex = selectedIndex,
            onSelect = { index -> onSelect(if (index == 0) -1 else profiles[index - 1].id) },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onEditClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = uiStr(R.string.ui_edit_profiles),
                tint = controlAccentColor()
            )
        }
    }
}
