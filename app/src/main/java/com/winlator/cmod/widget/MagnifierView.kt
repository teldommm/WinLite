package com.winlator.cmod.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.cmod.core.Callback
import com.winlator.cmod.ui.sidebarPanelColor
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import kotlin.math.roundToInt
import com.winlator.cmod.ui.theme.TablerIcons

// Floating magnifier toolbar (shown over the X server surface from the in-game sidebar).
// Layout/shape follows WinLite's Compose panel — narrow rounded column, Material outlined
// icons instead of the old drawable-hdpi PNGs — but the colors come from WinZTheme, using
// the same outer-card treatment as the in-game sidebar (sidebarPanelColor + outlineVariant
// border + 8dp shadow), so it follows White/Dark/AMOLED like everything else.
//
// Public API is unchanged from the old Java widget, so XServerDisplayActivity needs no edits.
class MagnifierView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var restoreSavedPosition = true
    private val zoom = mutableFloatStateOf(1.0f)

    var zoomButtonCallback: Callback<Float>? = null
    var hideButtonCallback: Runnable? = null

    // Drag state. Raw (screen) coordinates are used on purpose: this view moves under the
    // finger while dragging, so view-local deltas (Compose's detectDragGestures) would be
    // measured against a shifting origin and make the panel jitter.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f

    init {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(ComposeView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZOverlayTheme {
                    MagnifierPanel(
                        zoom = zoom.floatValue,
                        onZoomChange = { delta -> zoomButtonCallback?.call(delta) },
                        onHide = { hideButtonCallback?.run() },
                        onDragEvent = this@MagnifierView::handleDrag
                    )
                }
            }
        })
    }

    fun setZoomValue(value: Float) {
        zoom.floatValue = value
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!restoreSavedPosition || width == 0 || height == 0) return
        // Default (no saved position) = bottom-right corner, same as the old widget:
        // a huge value gets clamped to the far edge by movePanel.
        val saved = readSavedOffset()
        movePanel(saved?.first ?: 1e6f, saved?.second ?: 1e6f)
        restoreSavedPosition = false
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downViewX = x
                downViewY = y
            }
            MotionEvent.ACTION_MOVE ->
                movePanel(downViewX + (event.rawX - downRawX), downViewY + (event.rawY - downRawY))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                saveCurrentOffset()
        }
        return true
    }

    private fun movePanel(newX: Float, newY: Float) {
        val parentView = parent as? ViewGroup ?: return
        val padding = 8f * resources.displayMetrics.density
        val maxX = (parentView.width - width - padding).coerceAtLeast(padding)
        val maxY = (parentView.height - height - padding).coerceAtLeast(padding)
        x = newX.coerceIn(padding, maxX)
        y = newY.coerceIn(padding, maxY)
    }

    // Same "x|y" preference key/format the Java widget wrote, so saved positions carry over.
    private fun saveCurrentOffset() {
        preferences.edit().putString(PREF_KEY, "${x.roundToInt()}|${y.roundToInt()}").apply()
    }

    private fun readSavedOffset(): Pair<Float, Float>? {
        val parts = preferences.getString(PREF_KEY, null)?.split("|") ?: return null
        if (parts.size < 2) return null
        val px = parts[0].toFloatOrNull() ?: return null
        val py = parts[1].toFloatOrNull() ?: return null
        return px to py
    }

    private companion object {
        const val PREF_KEY = "magnifier_view"
    }
}

// Mirrors the clamp in XServerDisplayActivity.onMagnifier (Mathf.clamp(..., 1.0f, 3.0f)) —
// used only to dim +/− at the ends of the range. Keep in sync if that range changes.
private const val MIN_ZOOM = 1.0f
private const val MAX_ZOOM = 3.0f
private const val ZOOM_STEP = 0.25f

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MagnifierPanel(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onHide: () -> Unit,
    onDragEvent: (MotionEvent) -> Boolean
) {
    val shape = MaterialTheme.shapes.large // 18dp, WinlatorShapes scale
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.shadow(elevation = 8.dp, shape = shape, clip = false),
        shape = shape,
        color = sidebarPanelColor(),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.width(48.dp).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInteropFilter(onTouchEvent = onDragEvent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TablerIcons.ArrowsMove,
                    contentDescription = "Move",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            MagnifierIconButton(TablerIcons.Plus, "Zoom in", enabled = zoom < MAX_ZOOM) {
                onZoomChange(ZOOM_STEP)
            }

            Text(
                text = "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = colors.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            MagnifierIconButton(TablerIcons.Minus, "Zoom out", enabled = zoom > MIN_ZOOM) {
                onZoomChange(-ZOOM_STEP)
            }

            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .width(24.dp)
                    .background(colors.outlineVariant, CircleShape)
            )

            MagnifierIconButton(TablerIcons.X, "Hide", tint = colors.onSurfaceVariant, onClick = onHide)
        }
    }
}

@Composable
private fun MagnifierIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
