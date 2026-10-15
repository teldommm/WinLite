package com.winlator.cmod.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.MotionAccumulator;
import com.winlator.cmod.ui.inputcontrols.EditorCanvasColors;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.85f;
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final ColorFilter colorFilter = new PorterDuffColorFilter(0xff2184ff, PorterDuff.Mode.SRC_IN);
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;

    // ---------- Editor (edit mode only) ----------
    // Listener the ControlsEditorActivity uses to keep its floating toolbar/settings panel in
    // sync with what happens on the canvas.
    public interface EditorListener {
        void onSelectionChanged(ControlElement element);
        // Fired after a drag has fully settled (position is final and saved), and after
        // add/duplicate/undo — anything that changes where elements sit.
        void onElementsChanged();
        void onUndoAvailabilityChanged(boolean available);
        // True while an element is being dragged or pinch-scaled on the canvas (the floating
        // toolbar/panel dim themselves so what's underneath stays visible).
        void onElementGestureChanged(boolean active);
    }

    private static final long SETTLE_DURATION_MS = 120;
    // Fraction of one grid cell within which an axis sticks to a grid line while dragging.
    private static final float GRID_MAGNET = 0.3f;
    // Fraction of one grid cell within which an axis sticks to an alignment guide (screen
    // centre, another element's centre/edges). Larger than the grid magnet on purpose: guides
    // are rare and meaningful, grid lines are everywhere.
    private static final float GUIDE_MAGNET = 0.6f;

    private EditorListener editorListener;

    // Touchscreen control haptics (see TouchHaptics). The switch is re-read at the start of
    // every touch event; the default matches what the in-game sidebar shows (off).
    private TouchHaptics touchHaptics;
    private boolean touchHapticsEnabled = false;
    private EditorCanvasColors canvasColors;
    private int touchSlop;
    private int activeEditPointerId = -1;
    private float downTouchX, downTouchY;
    private boolean draggingElement = false;
    private int dragStartX, dragStartY;
    // Live (unsnapped-to-int) crosshair position; `cursor` keeps the settled grid node that
    // addElement() uses.
    private float cursorX, cursorY;
    // Alignment guide currently being snapped to, per axis (NaN = none). Drawn as accent lines.
    private float guideLineX = Float.NaN, guideLineY = Float.NaN;
    private ValueAnimator elementSettleAnimator;
    private ValueAnimator cursorAnimator;
    private Runnable undoAction;

    // Pinch-to-scale of the selected element (second finger down while it is held).
    private static final float MIN_ELEMENT_SCALE = 0.5f;
    private static final float MAX_ELEMENT_SCALE = 2.0f;
    private boolean pinching = false;
    // After a pinch the rest of the gesture is ignored until every finger is up, so the
    // remaining finger doesn't start dragging the element it was just scaling.
    private boolean gestureConsumed = false;
    private int pinchPointerId = -1;
    private float pinchStartDistance;
    private float pinchStartScale;
    private boolean elementGestureActive = false;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final android.util.SparseArray<Bitmap> icons = new android.util.SparseArray<>();
    // ---------- Stick mouse (MOUSE_MOVE bindings on sticks, D-pads, buttons, gamepads) ----------
    // Full deflection at 100% Cursor Speed = 600 px/s (the old 10 px per 60 Hz tick), now scaled
    // by the real frame time so the speed no longer depends on how often the loop runs.
    private static final float STICK_MOUSE_PIXELS_PER_SECOND = 600f;
    // Response curve applied after the radial dead zone: 1 = linear, 2 = quadratic. 1.5 keeps the
    // top end close to the old linear feel while giving much finer control near the centre.
    public static final float STICK_MOUSE_CURVE = 1.5f;
    // Longest frame gap fed into one step, so a stall (GC, app switch) can't fling the cursor.
    private static final float STICK_MOUSE_MAX_DT = 0.05f;

    private static final int MOUSE_DIR_UP = 1, MOUSE_DIR_RIGHT = 2, MOUSE_DIR_DOWN = 4, MOUSE_DIR_LEFT = 8;
    // Digital sources (buttons, key-style D-pad presses): one bit per held direction, so
    // releasing LEFT while RIGHT is still held no longer stops the cursor.
    private final AtomicInteger mouseHeldDirs = new AtomicInteger();
    // Per-axis analog sources that still come through handleInputEvent with an offset
    // (on-screen D-pad element, gamepad triggers): packed (x, y).
    private final AtomicLong mouseLegacyAnalog = new AtomicLong(packMouse(0, 0));
    // 2D analog sources (on-screen sticks, gamepad sticks/hats), already shaped, keyed by source:
    // several can be active at once and releasing one doesn't cancel the others.
    private final Map<Object, Long> mouseAnalogSources = new ConcurrentHashMap<>();
    // External gamepad: last side of each stick/hat axis, per controller id (main thread only).
    private final Map<String, byte[]> joystickAxisSides = new java.util.HashMap<>();
    // External gamepad: whether each analog trigger's binding is currently pressed (L2, R2).
    private final Map<String, boolean[]> triggerBindingPressed = new java.util.HashMap<>();

    // Vsync-driven loop on its own looper thread (Choreographer works on any Looper thread),
    // running only while some source is non-zero.
    private final Object mouseMoveLock = new Object();
    private HandlerThread mouseMoveThread;
    private Handler mouseMoveHandler;
    private final AtomicBoolean mouseMoveRunning = new AtomicBoolean();
    // Only touched on mouseMoveThread.
    private Choreographer mouseMoveChoreographer;
    private long mouseMoveLastFrameNanos;
    private final MotionAccumulator mouseMoveMotion = new MotionAccumulator();
    private final Choreographer.FrameCallback mouseMoveFrame = this::onMouseMoveFrame;
    private boolean showTouchscreenControls = true;

    // ---------- Touchscreen Timeout (auto-hide) ----------
    // After AUTO_HIDE_DELAY_MS with every finger up, the controls fade out. The view stays
    // VISIBLE (only its alpha drops), so physical controllers and focus are unaffected. While
    // concealed, the next touch only brings the controls back: that whole gesture (until every
    // finger is up) is swallowed and reaches neither the elements nor the touchpad.
    private static final long AUTO_HIDE_DELAY_MS = 5000;
    private static final long AUTO_HIDE_FADE_OUT_MS = 250;
    private static final long AUTO_HIDE_FADE_IN_MS = 120;
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable concealRunnable = this::conceal;
    private boolean autoHideEnabled = false;
    private boolean concealed = false;
    private boolean swallowingWakeGesture = false;

    private SharedPreferences preferences;

    private ControlElement stickElement;

    private boolean focusOnStick = false; // A flag to determine if we are focusing on the stick

    public boolean isFocusedOnStick() {
        return focusOnStick;
    }

    public void setFocusOnStick(boolean focus) {
        this.focusOnStick = focus;
        invalidate(); // Redraw the view with the new focus setting
    }



    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus(); // Add this line to request focus
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }

    public InputControlsView(Context context, boolean focusOnStick) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus(); // Add this line to request focus
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));

        // If focusOnStick is true, adjust the layout params to match the stick element size
        if (focusOnStick) {
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }


    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (editMode) {
            canvasColors = EditorCanvasColors.resolve(getContext());
            touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }
    }

    public void setEditorListener(EditorListener listener) {
        this.editorListener = listener;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public void invalidateElement(Rect rect) {
        if (rect == null) {
            invalidate();
            return;
        }
        invalidate(rect.left, rect.top, rect.right, rect.bottom);
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width, height;

        if (stickElement != null && isFocusedOnStick()) {
            // If focusing on the stick, set width and height to the stick's bounding box size
            Rect boundingBox = stickElement.getBoundingBox();
            width = boundingBox.width();
            height = boundingBox.height();
        } else {
            // Default behavior for full screen
            width = getWidth();
            height = getHeight();
        }

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (stickElement != null) {
            // Draw only the stick element if focus mode is active
            stickElement.draw(canvas);
        }

        if (profile != null && showTouchscreenControls && !isFocusedOnStick()) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            if (editMode) drawDynamicZones(canvas);

            for (ControlElement element : profile.getElements()) {
                element.draw(canvas);
            }
        }

        if (editMode) drawGuides(canvas);

        super.onDraw(canvas);
    }


    public void resetStickPosition() {
        if (stickElement != null) {
            Rect boundingBox = stickElement.getBoundingBox();
            float centerX = boundingBox.centerX();
            float centerY = boundingBox.centerY();

            stickElement.setCurrentPosition(centerX, centerY); // Reset to the center of the bounding box
            invalidate(); // Redraw the stick in the centered position
        }
    }



    public void initializeStickElement(float x, float y, float scale) {
        stickElement = new ControlElement(this);
        stickElement.setType(ControlElement.Type.STICK); // Set type to STICK
        stickElement.setX((int) x);
        stickElement.setY((int) y);
        stickElement.setScale(scale);
        invalidate(); // Force the view to redraw with the stick
    }


    public void updateStickPosition(float x, float y) {
        if (stickElement != null) {
            stickElement.getCurrentPosition().x = x;  // Update the thumbstick's position
            stickElement.getCurrentPosition().y = y;  // Update the thumbstick's position
            invalidate(); // Redraw the view
        }
    }


    public ControlElement getStickElement() {
        return stickElement;
    }

    private void drawGrid(Canvas canvas) {
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        canvas.drawColor(colors.background);

        paint.setAntiAlias(false);
        paint.setColor(colors.gridLine);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i <= width; i += snappingSize) canvas.drawLine(i, 0, i, height, paint);
        for (int i = 0; i <= height; i += snappingSize) canvas.drawLine(0, i, width, i, paint);

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(colors.gridCenter);

        for (int i = 0; i < height; i += snappingSize * 2) canvas.drawLine(cx, i, cx, i + snappingSize, paint);
        for (int i = 0; i < width; i += snappingSize * 2) canvas.drawLine(i, cy, i + snappingSize, cy, paint);

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(colors.cursor);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursorY, getMaxWidth(), cursorY, paint);
        canvas.drawLine(cursorX, 0, cursorX, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    // Editor only: the zone of every dynamic stick as a dashed square (stronger when selected),
    // under the controls. Never drawn in game.
    private final DashPathEffect zoneDash = new DashPathEffect(new float[]{12f, 10f}, 0f);

    private void drawDynamicZones(Canvas canvas) {
        if (profile == null) return;
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        for (ControlElement element : profile.getElements()) {
            if (!element.isDynamicStick()) continue;
            RectF zone = element.getDynamicZone();
            boolean selected = element == selectedElement;

            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((colors.guide & 0x00FFFFFF) | ((selected ? 0x22 : 0x10) << 24));
            canvas.drawRect(zone, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, snappingSize * 0.1f));
            paint.setColor((colors.guide & 0x00FFFFFF) | ((selected ? 0xCC : 0x66) << 24));
            paint.setPathEffect(zoneDash);
            canvas.drawRect(zone, paint);
        }
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawGuides(Canvas canvas) {
        if (Float.isNaN(guideLineX) && Float.isNaN(guideLineY)) return;
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(Math.max(2f, snappingSize * 0.125f));
        paint.setColor(colors.guide);
        if (!Float.isNaN(guideLineX)) canvas.drawLine(guideLineX, 0, guideLineX, getHeight(), paint);
        if (!Float.isNaN(guideLineY)) canvas.drawLine(0, guideLineY, getWidth(), guideLineY, paint);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            finishPendingAnimations();
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            keepInside(element);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            setUndo(() -> removeWithoutUndo(element));
            notifyElementsChanged();
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            finishPendingAnimations();
            final ControlElement removed = selectedElement;
            final int index = profile.getElements().indexOf(removed);
            profile.removeElement(removed);
            selectElement(null);
            profile.save();
            invalidate();
            setUndo(() -> {
                profile.addElementAt(index, removed);
                profile.save();
                selectElement(removed);
                notifyElementsChanged();
            });
            return true;
        }
        else return false;
    }

    // Copy of the selected element (same JSON round-trip the profile loader uses, so every
    // field comes along), dropped a few grid cells down-right of the original and selected.
    public synchronized boolean duplicateElement() {
        if (!editMode || selectedElement == null || profile == null || snappingSize <= 0) return false;
        finishPendingAnimations();
        ControlElement copy = ControlsProfile.elementFromJSON(selectedElement.toJSONObject(), this);
        if (copy == null) return false;
        int step = snappingSize * 4;
        copy.setX(clampInt(selectedElement.getX() + step, 0, getMaxWidth()));
        copy.setY(clampInt(selectedElement.getY() + step, 0, getMaxHeight()));
        keepInside(copy);
        profile.addElement(copy);
        profile.save();
        selectElement(copy);
        setUndo(() -> removeWithoutUndo(copy));
        notifyElementsChanged();
        return true;
    }

    public synchronized boolean undo() {
        if (!editMode || undoAction == null) return false;
        finishPendingAnimations();
        Runnable action = undoAction;
        setUndo(null);
        action.run();
        invalidate();
        return true;
    }

    public boolean canUndo() {
        return undoAction != null;
    }

    // Records the element's current state as the one-step undo, for edits made outside the
    // canvas (the settings panel). Undo swaps in a fresh copy built from the snapshot — the same
    // JSON round-trip the profile loader uses — at the same list index, with the exact position.
    public synchronized void recordElementSnapshot(final ControlElement element) {
        if (element == null || profile == null) return;
        final JSONObject snapshot = element.toJSONObject();
        if (snapshot == null) return;
        final int x = element.getX();
        final int y = element.getY();
        setUndo(() -> {
            int index = profile.getElements().indexOf(element);
            if (index < 0) return;
            ControlElement restored = ControlsProfile.elementFromJSON(snapshot, this);
            if (restored == null) return;
            restored.setX(x);
            restored.setY(y);
            profile.removeElement(element);
            profile.addElementAt(index, restored);
            profile.save();
            selectElement(restored);
            notifyElementsChanged();
        });
    }

    private void removeWithoutUndo(ControlElement element) {
        profile.removeElement(element);
        if (selectedElement == element) selectElement(null);
        profile.save();
        notifyElementsChanged();
    }

    private void setUndo(Runnable action) {
        boolean before = undoAction != null;
        undoAction = action;
        if (editorListener != null && before != (action != null)) {
            editorListener.onUndoAvailabilityChanged(action != null);
        }
    }

    private void notifyElementsChanged() {
        if (editorListener != null) editorListener.onElementsChanged();
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- keeping elements on screen (editor) ----
    // The whole bounding box stays inside the visible view. getMaxWidth()/getMaxHeight() are
    // rounded to the grid and can be a little larger than the view, so the real size is the limit.
    // An element bigger than the view on an axis is centred on that axis.

    private float boundsWidth() {
        return Math.min(getWidth(), getMaxWidth());
    }

    private float boundsHeight() {
        return Math.min(getHeight(), getMaxHeight());
    }

    private static float clampAxis(float value, float half, float extent) {
        float lo = half;
        float hi = extent - half;
        if (lo > hi) return extent * 0.5f;
        return Math.max(lo, Math.min(hi, value));
    }

    // Same, but on a grid line when one fits, so a released element is still grid-aligned.
    private float clampAxisToGrid(float value, float half, float extent) {
        float lo = half;
        float hi = extent - half;
        if (lo > hi) return extent * 0.5f;
        float gridLo = (float) Math.ceil(lo / snappingSize) * snappingSize;
        float gridHi = (float) Math.floor(hi / snappingSize) * snappingSize;
        if (gridLo <= gridHi) return Math.max(gridLo, Math.min(gridHi, value));
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * Pushes the element back so it is fully on screen. Call it after anything that moves or
     * resizes an element in the editor (drag, pinch, scale / shape / type changes, new copies).
     */
    public void keepInside(ControlElement element) {
        if (element == null || getWidth() <= 0 || getHeight() <= 0) return;
        Rect box = element.getBoundingBox();
        float halfW = box.width() * 0.5f;
        float halfH = box.height() * 0.5f;
        int x = Math.round(clampAxis(element.getX(), halfW, boundsWidth()));
        int y = Math.round(clampAxis(element.getY(), halfH, boundsHeight()));
        if (x != element.getX()) element.setX(x);
        if (y != element.getY()) element.setY(y);
        invalidate();
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        ControlElement previous = selectedElement;
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
        if (editorListener != null && previous != element) editorListener.onSelectionChanged(element);
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        ControlElement previousSelection = selectedElement;
        if (editMode) {
            // Switching profiles inside the editor: land any glide on the old profile first,
            // and drop the undo step — it points at the old profile's elements.
            finishPendingAnimations();
            setUndo(null);
            guideLineX = Float.NaN;
            guideLineY = Float.NaN;
        }
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        }
        else this.profile = null;
        // Inputs held on the previous profile's elements must not keep moving the cursor.
        clearStickMouseSources();
        selectedElement = null;
        if (editMode) {
            invalidate();
            if (previousSelection != null && editorListener != null) editorListener.onSelectionChanged(null);
        }
        else runOnUi(this::resetAutoHide);
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
        runOnUi(this::resetAutoHide);
    }

    public int getPrimaryColor() {
        // Kept for compatibility with ControlElement; visual style now derives from secondary blue.
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        // Winlator-like electric blue used by the app UI. Alpha is handled per primitive.
        return Color.argb(255, 33, 132, 255);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public Path getPath() {
        return path;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMouseMoveThread();
        autoHideHandler.removeCallbacks(concealRunnable);
        super.onDetachedFromWindow();
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    // ---------- Stick mouse loop ----------

    private static long packMouse(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32) | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }

    private static float unpackMouseX(long v) {
        return Float.intBitsToFloat((int) (v >>> 32));
    }

    private static float unpackMouseY(long v) {
        return Float.intBitsToFloat((int) v);
    }

    /**
     * Radial dead zone + rescale + response curve for a stick vector in [-1, 1]². Output starts
     * at 0 right at the edge of the dead zone (it used to jump straight to 0.15) and keeps the
     * input direction; full deflection still maps to 1. Returns {x, y}.
     */
    public static float[] shapeStickMouse(float x, float y) {
        float magnitude = (float) Math.sqrt(x * x + y * y);
        float deadZone = ControlElement.STICK_DEAD_ZONE;
        if (magnitude <= deadZone) return new float[]{0f, 0f};
        float t = (Math.min(magnitude, 1f) - deadZone) / (1f - deadZone);
        float scale = (float) Math.pow(t, STICK_MOUSE_CURVE) / magnitude;
        return new float[]{x * scale, y * scale};
    }

    /** Unit direction of a MOUSE_MOVE binding: {dx, dy}. */
    public static int[] mouseMoveDirection(Binding binding) {
        switch (binding) {
            case MOUSE_MOVE_LEFT:  return new int[]{-1, 0};
            case MOUSE_MOVE_RIGHT: return new int[]{1, 0};
            case MOUSE_MOVE_UP:    return new int[]{0, -1};
            case MOUSE_MOVE_DOWN:  return new int[]{0, 1};
            default:               return new int[]{0, 0};
        }
    }

    /**
     * Sets a 2D analog source (already shaped, in [-1, 1]); (0, 0) removes it. Sources are
     * summed, so e.g. two sticks or a stick and a gamepad don't cancel each other on release.
     */
    public void setStickMouse(Object source, float x, float y) {
        if (x == 0f && y == 0f) mouseAnalogSources.remove(source);
        else {
            mouseAnalogSources.put(source, packMouse(x, y));
            wakeMouseMove();
        }
    }

    private void setMouseHeld(int dirBit, boolean held) {
        if (held) {
            mouseHeldDirs.getAndUpdate(v -> v | dirBit);
            wakeMouseMove();
        }
        else mouseHeldDirs.getAndUpdate(v -> v & ~dirBit);
    }

    private void setMouseLegacyAxis(boolean horizontal, float value) {
        long prev, next;
        do {
            prev = mouseLegacyAnalog.get();
            next = horizontal ? packMouse(value, unpackMouseY(prev)) : packMouse(unpackMouseX(prev), value);
        } while (!mouseLegacyAnalog.compareAndSet(prev, next));
        if (value != 0f) wakeMouseMove();
    }

    private void clearStickMouseSources() {
        mouseHeldDirs.set(0);
        mouseLegacyAnalog.set(packMouse(0, 0));
        mouseAnalogSources.clear();
    }

    /** Current combined stick-mouse vector, each axis clamped to [-1, 1]. */
    private float[] currentStickMouse() {
        int held = mouseHeldDirs.get();
        float x = ((held & MOUSE_DIR_RIGHT) != 0 ? 1 : 0) - ((held & MOUSE_DIR_LEFT) != 0 ? 1 : 0);
        float y = ((held & MOUSE_DIR_DOWN) != 0 ? 1 : 0) - ((held & MOUSE_DIR_UP) != 0 ? 1 : 0);
        long legacy = mouseLegacyAnalog.get();
        x += unpackMouseX(legacy);
        y += unpackMouseY(legacy);
        for (Long v : mouseAnalogSources.values()) {
            x += unpackMouseX(v);
            y += unpackMouseY(v);
        }
        return new float[]{Mathf.clamp(x, -1, 1), Mathf.clamp(y, -1, 1)};
    }

    private void wakeMouseMove() {
        if (!mouseMoveRunning.compareAndSet(false, true)) return;
        Handler handler;
        synchronized (mouseMoveLock) {
            if (mouseMoveThread == null) {
                mouseMoveThread = new HandlerThread("StickMouse", android.os.Process.THREAD_PRIORITY_DISPLAY);
                mouseMoveThread.start();
                mouseMoveHandler = new Handler(mouseMoveThread.getLooper());
            }
            handler = mouseMoveHandler;
        }
        handler.post(() -> {
            // Thread-local: always this looper's instance (the thread is recreated after detach).
            mouseMoveChoreographer = Choreographer.getInstance();
            mouseMoveLastFrameNanos = 0;
            mouseMoveMotion.reset();
            mouseMoveChoreographer.postFrameCallback(mouseMoveFrame);
        });
    }

    private void stopMouseMoveThread() {
        clearStickMouseSources();
        synchronized (mouseMoveLock) {
            if (mouseMoveThread != null) {
                mouseMoveThread.quitSafely();
                mouseMoveThread = null;
                mouseMoveHandler = null;
            }
        }
        // The old looper is gone; the next wake starts a fresh thread and Choreographer.
        mouseMoveRunning.set(false);
    }

    private void onMouseMoveFrame(long frameTimeNanos) {
        float[] v = currentStickMouse();
        if (v[0] == 0f && v[1] == 0f) {
            // Idle: stop re-posting. Re-check after clearing the flag so an input that arrived in
            // between (and saw the loop as still running) isn't lost.
            mouseMoveRunning.set(false);
            v = currentStickMouse();
            if ((v[0] == 0f && v[1] == 0f) || !mouseMoveRunning.compareAndSet(false, true)) {
                mouseMoveMotion.reset();
                mouseMoveLastFrameNanos = 0;
                return;
            }
        }
        mouseMoveChoreographer.postFrameCallback(mouseMoveFrame);

        float dt = mouseMoveLastFrameNanos == 0 ? 1f / 60f : (frameTimeNanos - mouseMoveLastFrameNanos) / 1e9f;
        mouseMoveLastFrameNanos = frameTimeNanos;
        if (dt <= 0f) return;
        dt = Math.min(dt, STICK_MOUSE_MAX_DT);

        // Stick / D-pad / button / gamepad mouse moves follow the global Cursor Speed (the in-game
        // sidebar slider), same as the touch area. Read every frame, so the slider applies live.
        TouchpadView tp = touchpadView;
        float speed = tp != null ? tp.getSensitivity() : 1.0f;
        float step = speed * STICK_MOUSE_PIXELS_PER_SECOND * dt;
        mouseMoveMotion.add(v[0] * step, v[1] * step);
        int dx = mouseMoveMotion.x();
        int dy = mouseMoveMotion.y();
        XServer server = xServer;
        if ((dx == 0 && dy == 0) || server == null) return;
        if (server.isRelativeMouseMovement()) {
            WinHandler winHandler = server.getWinHandler();
            if (winHandler != null) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
        }
        else server.injectPointerMoveDelta(dx, dy);
    }

    private void processJoystickInput(ExternalController controller) {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        // MOUSE_MOVE bindings on a stick or hat are handled per pair as one 2D vector (radial
        // dead zone + curve, direction taken from the binding), not per axis below.
        processJoystickMouse(controller, axes, values);

        // Per axis, the side (-1 / 0 / +1) it was on at the previous event. A binding is released
        // when its side is left, not only when the axis comes back inside the dead zone: a fast
        // flick from one edge to the other can skip the dead zone between two events, which left
        // the first direction's key stuck. Release goes first, so two directions bound to the
        // same gamepad axis don't zero the new value.
        byte[] sides = joystickAxisSides.get(controller.getId());
        if (sides == null) {
            sides = new byte[axes.length];
            joystickAxisSides.put(controller.getId(), sides);
        }
        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte side = Math.abs(value) > ControlElement.STICK_DEAD_ZONE ? Mathf.sign(value) : 0;
            byte prev = sides[i];
            if (prev != 0 && prev != side) {
                Binding b = axisBinding(controller, axes[i], prev);
                if (b != null && !b.isMouseMove()) handleInputEvent(controller, b, false, 0, false);
            }
            if (side != 0) {
                Binding b = axisBinding(controller, axes[i], side);
                // Gamepad bindings follow the analog value on every event; key / mouse-button
                // bindings only on the edge (in Relative Mouse mode a mouse button used to get a
                // fresh LEFTDOWN on every stick event).
                if (b != null && !b.isMouseMove() && (b.isGamepad() || prev != side))
                    handleInputEvent(controller, b, true, value, false);
            }
            sides[i] = side;
        }

        // Analog triggers only: digital ones (BUTTON_L2/R2 keys) are handled in onKeyEvent, and
        // reading their absent axis here released the binding on every stick movement.
        if (controller.hasAnalogTriggers()) {
            processTriggerInput(controller, controller.state.triggerL, KeyEvent.KEYCODE_BUTTON_L2, 0);
            processTriggerInput(controller, controller.state.triggerR, KeyEvent.KEYCODE_BUTTON_R2, 1);
        }
        // The caller (WinHandler) writes the merged state once afterwards.
    }

    private static Binding axisBinding(ExternalController controller, int axis, int sign) {
        ExternalControllerBinding b = controller.getControllerBinding(
                ExternalControllerBinding.getKeyCodeForAxis(axis, (byte) sign));
        return b != null ? b.getBinding() : null;
    }

    private void processJoystickMouse(ExternalController controller, int[] axes, float[] values) {
        boolean any = false;
        float outX = 0f, outY = 0f;
        for (int p = 0; p + 1 < axes.length; p += 2) {
            Binding xNeg = axisBinding(controller, axes[p], -1), xPos = axisBinding(controller, axes[p], 1);
            Binding yNeg = axisBinding(controller, axes[p + 1], -1), yPos = axisBinding(controller, axes[p + 1], 1);
            boolean xMouse = (xNeg != null && xNeg.isMouseMove()) || (xPos != null && xPos.isMouseMove());
            boolean yMouse = (yNeg != null && yNeg.isMouseMove()) || (yPos != null && yPos.isMouseMove());
            if (!xMouse && !yMouse) continue;
            any = true;

            float vx = xMouse ? values[p] : 0f;
            float vy = yMouse ? values[p + 1] : 0f;
            // The hat is digital (-1/0/1): no dead zone or curve, and diagonals stay (1, 1).
            float[] shaped = axes[p] == MotionEvent.AXIS_HAT_X ? new float[]{vx, vy} : shapeStickMouse(vx, vy);

            for (int a = 0; a < 2; a++) {
                float c = shaped[a];
                if (c == 0f) continue;
                Binding b = a == 0 ? (c > 0 ? xPos : xNeg) : (c > 0 ? yPos : yNeg);
                if (b == null || !b.isMouseMove()) continue;
                int[] dir = mouseMoveDirection(b);
                float m = Math.abs(c);
                outX += dir[0] * m;
                outY += dir[1] * m;
            }
        }
        Object key = "gamepad:" + controller.getId();
        if (any) setStickMouse(key, Mathf.clamp(outX, -1, 1), Mathf.clamp(outY, -1, 1));
        else mouseAnalogSources.remove(key);
    }

    private void processTriggerInput(ExternalController controller, float value, int keyCode, int index) {
        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        if (controllerBinding == null) return;
        Binding binding = controllerBinding.getBinding();

        boolean[] pressed = triggerBindingPressed.get(controller.getId());
        if (pressed == null) {
            pressed = new boolean[2];
            triggerBindingPressed.put(controller.getId(), pressed);
        }
        boolean isPressed = value > ControlElement.STICK_DEAD_ZONE;
        boolean analog = binding.isGamepad() || binding.isMouseMove();
        if (isPressed) {
            if (analog || !pressed[index]) handleInputEvent(controller, binding, true, value, false);
        }
        else if (pressed[index]) {
            handleInputEvent(controller, binding, false, 0, false);
        }
        pressed[index] = isPressed;
    }

    /**
     * Joystick motion of a physical controller, after WinHandler updated its state: runs this
     * profile's bindings for it (remappedState, keys, mouse). Called directly instead of relying
     * on this view having focus, which joystick events otherwise need.
     */
    public void processExternalControllerMotion(ExternalController controller) {
        if (editMode || profile == null || controller.getControllerBindingCount() == 0) return;
        if (profile.getController(controller.getDeviceId()) != controller) return;
        processJoystickInput(controller);
    }

    /** Releases everything a controller's stick / trigger bindings hold (device disconnected). */
    public void releaseExternalController(ExternalController controller) {
        byte[] sides = joystickAxisSides.remove(controller.getId());
        if (sides != null) {
            final int[] axes = {
                    MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                    MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                    MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
            };
            for (int i = 0; i < axes.length && i < sides.length; i++) {
                if (sides[i] == 0) continue;
                Binding b = axisBinding(controller, axes[i], sides[i]);
                if (b != null && !b.isMouseMove()) handleInputEvent(controller, b, false, 0, false);
            }
        }
        boolean[] triggers = triggerBindingPressed.remove(controller.getId());
        if (triggers != null) {
            int[] keyCodes = {KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2};
            for (int i = 0; i < 2; i++) {
                if (!triggers[i]) continue;
                ExternalControllerBinding b = controller.getControllerBinding(keyCodes[i]);
                if (b != null) handleInputEvent(controller, b.getBinding(), false, 0, false);
            }
        }
        mouseAnalogSources.remove("gamepad:" + controller.getId());
    }




    // Physical controllers' joystick motion no longer comes through here: WinHandler handles it
    // and calls processExternalControllerMotion() (this used to depend on the view having focus,
    // and the per-event logging ran for every mouse and stick event).

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        UnbufferedInput.onTouchEvent(this, event);

        boolean hapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);
        touchHapticsEnabled = hapticsEnabled && !editMode;

        if (handleAutoHideTouch(event)) return true;

        if (editMode && readyToDraw) {
            handleEditTouch(event);
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            // A new gesture starts with no finger down, so any element still claiming a pointer
            // is left over from a gesture that never ended properly (e.g. cancelled by the
            // sidebar or a system gesture). Release it first: otherwise it stays pressed and,
            // since the touchpad ignores pointers held by controls, that pointer id (usually 0,
            // the first finger) would be ignored by the trackpad from then on.
            if (actionMasked == MotionEvent.ACTION_DOWN) releaseStaleElements();

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    boolean hapticPlayed = false;
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            handled = true;
                            // One pulse per touch even if overlapping elements both took it.
                            // D-pads report their own (per direction) from ControlElement.
                            if (!hapticPlayed) {
                                int kind = element.getTouchDownHaptic();
                                if (kind != TouchHaptics.NONE) {
                                    playTouchHaptic(kind);
                                    hapticPlayed = true;
                                }
                            }
                        }
                    }
                    // Second pass: only a touch nobody took by their own bounds may spawn a
                    // dynamic stick from its zone — buttons inside a zone keep priority.
                    if (!handled) {
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleDynamicZoneTouchDown(pointerId, x, y)) {
                                handled = true;
                                // No haptic: sticks are silent by design.
                                break;
                            }
                        }
                    }
                    if (!handled) forwardToTouchpad(event);
                    else syncCapturedPointers();
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    // The touchpad gets the whole event at most once (it used to receive it once
                    // per unhandled pointer, so e.g. a two-finger scroll was processed twice).
                    boolean anyUnhandled = false;
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int pid = event.getPointerId(i);

                        boolean pointerHandled = trySwipe(pid, x, y);
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(pid, x, y)) pointerHandled = true;
                        }
                        if (!pointerHandled) anyUnhandled = true;
                    }
                    if (anyUnhandled) forwardToTouchpad(event);
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    // Cancel ends the gesture for every pointer, not just the one at actionIndex.
                    releaseStaleElements();
                    forwardToTouchpad(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    if (!handled) forwardToTouchpad(event);
                    else syncCapturedPointers();
                    break;
            }
        }
        return true;
    }





    /** Touchscreen Timeout on/off (shortcut setting, sidebar can change it for the session). */
    public void setAutoHideEnabled(boolean enabled) {
        autoHideEnabled = enabled;
        runOnUi(this::resetAutoHide);
    }

    public boolean isAutoHideEnabled() {
        return autoHideEnabled;
    }

    private boolean isAutoHideActive() {
        return autoHideEnabled && !editMode && profile != null && showTouchscreenControls
                && getVisibility() == VISIBLE;
    }

    // Returns true when the event belongs to the gesture that wakes concealed controls.
    private boolean handleAutoHideTouch(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int action = event.getActionMasked();
        boolean gestureEnds = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;

        if (!isAutoHideActive()) {
            if (gestureEnds) swallowingWakeGesture = false;
            return swallowingWakeGesture;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            swallowingWakeGesture = concealed;
            if (concealed) reveal();
        }

        // Fingers down: never hide. Last finger up: start counting again.
        if (gestureEnds) scheduleConceal();
        else autoHideHandler.removeCallbacks(concealRunnable);

        if (swallowingWakeGesture) {
            if (gestureEnds) swallowingWakeGesture = false;
            return true;
        }
        return false;
    }

    private void scheduleConceal() {
        autoHideHandler.removeCallbacks(concealRunnable);
        if (isAutoHideActive()) autoHideHandler.postDelayed(concealRunnable, AUTO_HIDE_DELAY_MS);
    }

    private void conceal() {
        if (!isAutoHideActive() || concealed || isAnyElementHeld()) return;
        concealed = true;
        animate().cancel();
        animate().alpha(0f).setDuration(AUTO_HIDE_FADE_OUT_MS).start();
    }

    private void reveal() {
        concealed = false;
        animate().cancel();
        animate().alpha(1f).setDuration(AUTO_HIDE_FADE_IN_MS).start();
    }

    // Back to fully shown, then restart the countdown if auto-hide applies. Called whenever
    // something that decides isAutoHideActive() changes.
    private void resetAutoHide() {
        autoHideHandler.removeCallbacks(concealRunnable);
        swallowingWakeGesture = false;
        concealed = false;
        animate().cancel();
        setAlpha(1f);
        scheduleConceal();
    }

    private boolean isAnyElementHeld() {
        if (profile == null) return false;
        for (ControlElement element : profile.getElements()) {
            if (element.getCurrentPointerId() != -1) return true;
        }
        return false;
    }

    private void runOnUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else post(action);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Guard: a super constructor may set visibility before our fields exist.
        if (autoHideHandler != null) runOnUi(this::resetAutoHide);
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                
                if (controllerBinding != null) {
                    int keyCode = event.getKeyCode();
                    if ((keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2)
                            && controller.hasAnalogTriggers()) {
                        // Analog triggers: the axis drives the binding (processTriggerInput); their
                        // digital key would fight it.
                        return true;
                    }
                    int action = event.getAction();

                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controller, controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controller, controllerBinding.getBinding(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(null, binding, isActionDown, 0);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown) {
        handleInputEvent(controller, binding, isActionDown, 0);
    }

    /**
     * Handle stick input with proper 2D axis management.
     * Use this for analog sticks to avoid per-direction axis conflicts.
     */
    public void handleStickInput(Binding firstBinding, float deltaX, float deltaY) {
        if (!firstBinding.isGamepad() || profile == null) return;

        GamepadState state = profile.getGamepadState();
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        
        // Determine which stick this is based on the first binding
        boolean isLeftStick = firstBinding == Binding.GAMEPAD_LEFT_THUMB_UP || 
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_DOWN ||
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_LEFT ||
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_RIGHT;
        
        boolean changed;
        if (isLeftStick) {
            changed = Float.compare(state.thumbLX, deltaX) != 0 ||
                      Float.compare(state.thumbLY, deltaY) != 0;
            state.thumbLX = deltaX;
            state.thumbLY = deltaY;
        } else {
            changed = Float.compare(state.thumbRX, deltaX) != 0 ||
                      Float.compare(state.thumbRY, deltaY) != 0;
            state.thumbRX = deltaX;
            state.thumbRY = deltaY;
        }

        if (changed && winHandler != null) {
            winHandler.sendGamepadState();
        }
    }

    /** All four D-pad directions in one update (one packet, and none if nothing changed). */
    public void handleDPadInput(boolean up, boolean right, boolean down, boolean left) {
        if (profile == null) return;

        GamepadState state = profile.getGamepadState();
        boolean changed = state.dpad[0] != up ||
                          state.dpad[1] != right ||
                          state.dpad[2] != down ||
                          state.dpad[3] != left;
        if (!changed) return;

        state.dpad[0] = up;
        state.dpad[1] = right;
        state.dpad[2] = down;
        state.dpad[3] = left;

        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState();
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(null, binding, isActionDown, offset);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(controller, binding, isActionDown, offset, true);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (binding.isGamepad()) {
            if (profile == null && controller == null) return;

            GamepadState state = (controller != null) ? controller.remappedState : profile.getGamepadState();
            boolean stateChanged = false;

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2) {
                    float value = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                    stateChanged = Float.compare(state.triggerL, value) != 0;
                    state.triggerL = value;
                }
                else if (buttonIdx == ExternalController.IDX_BUTTON_R2) {
                    float value = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                    stateChanged = Float.compare(state.triggerR, value) != 0;
                    state.triggerR = value;
                }
                else {
                    stateChanged = state.isPressed(buttonIdx) != isActionDown;
                    if (stateChanged) state.setPressed(buttonIdx, isActionDown);
                }
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                float value = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_UP ? -val : val) : 0;
                stateChanged = Float.compare(state.thumbLY, value) != 0;
                state.thumbLY = value;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                float value = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT ? -val : val) : 0;
                stateChanged = Float.compare(state.thumbLX, value) != 0;
                state.thumbLX = value;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                float value = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_UP ? -val : val) : 0;
                stateChanged = Float.compare(state.thumbRY, value) != 0;
                state.thumbRY = value;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                float value = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT ? -val : val) : 0;
                stateChanged = Float.compare(state.thumbRX, value) != 0;
                state.thumbRX = value;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                int dpadIndex = binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal();
                stateChanged = state.dpad[dpadIndex] != isActionDown;
                state.dpad[dpadIndex] = isActionDown;
            }

            if (winHandler != null && sendUpdate && stateChanged) {
                if (controller != null)
                    winHandler.sendGamepadState(controller);
                else
                    winHandler.sendGamepadState();
            }
        }
        else {
            if (binding.isMouseMove()) {
                boolean horizontal = binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT;
                int dirBit = binding == Binding.MOUSE_MOVE_UP ? MOUSE_DIR_UP
                        : binding == Binding.MOUSE_MOVE_RIGHT ? MOUSE_DIR_RIGHT
                        : binding == Binding.MOUSE_MOVE_DOWN ? MOUSE_DIR_DOWN : MOUSE_DIR_LEFT;
                if (!isActionDown) {
                    setMouseHeld(dirBit, false);
                    setMouseLegacyAxis(horizontal, 0f);
                }
                else if (offset == 0) setMouseHeld(dirBit, true);
                else {
                    // Analog per-axis source: magnitude from the offset, direction from the
                    // binding (it used to follow the offset's sign, so a binding mapped against
                    // the axis moved the cursor the wrong way).
                    int[] dir = mouseMoveDirection(binding);
                    setMouseLegacyAxis(horizontal, Math.min(Math.abs(offset), 1f) * (horizontal ? dir[0] : dir[1]));
                }
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            int wheelDelta = pointerButton == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (pointerButton == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, true), 0, 0, wheelDelta);
                        } else {
                            xServer.injectPointerButtonPress(pointerButton);
                        }
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, false), 0, 0, 0);
                        } else {
                            xServer.injectPointerButtonRelease(pointerButton);
                        }
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }


    public void invalidateIconCache() {
        icons.clear();
    }

    public Bitmap getIcon(byte id) {
        if (id < 0) return null;
        Bitmap cached = icons.get(id);
        if (cached == null) {
            File overrideFile = new File(
                android.os.Environment.getExternalStorageDirectory(),
                "winlator/custom_icons/override_" + id + ".png"
            );
            if (overrideFile.exists()) {
                cached = BitmapFactory.decodeFile(overrideFile.getAbsolutePath());
                if (cached != null) {
                    android.util.Log.i("Icons", "Using custom override for built-in icon " + id);
                    icons.put(id, cached);
                    return cached;
                }
            }
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                cached = BitmapFactory.decodeStream(is);
                if (cached != null) icons.put(id, cached);
                else android.util.Log.w("Icons", "Built-in icon " + id + " decoded as null");
            }
            catch (IOException e) {
                android.util.Log.w("Icons", "Built-in icon " + id + " not in assets: " + e.getMessage());
            }
        }
        return cached;
    }
    // A finger holding a swipe-enabled control that has slid off it onto another swipe-enabled,
    // currently free control: release the first and press the second, without lifting. Both
    // ends must have Swipeable on (off by default), so existing layouts behave exactly as before.
    // Tells the touchpad which pointers are held by on-screen controls, so those fingers never
    // count as trackpad/touchscreen fingers (e.g. a finger on a stick becoming "the" touch).
    private final java.util.HashSet<Integer> capturedPointerIds = new java.util.HashSet<>();

    private void syncCapturedPointers() {
        if (touchpadView == null) return;
        capturedPointerIds.clear();
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                int id = element.getCurrentPointerId();
                if (id != -1) capturedPointerIds.add(id);
            }
        }
        touchpadView.setPointerIdsToIgnore(capturedPointerIds);
    }

    private void releaseStaleElements() {
        if (profile == null) return;
        for (ControlElement element : profile.getElements()) {
            int id = element.getCurrentPointerId();
            if (id != -1) element.handleTouchUp(id);
        }
        syncCapturedPointers();
    }

    private void forwardToTouchpad(MotionEvent event) {
        syncCapturedPointers();
        touchpadView.onTouchEvent(event);
    }

    private boolean trySwipe(int pointerId, float x, float y) {
        ControlElement source = null;
        for (ControlElement element : profile.getElements()) {
            if (element.isCapturing(pointerId)) {
                source = element;
                break;
            }
        }
        if (source == null || !source.isSwipeEnabled() || source.containsPoint(x, y)) return false;

        for (ControlElement target : profile.getElements()) {
            if (target == source || !target.isSwipeEnabled() || target.isCapturingAnyPointer() || !target.containsPoint(x, y)) continue;
            source.handleTouchUp(pointerId);
            if (target.handleTouchDown(pointerId, x, y)) {
                playTouchHaptic(target.getTouchDownHaptic());
            }
            return true;
        }
        return false;
    }

    // Called by the touch code here and by ControlElement (D-pad direction changes). No-op in
    // the editor and when the sidebar's touchscreen vibration is off.
    public void playTouchHaptic(int kind) {
        if (!touchHapticsEnabled || kind == TouchHaptics.NONE) return;
        if (touchHaptics == null) touchHaptics = new TouchHaptics(getContext());
        touchHaptics.play(kind);
    }

    // ======================= Edit-mode touch: drag, magnets, crosshair =======================

    private void handleEditTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                finishPendingAnimations();
                gestureConsumed = false;
                pinching = false;
                activeEditPointerId = event.getPointerId(0);
                float x = event.getX();
                float y = event.getY();
                downTouchX = x;
                downTouchY = y;
                draggingElement = false;

                ControlElement element = intersectElement(x, y);
                moveCursor = element == null;
                if (element != null) {
                    offsetX = x - element.getX();
                    offsetY = y - element.getY();
                    dragStartX = element.getX();
                    dragStartY = element.getY();
                }
                else {
                    // Crosshair glides to the tap point instead of teleporting there.
                    animateCursorTo(x, y, null);
                }
                selectElement(element);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (gestureConsumed || pinching || moveCursor || selectedElement == null) break;
                if (draggingElement) {
                    // Land the drag first (glide jumps to its end, position is saved).
                    draggingElement = false;
                    settleElement(selectedElement);
                    finishPendingAnimations();
                }
                int newIndex = event.getActionIndex();
                int firstIndex = event.findPointerIndex(activeEditPointerId);
                if (firstIndex < 0) break;
                float distance = (float) Math.hypot(event.getX(newIndex) - event.getX(firstIndex), event.getY(newIndex) - event.getY(firstIndex));
                if (distance < touchSlop) break;
                recordElementSnapshot(selectedElement);
                pinching = true;
                pinchPointerId = event.getPointerId(newIndex);
                pinchStartDistance = distance;
                pinchStartScale = selectedElement.getScale();
                setElementGestureActive(true);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (gestureConsumed) break;
                if (pinching) {
                    updatePinch(event);
                    break;
                }
                int index = event.findPointerIndex(activeEditPointerId);
                if (index < 0) break;
                float x = event.getX(index);
                float y = event.getY(index);

                if (selectedElement != null && !moveCursor) {
                    if (!draggingElement) {
                        if (Math.hypot(x - downTouchX, y - downTouchY) < touchSlop) break;
                        // Re-anchor at the moment the drag starts so the element doesn't jump
                        // by the slop distance.
                        draggingElement = true;
                        offsetX = x - selectedElement.getX();
                        offsetY = y - selectedElement.getY();
                        setElementGestureActive(true);
                    }
                    dragElementTo(x - offsetX, y - offsetY);
                }
                else if (moveCursor) {
                    if (cursorAnimator != null) cursorAnimator.cancel();
                    cursorX = x;
                    cursorY = y;
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int liftedId = event.getPointerId(event.getActionIndex());
                if (pinching && (liftedId == pinchPointerId || liftedId == activeEditPointerId)) {
                    endPinch();
                    break;
                }
                if (gestureConsumed) break;
                // A second finger lifting doesn't end the gesture; the first one lifting does.
                if (liftedId == activeEditPointerId) endEditGesture(event, event.getActionIndex());
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pinching) endPinch();
                if (gestureConsumed) {
                    gestureConsumed = false;
                    activeEditPointerId = -1;
                    break;
                }
                int index = event.findPointerIndex(activeEditPointerId);
                endEditGesture(event, Math.max(index, 0));
                break;
            }
        }
    }

    private void endEditGesture(MotionEvent event, int pointerIndex) {
        if (activeEditPointerId == -1) return;
        activeEditPointerId = -1;

        if (selectedElement != null && draggingElement) {
            draggingElement = false;
            setElementGestureActive(false);
            settleElement(selectedElement);
        }
        else if (moveCursor) {
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            float targetX = Mathf.roundTo(x, snappingSize);
            float targetY = Mathf.roundTo(y, snappingSize);
            cursor.set((int) targetX, (int) targetY);
            animateCursorTo(targetX, targetY, null);
        }
        invalidate();
    }

    private void updatePinch(MotionEvent event) {
        ControlElement element = selectedElement;
        int a = event.findPointerIndex(activeEditPointerId);
        int b = event.findPointerIndex(pinchPointerId);
        if (element == null || a < 0 || b < 0 || pinchStartDistance <= 0) return;
        float distance = (float) Math.hypot(event.getX(b) - event.getX(a), event.getY(b) - event.getY(a));
        float scale = pinchStartScale * distance / pinchStartDistance;
        // 5% steps, same granularity the settings slider effectively lands on.
        scale = Math.round(scale * 20f) / 20f;
        scale = Math.max(MIN_ELEMENT_SCALE, Math.min(MAX_ELEMENT_SCALE, scale));
        if (scale != element.getScale()) {
            element.setScale(scale);
            keepInside(element);
            invalidate();
        }
    }

    private void endPinch() {
        pinching = false;
        pinchPointerId = -1;
        gestureConsumed = true;
        draggingElement = false;
        setElementGestureActive(false);
        if (profile != null) profile.save();
        invalidate();
        notifyElementsChanged();
    }

    private void setElementGestureActive(boolean active) {
        if (elementGestureActive == active) return;
        elementGestureActive = active;
        if (editorListener != null) editorListener.onElementGestureChanged(active);
    }

    private void dragElementTo(float rawX, float rawY) {
        ControlElement element = selectedElement;
        if (element == null || snappingSize <= 0) return;

        Rect box = element.getBoundingBox();
        float halfW = box.width() * 0.5f;
        float halfH = box.height() * 0.5f;

        float prevGuideX = guideLineX, prevGuideY = guideLineY;
        float[] snappedX = snapAxis(element, rawX, halfW, true);
        float[] snappedY = snapAxis(element, rawY, halfH, false);
        guideLineX = snappedX[1];
        guideLineY = snappedY[1];

        // The finger may go anywhere; the element stops at the screen edge. If that moved it off
        // the guide it had snapped to, the guide is not shown.
        float insideX = clampAxis(snappedX[0], halfW, boundsWidth());
        float insideY = clampAxis(snappedY[0], halfH, boundsHeight());
        if (insideX != snappedX[0]) guideLineX = Float.NaN;
        if (insideY != snappedY[0]) guideLineY = Float.NaN;

        element.setX(Math.round(insideX));
        element.setY(Math.round(insideY));

        // Tick when a guide is newly acquired (not on grid lines — they're every cell and
        // would turn into a constant buzz).
        boolean newGuide = (!Float.isNaN(guideLineX) && guideLineX != prevGuideX)
                || (!Float.isNaN(guideLineY) && guideLineY != prevGuideY);
        if (newGuide) snapHaptic();

        invalidate();
    }

    // Returns {position, guideLine (NaN if the axis is on the grid or free)}.
    // Guides win over grid lines; either only applies within its magnet radius, otherwise the
    // axis follows the finger freely.
    private float[] snapAxis(ControlElement dragged, float value, float half, boolean horizontal) {
        float bestDistance = snappingSize * GUIDE_MAGNET;
        float bestPosition = Float.NaN;
        float bestLine = Float.NaN;

        float screenCenter = Mathf.roundTo((horizontal ? getMaxWidth() : getMaxHeight()) * 0.5f, snappingSize);
        float d = Math.abs(value - screenCenter);
        if (d <= bestDistance) {
            bestDistance = d;
            bestPosition = screenCenter;
            bestLine = screenCenter;
        }

        // Screen edges, one grid cell in: my start edge to the leading margin, my end edge to
        // the trailing one.
        float extent = horizontal ? getMaxWidth() : getMaxHeight();
        float edgeMargin = snappingSize;
        d = Math.abs((value - half) - edgeMargin);
        if (d < bestDistance) { bestDistance = d; bestPosition = edgeMargin + half; bestLine = edgeMargin; }
        d = Math.abs((value + half) - (extent - edgeMargin));
        if (d < bestDistance) { bestDistance = d; bestPosition = extent - edgeMargin - half; bestLine = extent - edgeMargin; }

        if (profile != null) {
            for (ControlElement other : profile.getElements()) {
                if (other == dragged) continue;
                Rect box = other.getBoundingBox();
                float center = horizontal ? other.getX() : other.getY();
                float start = horizontal ? box.left : box.top;
                float end = horizontal ? box.right : box.bottom;

                // centre ↔ centre
                d = Math.abs(value - center);
                if (d < bestDistance) { bestDistance = d; bestPosition = center; bestLine = center; }
                // my start edge ↔ their start edge
                d = Math.abs((value - half) - start);
                if (d < bestDistance) { bestDistance = d; bestPosition = start + half; bestLine = start; }
                // my end edge ↔ their end edge
                d = Math.abs((value + half) - end);
                if (d < bestDistance) { bestDistance = d; bestPosition = end - half; bestLine = end; }
            }
        }

        if (!Float.isNaN(bestPosition)) return new float[]{bestPosition, bestLine};

        float grid = Mathf.roundTo(value, snappingSize);
        if (Math.abs(value - grid) <= snappingSize * GRID_MAGNET) return new float[]{grid, Float.NaN};
        return new float[]{value, Float.NaN};
    }

    // On release: an axis held by a guide keeps it, a free axis glides to the nearest grid
    // line. The profile is saved (and undo recorded) only once the glide has finished.
    private void settleElement(final ControlElement element) {
        final float fromX = element.getX();
        final float fromY = element.getY();
        // Rounding to the grid must not carry the element back over the edge.
        Rect settleBox = element.getBoundingBox();
        final float toX = Float.isNaN(guideLineX)
                ? clampAxisToGrid(Mathf.roundTo(fromX, snappingSize), settleBox.width() * 0.5f, boundsWidth())
                : fromX;
        final float toY = Float.isNaN(guideLineY)
                ? clampAxisToGrid(Mathf.roundTo(fromY, snappingSize), settleBox.height() * 0.5f, boundsHeight())
                : fromY;
        final int startX = dragStartX, startY = dragStartY;

        elementSettleAnimator = ValueAnimator.ofFloat(0f, 1f);
        elementSettleAnimator.setDuration(SETTLE_DURATION_MS);
        elementSettleAnimator.setInterpolator(new DecelerateInterpolator());
        elementSettleAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            element.setX(Math.round(fromX + (toX - fromX) * t));
            element.setY(Math.round(fromY + (toY - fromY) * t));
            invalidate();
        });
        elementSettleAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean done = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                finish();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
            }

            private void finish() {
                if (done) return;
                done = true;
                element.setX(Math.round(toX));
                element.setY(Math.round(toY));
                guideLineX = Float.NaN;
                guideLineY = Float.NaN;
                elementSettleAnimator = null;
                if (profile != null) profile.save();
                if (element.getX() != startX || element.getY() != startY) {
                    setUndo(() -> {
                        element.setX(startX);
                        element.setY(startY);
                        profile.save();
                        selectElement(element);
                        notifyElementsChanged();
                    });
                }
                invalidate();
                notifyElementsChanged();
            }
        });
        elementSettleAnimator.start();
    }

    private void animateCursorTo(final float toX, final float toY, Runnable onEnd) {
        if (cursorAnimator != null) cursorAnimator.cancel();
        final float fromX = cursorX;
        final float fromY = cursorY;
        cursorAnimator = ValueAnimator.ofFloat(0f, 1f);
        cursorAnimator.setDuration(SETTLE_DURATION_MS);
        cursorAnimator.setInterpolator(new DecelerateInterpolator());
        cursorAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            cursorX = fromX + (toX - fromX) * t;
            cursorY = fromY + (toY - fromY) * t;
            invalidate();
        });
        cursorAnimator.start();
    }

    // Jumps any running glide to its end state (saving the settled element) before a new
    // gesture or an add/remove/duplicate/undo acts on the canvas.
    private void finishPendingAnimations() {
        if (elementSettleAnimator != null) elementSettleAnimator.end();
        if (cursorAnimator != null) cursorAnimator.end();
    }

    private void snapHaptic() {
        int constant = Build.VERSION.SDK_INT >= 34
                ? HapticFeedbackConstants.SEGMENT_TICK
                : HapticFeedbackConstants.CLOCK_TICK;
        performHapticFeedback(constant);
    }
}
