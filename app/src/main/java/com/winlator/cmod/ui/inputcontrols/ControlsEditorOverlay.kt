package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.ui.theme.WinZMotion
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ripple
import com.winlator.cmod.ui.SidebarChoiceItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.ui.SidebarMenu
import com.winlator.cmod.ui.sidebarPanelColor
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.widget.InputControlsView
import kotlin.math.hypot
import kotlin.math.max
import com.winlator.cmod.ui.theme.hairlineColor

// Toolbar actions the Activity handles (all of them touch the profile / InputControlsView).
interface ControlsEditorActions {
    fun onAddElement()
    fun onRemoveElement()
    fun onDuplicateElement()
    fun onUndo()
    // The Activity builds the settings model for the selected element and calls showSettings().
    fun onOpenSettings()
    fun onSchemeColorSelected(color: Int)
    // Switch the editor to another profile (chosen from the toolbar's profile menu).
    fun onSelectProfile(profileId: Int)
}

// Floating chrome of the controls editor: a draggable toolbar and the element-settings panel
// attached to it. Replaces ControlsEditorToolbarComposeHost (fixed, hardcoded colors, PNG
// icons) and the old centered, scrimmed settings dialog.
//
// Two separate wrap-content ComposeViews over the canvas, so every touch outside them still
// reaches InputControlsView — the panel has no scrim: tapping another element on the canvas
// re-targets the open panel instead of closing it.
//
// Panel placement: directly below the toolbar (or above it, whichever side has more room),
// centered on it, and — if that would cover the selected element — pushed to the opposite
// horizontal half of the screen. It follows the toolbar while it is dragged.
class ControlsEditorOverlay(
    private val container: FrameLayout,
    private val canvas: InputControlsView,
    private val profileIds: List<Int>,
    private val profileNames: List<String>,
    currentProfileId: Int,
    private val schemeColors: List<Int>,
    schemeColor: Int,
    private val actions: ControlsEditorActions,
    private val settingsCallbacks: ControlElementSettingsCallbacks
) {
    private val density = container.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(container.context).scaledTouchSlop

    private val currentProfileIdState = mutableIntStateOf(currentProfileId)
    private val profileMenuOpen = mutableStateOf(false)
    private val hasSelection = mutableStateOf(false)
    private val undoAvailable = mutableStateOf(false)
    private val schemeColorState = mutableIntStateOf(schemeColor)
    private val panelOpen = mutableStateOf(false)
    // Last model shown, kept while the panel animates out.
    private val panelModel = mutableStateOf<ControlElementSettingsModel?>(null)
    private val panelMaxHeightPx = mutableIntStateOf(Int.MAX_VALUE)

    private val toolbarView: ComposeView
    private val panelView: ComposeView

    // Toolbar drag state (raw screen coordinates: the view moves under the finger).
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var toolbarDragging = false

    // Separate ObjectAnimators for the dim: ViewPropertyAnimator.cancel() (used by the panel's
    // position glide) would otherwise cancel an alpha fade running on the same view.
    private var toolbarDimAnimator: ObjectAnimator? = null
    private var panelDimAnimator: ObjectAnimator? = null

    init {
        val context = container.context
        toolbarView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(8f).toInt() }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val currentId = currentProfileIdState.intValue
                    val currentIndex = profileIds.indexOf(currentId)
                    EditorToolbar(
                        profileName = profileNames.getOrElse(currentIndex) { "" },
                        profileNames = profileNames,
                        currentProfileIndex = currentIndex,
                        profileMenuOpen = profileMenuOpen.value,
                        onProfileMenuDismiss = { profileMenuOpen.value = false },
                        onProfileSelected = { index ->
                            profileMenuOpen.value = false
                            val id = profileIds.getOrNull(index)
                            if (id != null && id != currentProfileIdState.intValue) actions.onSelectProfile(id)
                        },
                        hasSelection = hasSelection.value,
                        undoAvailable = undoAvailable.value,
                        settingsOpen = panelOpen.value,
                        schemeColors = schemeColors,
                        schemeColor = schemeColorState.intValue,
                        onDragEvent = this@ControlsEditorOverlay::handleToolbarDrag,
                        onAdd = actions::onAddElement,
                        onRemove = actions::onRemoveElement,
                        onDuplicate = actions::onDuplicateElement,
                        onUndo = actions::onUndo,
                        onSettings = { if (panelOpen.value) closeSettings() else actions.onOpenSettings() },
                        onSchemeColor = { color ->
                            schemeColorState.intValue = color
                            actions.onSchemeColorSelected(color)
                        }
                    )
                }
            }
        }

        panelView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val maxHeight = with(LocalDensity.current) { panelMaxHeightPx.intValue.toDp() }
                    AnimatedVisibility(
                        visible = panelOpen.value,
                        enter = WinZMotion.dialogCardEnter(initialScale = 0.96f),
                        exit = WinZMotion.dialogCardExit(targetScale = 0.96f)
                    ) {
                        val model = panelModel.value
                        if (model != null) {
                            FloatingSurface {
                                Column(
                                    Modifier
                                        .width(340.dp)
                                        .heightIn(max = maxHeight)
                                        .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)
                                ) {
                                    ControlElementSettingsPanelContent(model, settingsCallbacks, onClose = this@ControlsEditorOverlay::closeSettings)
                                }
                            }
                        }
                    }
                }
            }
        }

        container.addView(toolbarView)
        container.addView(panelView)

        toolbarView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionPanel(animate = false) }
        panelView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionPanel(animate = false) }
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampToolbar()
            positionPanel(animate = false)
        }
    }

    // ---------- Java-facing API ----------

    // After the Activity has switched profiles.
    fun setProfile(profileId: Int, schemeColor: Int) {
        currentProfileIdState.intValue = profileId
        schemeColorState.intValue = schemeColor
        hasSelection.value = false
        undoAvailable.value = false
        panelOpen.value = false
    }

    fun setHasSelection(value: Boolean) {
        hasSelection.value = value
    }

    fun setUndoAvailable(value: Boolean) {
        undoAvailable.value = value
    }

    fun setSchemeColor(color: Int) {
        schemeColorState.intValue = color
    }

    fun isSettingsOpen(): Boolean = panelOpen.value

    // Fades the toolbar and panel back while an element is dragged/pinched on the canvas, so
    // they don't hide what's under them. Touch-through isn't needed: the canvas already owns
    // the gesture's pointers.
    fun setDimmed(dimmed: Boolean) {
        val target = if (dimmed) 0.3f else 1f
        toolbarDimAnimator?.cancel()
        panelDimAnimator?.cancel()
        toolbarDimAnimator = fade(toolbarView, target)
        panelDimAnimator = fade(panelView, target)
    }

    private fun fade(view: View, target: Float): ObjectAnimator =
        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, target).apply {
            duration = 120
            start()
        }

    fun showSettings(model: ControlElementSettingsModel) {
        panelModel.value = model
        panelOpen.value = true
        positionPanel(animate = false)
    }

    // Refresh the open panel (after an edit, or when the selection moved to another element).
    fun updateSettings(model: ControlElementSettingsModel) {
        if (panelOpen.value) panelModel.value = model
    }

    fun hideSettings() {
        panelOpen.value = false
    }

    // Something on the canvas moved (drag settled, add/duplicate/undo): re-check whether the
    // panel now covers the selected element.
    fun onCanvasChanged() {
        positionPanel(animate = true)
    }

    // ---------- Internals ----------

    private fun closeSettings() {
        hideSettings()
        settingsCallbacks.onDone()
    }

    private fun dp(value: Float) = value * density

    private fun handleToolbarDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartX = toolbarView.x
                dragStartY = toolbarView.y
                toolbarDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragDownRawX
                val dy = event.rawY - dragDownRawY
                if (!toolbarDragging && hypot(dx, dy) >= touchSlop) toolbarDragging = true
                if (toolbarDragging) {
                    moveToolbar(dragStartX + dx, dragStartY + dy)
                    positionPanel(animate = false)
                }
            }
            MotionEvent.ACTION_UP -> {
                // A tap on the handle (no drag) opens the profile menu.
                if (!toolbarDragging && profileIds.isNotEmpty()) profileMenuOpen.value = true
                toolbarDragging = false
            }
            MotionEvent.ACTION_CANCEL -> toolbarDragging = false
        }
        return true
    }

    private fun moveToolbar(newX: Float, newY: Float) {
        val margin = dp(8f)
        val maxX = max(margin, container.width - toolbarView.width - margin)
        val maxY = max(margin, container.height - toolbarView.height - margin)
        toolbarView.x = newX.coerceIn(margin, maxX)
        toolbarView.y = newY.coerceIn(margin, maxY)
    }

    private fun clampToolbar() {
        if (toolbarView.width == 0 || container.width == 0) return
        moveToolbar(toolbarView.x, toolbarView.y)
    }

    private fun positionPanel(animate: Boolean) {
        val cw = container.width.toFloat()
        val ch = container.height.toFloat()
        if (cw == 0f || ch == 0f || toolbarView.width == 0) return

        val margin = dp(8f)
        val tbLeft = toolbarView.x
        val tbTop = toolbarView.y
        val tbRight = tbLeft + toolbarView.width
        val tbBottom = tbTop + toolbarView.height

        val spaceBelow = ch - tbBottom - 2 * margin
        val spaceAbove = tbTop - 2 * margin
        val pw = panelView.width.toFloat()
        val ph = panelView.height.toFloat()
        val below = spaceBelow >= ph || spaceBelow >= spaceAbove

        val maxHeight = max(dp(160f), if (below) spaceBelow else spaceAbove).toInt()
        if (panelMaxHeightPx.intValue != maxHeight) panelMaxHeightPx.intValue = maxHeight

        if (pw == 0f || ph == 0f) return

        var px = ((tbLeft + tbRight) / 2f - pw / 2f).coerceIn(margin, max(margin, cw - pw - margin))
        val py = if (below) tbBottom + margin else tbTop - margin - ph

        val box = canvas.selectedElement?.boundingBox
        if (box != null && RectF(px, py, px + pw, py + ph).intersects(
                box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()
            )
        ) {
            px = if (box.centerX() < cw / 2f) max(margin, cw - pw - margin) else margin
        }

        panelView.animate().cancel()
        if (animate) {
            panelView.animate().x(px).y(py).setDuration(160).start()
        } else {
            panelView.x = px
            panelView.y = py
        }
    }
}

// Same outer treatment as the in-game sidebar and the magnifier: sidebar panel fill,
// outlineVariant hairline, 8dp shadow, large (18dp) corners.
@Composable
private fun FloatingSurface(content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier.shadow(elevation = 8.dp, shape = shape, clip = false),
        shape = shape,
        color = sidebarPanelColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, hairlineColor()),
        content = content
    )
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditorToolbar(
    profileName: String,
    profileNames: List<String>,
    currentProfileIndex: Int,
    profileMenuOpen: Boolean,
    onProfileMenuDismiss: () -> Unit,
    onProfileSelected: (Int) -> Unit,
    hasSelection: Boolean,
    undoAvailable: Boolean,
    settingsOpen: Boolean,
    schemeColors: List<Int>,
    schemeColor: Int,
    onDragEvent: (MotionEvent) -> Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onUndo: () -> Unit,
    onSettings: () -> Unit,
    onSchemeColor: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    FloatingSurface {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle + profile switcher: drag it to move the toolbar, tap it to pick
            // another profile. Buttons elsewhere keep their own taps.
            Box {
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .pointerInteropFilter(onTouchEvent = onDragEvent)
                        .padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.DragIndicator, contentDescription = "Move toolbar", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            "PROFILE",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            profileName,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = "Switch profile",
                        tint = if (profileMenuOpen) controlAccentColor() else colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp).size(18.dp)
                    )
                }
                SidebarMenu(expanded = profileMenuOpen, onDismissRequest = onProfileMenuDismiss) {
                    profileNames.forEachIndexed { index, name ->
                        SidebarChoiceItem(name, index == currentProfileIndex) { onProfileSelected(index) }
                    }
                }
            }
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.Add, "Add element", iconSize = 24.dp, onClick = onAdd)
            ToolbarButton(Icons.Outlined.Delete, "Remove element", iconSize = 22.dp, enabled = hasSelection, onClick = onRemove)
            ToolbarButton(Icons.Outlined.ContentCopy, "Duplicate element", iconSize = 20.dp, enabled = hasSelection, onClick = onDuplicate)
            ToolbarButton(Icons.AutoMirrored.Outlined.Undo, "Undo", iconSize = 23.dp, enabled = undoAvailable, onClick = onUndo)
            ToolbarDivider()
            ToolbarButton(Icons.Outlined.Tune, "Element settings", iconSize = 21.dp, enabled = hasSelection || settingsOpen, active = settingsOpen, onClick = onSettings)

            var schemeOpen by remember { mutableStateOf(false) }
            Box {
                ToolbarButton(Icons.Outlined.Palette, "Scheme color", iconSize = 20.dp, active = schemeOpen) { schemeOpen = true }
                SidebarMenu(expanded = schemeOpen, onDismissRequest = { schemeOpen = false }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            "Scheme Color",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        ColorSwatchGrid(schemeColors, schemeColor) { color ->
                            schemeOpen = false
                            onSchemeColor(color)
                        }
                        Spacer(Modifier.height(10.dp))
                        // Typing keeps the menu open; tapping a swatch closes it.
                        Box(Modifier.width(250.dp)) { HexColorField(schemeColor, onSchemeColor) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// Fixed 40×40 cell for every tool, no Material IconButton: its 48dp minimum touch target
// was stretching the 40dp buttons unevenly inside the row. Icon sizes are set per icon to even
// out how big the glyphs *look* — the Material outlines fill very different parts of their
// 24dp box (the "+" ~14dp, the copy icon ~19×22dp).
@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        active -> controlAccentColor()
        else -> colors.onSurface
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(if (active) Modifier.background(colors.surfaceVariant) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}
