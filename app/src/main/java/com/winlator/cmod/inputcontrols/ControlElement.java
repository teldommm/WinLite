package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;

import androidx.core.graphics.ColorUtils;

import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.MotionAccumulator;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchHaptics;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 2.0f;
    public static final float STICK_CROSS_ZONE = 0.3f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    // Slide-between-buttons (a finger sliding off this control onto another swipe-enabled one
    // presses that one without lifting). Off by default for every element, including all
    // existing profiles: it is only written to / read from JSON when turned on.
    private boolean swipeable = false;
    private float opacity = 1.0f;
    private int customColor = 0; 
    private ColorFilter customColorFilter = null;
    private ColorFilter themeColorFilter = null;
    private int themeColorFilterColor = 1; 
    private boolean mouseMoveMode = false;
    private String customIconPath = null;
    private Bitmap customIconBitmap = null;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private final MotionAccumulator trackpadMotion = new MotionAccumulator();
    // Trackpad with mouse bindings: same velocity-based acceleration as the free touch area.
    private com.winlator.cmod.math.PointerAccel trackpadAccel;
    // Trackpad with gamepad bindings (camera): finger velocity (dp/s, smoothed) drives the stick,
    // and a per-frame tick eases it back to centre once the finger stops without lifting.
    public static final float TRACKPAD_FULL_SPEED_DP = 300f;
    private static final float TRACKPAD_STILL_S = 0.03f;
    private static final float TRACKPAD_DECAY_TAU_S = 0.05f;
    private float trackpadVelX, trackpadVelY;
    private long trackpadLastMoveMs;
    private long trackpadLastTickMs;
    private boolean trackpadTickScheduled;
    private final Runnable trackpadTick = this::onTrackpadStickTick;

    // ---------- Dynamic stick (STICK only, off by default) ----------
    // The stick has a fixed square zone around its home position. A touch in the zone but off
    // the stick spawns the stick under the finger; a finger that leaves the stick's radius drags
    // the stick along (finger stays on the rim). Both are bounded by the zone. On release the
    // stick is back home immediately. Home = the saved x/y, which is never modified at runtime,
    // so nothing of this leaks into the profile.
    public static final float DEFAULT_ZONE_SCALE = 3.0f;
    public static final float MIN_ZONE_SCALE = 1.5f;
    public static final float MAX_ZONE_SCALE = 5.0f;
    private boolean dynamicStick = false;
    // Zone side as a multiple of the stick's diameter, so it follows Scale.
    private float zoneScale = DEFAULT_ZONE_SCALE;
    // Runtime centre while a dynamic stick is held away from home.
    private boolean anchorActive = false;
    private float anchorX, anchorY;

    // Follow tuning. followSpeed 0..1 (per element, shown as 0–100%) sets both:
    //  - slack: how far past the stick's radius the finger may go before the stick starts to
    //    move (1.6× radius at 0 … 1.15× at 1). Output is already maxed at the rim, so the slack
    //    only absorbs overshoot; it doesn't cost any control.
    //  - smoothing: the stick glides towards its target with a time-based exponential ease
    //    (time constant 160 ms at 0 … 40 ms at 1), independent of the touch sample rate.
    public static final float DEFAULT_FOLLOW_SPEED = 0.5f;
    private float followSpeed = DEFAULT_FOLLOW_SPEED;
    private float anchorTargetX, anchorTargetY;
    private long lastAnchorNanos;
    private float lastFingerX, lastFingerY;
    private boolean anchorTickScheduled = false;
    // Keeps the glide going while the finger holds still (no MOVE events arrive then).
    private final Runnable anchorTick = () -> {
        anchorTickScheduled = false;
        if (currentPointerId != -1 && anchorActive) handleTouchMove(currentPointerId, lastFingerX, lastFingerY);
    };
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.STICK) {
            bindings[0] = Binding.KEY_W;
            bindings[1] = Binding.KEY_D;
            bindings[2] = Binding.KEY_S;
            bindings[3] = Binding.KEY_A;
        }
        else if(type == Type.D_PAD){
            bindings[0] = Binding.GAMEPAD_DPAD_UP;
            bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        }
        else if (type == Type.TRACKPAD) {
            bindings[0] = Binding.GAMEPAD_RIGHT_THUMB_UP;
            bindings[1] = Binding.GAMEPAD_RIGHT_THUMB_RIGHT;
            bindings[2] = Binding.GAMEPAD_RIGHT_THUMB_DOWN;
            bindings[3] = Binding.GAMEPAD_RIGHT_THUMB_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }

        text = "";
        iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public boolean isMouseMoveMode() {
        return mouseMoveMode;
    }

    public boolean isDynamicStick() {
        return dynamicStick && type == Type.STICK;
    }

    public boolean getDynamicStickFlag() {
        return dynamicStick;
    }

    public void setDynamicStick(boolean dynamicStick) {
        this.dynamicStick = dynamicStick;
        anchorActive = false;
    }

    public float getFollowSpeed() {
        return followSpeed;
    }

    public void setFollowSpeed(float followSpeed) {
        this.followSpeed = Math.max(0f, Math.min(1f, followSpeed));
    }

    public float getZoneScale() {
        return zoneScale;
    }

    public void setZoneScale(float zoneScale) {
        this.zoneScale = Math.max(MIN_ZONE_SCALE, Math.min(MAX_ZONE_SCALE, zoneScale));
    }

    // The zone square, centred on the stick's home position and cut to the screen.
    public RectF getDynamicZone() {
        Rect box = getBoundingBox();
        float half = box.width() * 0.5f * zoneScale;
        float cx = box.centerX();
        float cy = box.centerY();
        return new RectF(
                Math.max(0, cx - half),
                Math.max(0, cy - half),
                Math.min(inputControlsView.getMaxWidth(), cx + half),
                Math.min(inputControlsView.getMaxHeight(), cy + half)
        );
    }

    // Second-pass touch-down used by InputControlsView only when no element took the pointer
    // by its own bounds — so buttons sitting inside a stick's zone always win.
    public boolean handleDynamicZoneTouchDown(int pointerId, float x, float y) {
        if (!isDynamicStick() || currentPointerId != -1 || inputControlsView.isEditMode()) return false;
        if (!getDynamicZone().contains(x, y)) return false;
        currentPointerId = pointerId;
        moveAnchorTo(x, y);
        return handleTouchMove(pointerId, x, y);
    }

    // Places the stick immediately (spawn under the finger), clamped into the zone.
    private void moveAnchorTo(float cx, float cy) {
        anchorX = clampAnchorX(cx);
        anchorY = clampAnchorY(cy);
        anchorTargetX = anchorX;
        anchorTargetY = anchorY;
        lastAnchorNanos = System.nanoTime();
        anchorActive = true;
    }

    // Keeps the whole stick inside the zone (the home centre is always allowed, even when the
    // zone is cut by the screen edge).
    private float clampAnchorX(float cx) {
        Rect box = getBoundingBox();
        float radius = box.width() * 0.5f;
        RectF zone = getDynamicZone();
        float home = box.centerX();
        return Math.max(Math.min(zone.left + radius, home), Math.min(Math.max(zone.right - radius, home), cx));
    }

    private float clampAnchorY(float cy) {
        Rect box = getBoundingBox();
        float radius = box.width() * 0.5f;
        RectF zone = getDynamicZone();
        float home = box.centerY();
        return Math.max(Math.min(zone.top + radius, home), Math.min(Math.max(zone.bottom - radius, home), cy));
    }

    // One follow step for the finger at (fx, fy): retarget if it is beyond the slack distance,
    // then ease the stick towards the target by the time elapsed since the previous step.
    private void updateDynamicAnchor(float fx, float fy, float radius) {
        lastFingerX = fx;
        lastFingerY = fy;
        long now = System.nanoTime();
        if (!anchorActive) {
            Rect box = getBoundingBox();
            anchorX = anchorTargetX = box.centerX();
            anchorY = anchorTargetY = box.centerY();
            lastAnchorNanos = now;
            anchorActive = true;
        }

        float slack = radius * (1.6f - 0.45f * followSpeed);
        float dx = fx - anchorX;
        float dy = fy - anchorY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance > slack) {
            float pull = (distance - slack) / distance;
            anchorTargetX = clampAnchorX(anchorX + dx * pull);
            anchorTargetY = clampAnchorY(anchorY + dy * pull);
        } else {
            // Finger came back inside: stop where the stick is.
            anchorTargetX = anchorX;
            anchorTargetY = anchorY;
        }

        float dtMs = Math.min(50f, (now - lastAnchorNanos) / 1_000_000f);
        lastAnchorNanos = now;
        float tauMs = 160f - 120f * followSpeed;
        float alpha = 1f - (float) Math.exp(-dtMs / tauMs);
        anchorX += (anchorTargetX - anchorX) * alpha;
        anchorY += (anchorTargetY - anchorY) * alpha;

        if (Math.hypot(anchorTargetX - anchorX, anchorTargetY - anchorY) > 0.5f) {
            if (!anchorTickScheduled) {
                anchorTickScheduled = true;
                inputControlsView.postOnAnimation(anchorTick);
            }
        }
    }

    private float stickCenterX(Rect box) {
        return anchorActive ? anchorX : box.centerX();
    }

    private float stickCenterY(Rect box) {
        return anchorActive ? anchorY : box.centerY();
    }

    public boolean isSwipeable() {
        return swipeable;
    }

    public void setSwipeable(boolean swipeable) {
        this.swipeable = swipeable;
    }

    // Whether this element takes part in sliding right now. Toggle switches and relative-mouse
    // buttons are left out even when the flag is on: sliding off a toggle would flip it, and a
    // relative-mouse button uses the finger's movement itself.
    public boolean isSwipeEnabled() {
        if (!swipeable) return false;
        if (type == Type.D_PAD) return true;
        return type == Type.BUTTON && !toggleSwitch && !mouseMoveMode;
    }

    // Haptic weight for the moment this element takes a finger. D-pads return NONE: they pulse
    // per direction from handleTouchMove (a press from rest, a lighter tick when rolling to
    // another direction), so a touch in the D-pad's dead centre doesn't click. Sticks (fixed
    // and dynamic) and trackpads are deliberately silent: continuous input, like a real
    // thumbstick.
    public int getTouchDownHaptic() {
        switch (type) {
            case D_PAD:
            case STICK:
            case TRACKPAD:
                return TouchHaptics.NONE;
            default:
                return TouchHaptics.PRESS;
        }
    }

    public boolean isCapturing(int pointerId) {
        return pointerId != -1 && currentPointerId == pointerId;
    }

    public int getCurrentPointerId() {
        return currentPointerId;
    }

    public boolean isCapturingAnyPointer() {
        return currentPointerId != -1;
    }

    public void setMouseMoveMode(boolean mouseMoveMode) {
        this.mouseMoveMode = mouseMoveMode;
    }

    public String getCustomIconPath() {
        return customIconPath;
    }

    public void setCustomIconPath(String path) {
        if (customIconBitmap != null) {
            customIconBitmap.recycle();
            customIconBitmap = null;
        }
        this.customIconPath = path;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public int getCustomColor() {
        return customColor;
    }

    public void setCustomColor(int customColor) {
        this.customColor = customColor;
        this.customColorFilter = customColor != 0 ? new PorterDuffColorFilter(customColor, PorterDuff.Mode.SRC_IN) : null;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 8;
                halfHeight = snappingSize * 8;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }

    private String getBindingTextAt(int index) {
        Binding binding = getBindingAt(index);
        String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (text.length() > 7) {
            String[] parts = text.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) sb.append(part.charAt(0));
            return (binding.isMouse() ? "M" : "")+ sb;
        }
        else return text;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        else if (type == Type.BUTTON) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bindings.length; i++) {
                if (bindings[i] != Binding.NONE) {
                    if (sb.length() > 0) sb.append("+");
                    sb.append(getBindingTextAt(i));
                }
            }
            return sb.length() > 0 ? sb.toString() : getBindingTextAt(0);
        }
        else return getBindingTextAt(0);
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    private static Binding getRangeBindingForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z:
                return Binding.valueOf("KEY_"+((char)(65 + index)));
            case FROM_0_TO_9:
                return Binding.valueOf("KEY_"+((index + 1) % 10));
            case FROM_F1_TO_F12:
                return Binding.valueOf("KEY_F"+(index + 1));
            case FROM_NP0_TO_NP9:
                return Binding.valueOf("KEY_KP_"+((index + 1) % 10));
            default:
                return Binding.NONE;
        }
    }

    private static int rgba(int a, int r, int g, int b) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    
    
    
    private float getEffectiveOpacity() {
        float combined = opacity * inputControlsView.getOverlayOpacity();
        return inputControlsView.isEditMode() ? Math.max(0.35f, combined) : combined;
    }

    
    
    
    private int withAlpha(int color, int alpha) {
        int scaledAlpha = (int)(alpha * getEffectiveOpacity());
        if (scaledAlpha < 0) scaledAlpha = 0;
        if (scaledAlpha > 255) scaledAlpha = 255;
        return (color & 0x00ffffff) | ((scaledAlpha & 0xff) << 24);
    }

    private static final int WINLATOR_BLUE = 0xff2184ff;
    // Neutral greys (the old surface/edge were blue-tinted): the controls read as part of the
    // app's dark theme, and the profile's theme colour is the only colour left on them.
    private static final int DARK_SURFACE = 0xff0a0a0b;
    private static final int EDGE_SOFT = 0xffc4c4c8;

    
    
    private int getThemeColor() {
        if (customColor != 0) return customColor;
        ControlsProfile profile = inputControlsView.getProfile();
        int schemeColor = profile != null ? profile.getThemeColor() : 0;
        return schemeColor != 0 ? schemeColor : 0xffffffff;
    }

    private boolean isVisuallyPressed() {
        return selected || currentPointerId != -1;
    }

    private Rect expandedBounds(Rect rect, int padding) {
        return new Rect(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding);
    }

    private void invalidateSelf() {
        Rect rect = getBoundingBox();
        int pad = Math.max(4, inputControlsView.getSnappingSize() * 2);
        if (type == Type.STICK) pad = Math.max(pad, rect.width() / 4);
        
        inputControlsView.invalidateElement(expandedBounds(rect, pad));
    }

    private void setupPaint(Paint paint, Paint.Style style, int color, float strokeWidth) {
        paint.setStyle(style);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColorFilter(null);
        paint.setShader(null);
        paint.setAntiAlias(true);
    }

    // Outline brighter at the bottom than at the top: a soft light from below, as on the reference
    // controls. The paint's shader is cleared by the next setupPaint().
    private void useEdgeGradient(Paint paint, float top, float bottom, int topAlpha, int bottomAlpha) {
        paint.setColor(0xff000000);
        paint.setShader(new LinearGradient(0, top, 0, bottom,
                withAlpha(EDGE_SOFT, topAlpha), withAlpha(EDGE_SOFT, bottomAlpha), Shader.TileMode.CLAMP));
    }

    private void drawCenteredText(Canvas canvas, String text, float cx, float cy, float maxWidth, float maxSize, int color) {
        Paint paint = inputControlsView.getPaint();
        int alpha = (color >>> 24) & 0xff;
        setupPaint(paint, Paint.Style.FILL, withAlpha(color, alpha), 0);
        paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, maxWidth), maxSize));
        canvas.drawText(text, cx, cy - ((paint.descent() + paint.ascent()) * 0.5f), paint);
    }

    private int colorForText(String text) {
        return getThemeColor();
    }

    private void drawSoftRoundRect(Canvas canvas, float left, float top, float right, float bottom, float radius, boolean active) {
        Paint paint = inputControlsView.getPaint();
        float unit = inputControlsView.getSnappingSize() * scale;

        setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 64 : 16), Math.max(1f, unit * 0.55f));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

        setupPaint(paint, Paint.Style.FILL, active ? withAlpha(getThemeColor(), 190) : withAlpha(DARK_SURFACE, 135), 0);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

        // Soft glow just inside the edge: three hairlines stepping inwards and fading out.
        float glow = Math.max(1f, unit * 0.30f);
        int[] glowAlpha = active ? new int[] {46, 26, 12} : new int[] {30, 16, 7};
        for (int i = 0; i < 3; i++) {
            float inset = glow * (i + 0.5f) + Math.max(1f, unit * 0.12f);
            setupPaint(paint, Paint.Style.STROKE, withAlpha(0xffffffff, glowAlpha[i]), glow);
            canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, Math.max(0, radius - inset), Math.max(0, radius - inset), paint);
        }

        // The outline on top, brighter along the bottom.
        setupPaint(paint, Paint.Style.STROKE, 0xff000000, Math.max(1f, unit * 0.24f));
        useEdgeGradient(paint, top, bottom, active ? 70 : 34, active ? 140 : 92);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setShader(null);
    }

    private void drawSoftCircle(Canvas canvas, float cx, float cy, float radius, boolean active, int accentColor, boolean fillActive) {
        Paint paint = inputControlsView.getPaint();
        float unit = inputControlsView.getSnappingSize() * scale;

        setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 72 : 18), Math.max(1f, unit * 0.55f));
        canvas.drawCircle(cx, cy, radius, paint);

        setupPaint(paint, Paint.Style.FILL, active && fillActive ? withAlpha(accentColor, 190) : withAlpha(DARK_SURFACE, 140), 0);
        canvas.drawCircle(cx, cy, radius, paint);

        float glow = Math.max(1f, unit * 0.30f);
        int[] glowAlpha = active ? new int[] {46, 26, 12} : new int[] {30, 16, 7};
        for (int i = 0; i < 3; i++) {
            float inset = glow * (i + 0.5f) + Math.max(1f, unit * 0.12f);
            setupPaint(paint, Paint.Style.STROKE, withAlpha(0xffffffff, glowAlpha[i]), glow);
            canvas.drawCircle(cx, cy, Math.max(0, radius - inset), paint);
        }

        setupPaint(paint, Paint.Style.STROKE, 0xff000000, Math.max(1f, unit * 0.22f));
        useEdgeGradient(paint, cy - radius, cy + radius, active ? 74 : 36, active ? 140 : 96);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    private void drawTriangle(Canvas canvas, float cx, float cy, float size, int direction, int color) {
        Paint paint = inputControlsView.getPaint();
        Path path = inputControlsView.getPath();
        path.reset();

        if (direction == 0) { 
            path.moveTo(cx, cy - size);
            path.lineTo(cx - size * 0.85f, cy + size * 0.65f);
            path.lineTo(cx + size * 0.85f, cy + size * 0.65f);
        }
        else if (direction == 1) { 
            path.moveTo(cx + size, cy);
            path.lineTo(cx - size * 0.65f, cy - size * 0.85f);
            path.lineTo(cx - size * 0.65f, cy + size * 0.85f);
        }
        else if (direction == 2) { 
            path.moveTo(cx, cy + size);
            path.lineTo(cx - size * 0.85f, cy - size * 0.65f);
            path.lineTo(cx + size * 0.85f, cy - size * 0.65f);
        }
        else { 
            path.moveTo(cx - size, cy);
            path.lineTo(cx + size * 0.65f, cy - size * 0.85f);
            path.lineTo(cx + size * 0.65f, cy + size * 0.85f);
        }
        path.close();

        setupPaint(paint, Paint.Style.FILL, color, 0);
        canvas.drawPath(path, paint);
    }

    private void drawDPadPiece(Canvas canvas, float cx, float cy, float size, int direction, boolean active) {
        float radius = size * 0.28f;
        drawSoftRoundRect(canvas, cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f, radius, active);
        drawTriangle(canvas, cx, cy, size * 0.22f, direction, active ? withAlpha(0xffffffff, 255) : withAlpha(getThemeColor(), 255));
    }

    private void drawMouseGlyph(Canvas canvas, float cx, float cy, float size, int color) {
        Paint paint = inputControlsView.getPaint();
        float w = size * 0.42f;
        float h = size * 0.62f;
        float r = w * 0.5f;
        setupPaint(paint, Paint.Style.STROKE, color, Math.max(1f, size * 0.035f));
        canvas.drawRoundRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, r, r, paint);
        canvas.drawLine(cx, cy - h * 0.48f, cx, cy - h * 0.15f, paint);
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        Rect boundingBox = getBoundingBox();
        float strokeWidth = Math.max(1f, snappingSize * 0.16f * scale);
        boolean active = isVisuallyPressed();

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float w = boundingBox.width();
                float h = boundingBox.height();

                String label = getDisplayText();
                int accent = colorForText(label);
                boolean faceButton = "A".equalsIgnoreCase(label) || "B".equalsIgnoreCase(label) || "X".equalsIgnoreCase(label) || "Y".equalsIgnoreCase(label);

                switch (shape) {
                    case CIRCLE: {
                        
                        drawSoftCircle(canvas, cx, cy, w * 0.5f, active, accent, true);
                        break;
                    }
                    // RECT and ROUND_RECT used to share one branch (both drawn as a pill), so
                    // switching between them changed nothing. RECT is now a rectangle with the
                    // same small corner radius as SQUARE; ROUND_RECT keeps the pill shape.
                    case RECT: {
                        float radius = snappingSize * 1.05f * scale;
                        drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                        break;
                    }
                    case ROUND_RECT: {
                        float radius = h * 0.45f;
                        drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                        break;
                    }
                    case SQUARE: {
                        float radius = snappingSize * 1.05f * scale;
                        drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                        break;
                    }
                }

                if (iconId > 0 || customIconPath != null) {
                    drawIcon(canvas, cx, cy, w, h, iconId);
                }
                else {
                    int textColor = active ? 0xffffffff : accent;
                    drawCenteredText(canvas, label, cx, cy, w * 0.55f, snappingSize * 2.1f * scale, textColor);
                }
                break;
            }

            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                
                
                
                
                float base = Math.min(boundingBox.width(), boundingBox.height());
                float piece = base * 0.30f;       
                float dist = piece * 1.15f;       
                float centerSize = piece * 0.26f; 

                
                setupPaint(paint, Paint.Style.FILL, withAlpha(DARK_SURFACE, active ? 72 : 42), 0);
                canvas.drawRoundRect(
                        cx - centerSize * 0.5f,
                        cy - centerSize * 0.5f,
                        cx + centerSize * 0.5f,
                        cy + centerSize * 0.5f,
                        centerSize * 0.22f,
                        centerSize * 0.22f,
                        paint
                );

                // Pieces light up per pressed direction, so the element-wide `active` flag never
                // reached them and a D-pad selected in the editor looked unselected. In the editor
                // a selected D-pad lights all four pieces, like other elements' pressed look.
                boolean editorSelected = selected && inputControlsView.isEditMode();
                drawDPadPiece(canvas, cx, cy - dist, piece, 0, editorSelected || (states.length > 0 && states[0]));
                drawDPadPiece(canvas, cx + dist, cy, piece, 1, editorSelected || (states.length > 1 && states[1]));
                drawDPadPiece(canvas, cx, cy + dist, piece, 2, editorSelected || (states.length > 2 && states[2]));
                drawDPadPiece(canvas, cx - dist, cy, piece, 3, editorSelected || (states.length > 3 && states[3]));
                break;
            }

            case RANGE_BUTTON: {
                Range range = getRange();
                float radius = snappingSize * 0.9f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);

                canvas.save();
                path.addRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                canvas.clipPath(path);

                
                
                boolean isPressingSegment = currentPointerId != -1 && !scroller.isScrolling();
                Binding pressedBinding = scroller.getBinding();

                if (orientation == 0) {
                    float startX = boundingBox.left - scrollOffset % elementSize;
                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        String text = getRangeTextForIndex(range, index);
                        boolean segmentPressed = isPressingSegment && pressedBinding == getRangeBindingForIndex(range, index);
                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            if (segmentPressed) {
                                setupPaint(paint, Paint.Style.FILL, withAlpha(getThemeColor(), 150), 0);
                                canvas.drawRect(startX, boundingBox.top, startX + elementSize, boundingBox.bottom, paint);
                            }
                            drawCenteredText(canvas, text, startX + elementSize * 0.5f, y, elementSize - strokeWidth * 2, minTextSize, segmentPressed ? 0xffffffff : getThemeColor());
                        }
                        startX += elementSize;
                    }
                }
                else {
                    float startY = boundingBox.top - scrollOffset % elementSize;
                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        String text = getRangeTextForIndex(range, i);
                        boolean segmentPressed = isPressingSegment && pressedBinding == getRangeBindingForIndex(range, i);
                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            if (segmentPressed) {
                                setupPaint(paint, Paint.Style.FILL, withAlpha(getThemeColor(), 150), 0);
                                canvas.drawRect(boundingBox.left, startY, boundingBox.right, startY + elementSize, paint);
                            }
                            drawCenteredText(canvas, text, x, startY + elementSize * 0.5f, boundingBox.width() - strokeWidth * 2, minTextSize, segmentPressed ? 0xffffffff : getThemeColor());
                        }
                        startY += elementSize;
                    }
                }
                canvas.restore();
                break;
            }

            case STICK: {
                // Runtime centre: home, or where a dynamic stick currently is.
                float cx = stickCenterX(boundingBox);
                float cy = stickCenterY(boundingBox);
                float outer = boundingBox.height() * 0.5f;
                float innerRing = outer * 0.56f;
                float thumbRadius = outer * 0.42f;

                // Base: a radial gradient, nearly black in the middle and lighter towards the rim.
                setupPaint(paint, Paint.Style.FILL, 0xff000000, 0);
                paint.setShader(new RadialGradient(cx, cy, outer,
                        new int[] {
                                withAlpha(0xff020202, active ? 150 : 120),
                                withAlpha(0xff060606, active ? 155 : 125),
                                withAlpha(0xff2a2a2d, active ? 190 : 150)
                        },
                        new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, outer, paint);

                // Rim: a hairline, brighter along the bottom (same light as the buttons).
                setupPaint(paint, Paint.Style.STROKE, 0xff000000, Math.max(1f, snappingSize * 0.18f * scale));
                useEdgeGradient(paint, cy - outer, cy + outer, active ? 70 : 34, active ? 130 : 88);
                canvas.drawCircle(cx, cy, outer * 0.985f, paint);
                paint.setShader(null);

                // One thin ring in the profile colour marks the travel limit - fine and quiet, no
                // longer the heavy coloured ring it used to be.
                setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 150 : 95), Math.max(1f, snappingSize * 0.09f * scale));
                canvas.drawCircle(cx, cy, innerRing, paint);

                // Thumb follows the finger only while held; otherwise it sits in the centre. (The
                // cached position used to stay behind when the stick was moved in the editor.)
                boolean held = currentPointerId != -1 && currentPosition != null;
                float thumbstickX = held ? currentPosition.x : cx;
                float thumbstickY = held ? currentPosition.y : cy;

                // Thumb: a flat grey disc, a touch lighter at the edge; it brightens while held.
                int core = active ? 0xff626266 : 0xff4d4d50;
                int edge = active ? 0xff787880 : 0xff5f5f64;
                setupPaint(paint, Paint.Style.FILL, 0xff000000, 0);
                paint.setShader(new RadialGradient(thumbstickX, thumbstickY, thumbRadius,
                        new int[] {withAlpha(core, 225), withAlpha(core, 225), withAlpha(edge, 235)},
                        new float[] {0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                setupPaint(paint, Paint.Style.STROKE, withAlpha(0xffffffff, active ? 70 : 38), Math.max(1f, snappingSize * 0.10f * scale));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius * 0.97f, paint);
                break;
            }

            case TRACKPAD: {
                
                float radius = boundingBox.height() * 0.18f;
                drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                drawMouseGlyph(canvas, boundingBox.centerX(), boundingBox.centerY(), Math.min(boundingBox.width(), boundingBox.height()) * 0.45f, withAlpha(getThemeColor(), active ? 220 : 95));
                break;
            }
        }
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Paint paint = inputControlsView.getPaint();

        Bitmap icon = null;
        boolean isCustom = false;
        if (customIconPath != null) {
            if (customIconBitmap == null || customIconBitmap.isRecycled()) {
                try {
                    customIconBitmap = android.graphics.BitmapFactory.decodeFile(customIconPath);
                } catch (Exception ignored) {}
            }
            if (customIconBitmap != null) {
                icon = customIconBitmap;
                isCustom = true;
            }
        }
        if (icon == null) {
            icon = inputControlsView.getIcon((byte)iconId);
        }
        if (icon == null) return;

        paint.setColor(0xffffffff);
        int iconAlpha = (int)(230 * getEffectiveOpacity());
        paint.setAlpha(Math.max(0, Math.min(255, iconAlpha)));

        if (!isCustom) {
            if (customColorFilter != null) {
                paint.setColorFilter(customColorFilter);
            } else {
                int themeColor = getThemeColor();
                if (themeColorFilter == null || themeColorFilterColor != themeColor) {
                    themeColorFilter = new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
                    themeColorFilterColor = themeColor;
                }
                paint.setColorFilter(themeColorFilter);
            }
        }

        int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 1.65f : 0.85f) * scale);
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);
            if (opacity != 1.0f) elementJSONObject.put("opacity", opacity);
            if (customColor != 0) elementJSONObject.put("customColor", customColor);
            if (mouseMoveMode) elementJSONObject.put("mouseMoveMode", true);
            if (swipeable) elementJSONObject.put("swipeable", true);
            if (dynamicStick) elementJSONObject.put("dynamicStick", true);
            // Tuning is kept even while the switch is off (only non-default values are written),
            // so toggling Dynamic Stick off and on again doesn't reset Zone Size / Follow Speed.
            if (zoneScale != DEFAULT_ZONE_SCALE) elementJSONObject.put("zoneScale", Float.valueOf(zoneScale));
            if (followSpeed != DEFAULT_FOLLOW_SPEED) elementJSONObject.put("followSpeed", Float.valueOf(followSpeed));
            if (customIconPath != null) elementJSONObject.put("customIconPath", customIconPath);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    
    
    private void setButtonBindingsActive(boolean active) {
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != Binding.NONE) inputControlsView.handleInputEvent(bindings[i], active);
        }
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            invalidateSelf();
            if (type == Type.BUTTON) {
                if (mouseMoveMode) {
                    inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_DOWN);
                    invalidateSelf();
                    return true;
                }
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!toggleSwitch || !selected) {
                    setButtonBindingsActive(true);
                }
                invalidateSelf();
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                invalidateSelf();
                return true;
            }
            else {
                if (type == Type.TRACKPAD) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                    trackpadMotion.reset();
                    if (trackpadAccel == null) trackpadAccel = new com.winlator.cmod.math.PointerAccel(screenDensity());
                    trackpadAccel.reset(android.os.SystemClock.uptimeMillis());
                    trackpadVelX = trackpadVelY = 0;
                    trackpadLastMoveMs = 0;
                }
                invalidateSelf();
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    private float screenDensity() {
        return inputControlsView.getResources().getDisplayMetrics().density;
    }

    /** Trackpad (gamepad bindings): current velocity -> radial stick deflection. */
    private void sendTrackpadStick(Binding firstBinding) {
        if (interpolator == null) interpolator = new CubicBezierInterpolator();
        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
        float speed = (float) Math.hypot(trackpadVelX, trackpadVelY);
        float outX = 0f, outY = 0f;
        if (speed > 0.001f) {
            float m = interpolator.getInterpolation(Math.min(1f, speed / TRACKPAD_FULL_SPEED_DP));
            outX = trackpadVelX / speed * m;
            outY = trackpadVelY / speed * m;
        }
        inputControlsView.handleStickInput(firstBinding, outX, outY);
    }

    private void scheduleTrackpadTick() {
        if (trackpadTickScheduled) return;
        trackpadTickScheduled = true;
        trackpadLastTickMs = android.os.SystemClock.uptimeMillis();
        inputControlsView.postOnAnimation(trackpadTick);
    }

    // Per frame while the trackpad is held: once no move has arrived for TRACKPAD_STILL_S, the
    // velocity decays exponentially and the stick eases back to centre.
    private void onTrackpadStickTick() {
        trackpadTickScheduled = false;
        if (currentPointerId == -1 || type != Type.TRACKPAD) return;
        Binding firstBinding = getBindingAt(0);
        if (!firstBinding.isGamepad()) return;
        long now = android.os.SystemClock.uptimeMillis();
        float tickDt = (now - trackpadLastTickMs) / 1000f;
        trackpadLastTickMs = now;
        if ((now - trackpadLastMoveMs) / 1000f > TRACKPAD_STILL_S) {
            float k = (float) Math.exp(-tickDt / TRACKPAD_DECAY_TAU_S);
            trackpadVelX *= k;
            trackpadVelY *= k;
            if (Math.hypot(trackpadVelX, trackpadVelY) < 5f) {
                trackpadVelX = trackpadVelY = 0;
                inputControlsView.handleStickInput(firstBinding, 0f, 0f);
                return;
            }
            sendTrackpadStick(firstBinding);
        }
        trackpadTickScheduled = true;
        inputControlsView.postOnAnimation(trackpadTick);
    }

    /**
     * Feeds this stick / D-pad's MOUSE_MOVE bindings as one 2D source. (x, y) is the signed
     * deflection; each component picks the binding for its direction (UP/RIGHT/DOWN/LEFT slots),
     * and the cursor moves the way that binding says.
     */
    private void applyMouseMoveBindings(float x, float y) {
        float outX = 0f, outY = 0f;
        if (x != 0f) {
            Binding b = getBindingAt(x > 0 ? 1 : 3);
            if (b.isMouseMove()) {
                int[] dir = InputControlsView.mouseMoveDirection(b);
                outX += dir[0] * Math.abs(x);
                outY += dir[1] * Math.abs(x);
            }
        }
        if (y != 0f) {
            Binding b = getBindingAt(y > 0 ? 2 : 0);
            if (b.isMouseMove()) {
                int[] dir = InputControlsView.mouseMoveDirection(b);
                outX += dir[0] * Math.abs(y);
                outY += dir[1] * Math.abs(y);
            }
        }
        inputControlsView.setStickMouse(this, Mathf.clamp(outX, -1, 1), Mathf.clamp(outY, -1, 1));
    }

    // A D-pad bound to the four gamepad D-pad directions reports through one call (one packet
    // per change) instead of four separate binding events.
    private boolean usesNativeGamepadDPad() {
        return type == Type.D_PAD &&
               getBindingAt(0) == Binding.GAMEPAD_DPAD_UP &&
               getBindingAt(1) == Binding.GAMEPAD_DPAD_RIGHT &&
               getBindingAt(2) == Binding.GAMEPAD_DPAD_DOWN &&
               getBindingAt(3) == Binding.GAMEPAD_DPAD_LEFT;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && type == Type.BUTTON && mouseMoveMode) {
            inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_MOVE);
            return true;
        }
        else if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            // Trackpad only: this move in physical screen pixels (for speed / acceleration).
            float rawDx = 0f, rawDy = 0f;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                rawDx = x - currentPosition.x;
                rawDy = y - currentPosition.y;
                currentPosition.set(x, y);
            }
            else if (type == Type.STICK) {
                float cx = stickCenterX(boundingBox);
                float cy = stickCenterY(boundingBox);
                float offsetX = x - cx;
                float offsetY = y - cy;

                if (isDynamicStick() && !inputControlsView.isEditMode()) {
                    // Follow with slack + smoothing (see followSpeed). While the stick lags
                    // behind, the finger is past the rim and the output simply stays maxed.
                    updateDynamicAnchor(x, y, radius);
                    cx = anchorX;
                    cy = anchorY;
                    offsetX = x - cx;
                    offsetY = y - cy;
                }

                float distanceSquared = offsetX * offsetX + offsetY * offsetY;
                if (distanceSquared > radius * radius) {
                    float clampScale = radius / (float)Math.sqrt(distanceSquared);
                    offsetX *= clampScale;
                    offsetY *= clampScale;
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distanceSquared = offsetX * offsetX + offsetY * offsetY;
                if (distanceSquared > radius * radius) {
                    float clampScale = radius / (float)Math.sqrt(distanceSquared);
                    offsetX *= clampScale;
                    offsetY *= clampScale;
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                float visualX = stickCenterX(boundingBox) + deltaX * radius;
                float visualY = stickCenterY(boundingBox) + deltaY * radius;
                boolean redraw = currentPosition == null ||
                                 Math.abs(currentPosition.x - visualX) >= 0.5f ||
                                 Math.abs(currentPosition.y - visualY) >= 0.5f;
                // A dynamic stick also moves its base (the anchor), which the thumb alone doesn't show.
                if (isDynamicStick() && !inputControlsView.isEditMode()) redraw = true;
                if (currentPosition == null) currentPosition = new PointF();
                if (redraw) currentPosition.set(visualX, visualY);
                float adjDeltaX = (Math.abs(deltaX) < Math.abs(deltaY) * STICK_CROSS_ZONE) ? 0 : deltaX;
                float adjDeltaY = (Math.abs(deltaY) < Math.abs(deltaX) * STICK_CROSS_ZONE) ? 0 : deltaY;
                
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    float magnitude = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    float finalX = 0;
                    float finalY = 0;

                    if (magnitude > STICK_DEAD_ZONE) {
                        float normalizedX = deltaX / magnitude;
                        float normalizedY = deltaY / magnitude;
                        float scaledMagnitude = Math.max(0, magnitude - 0.01f) * STICK_SENSITIVITY;
                        scaledMagnitude = Math.min(scaledMagnitude, 1.0f);
                        finalX = normalizedX * scaledMagnitude;
                        finalY = normalizedY * scaledMagnitude;
                    }

                    inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    // Mouse-move bindings: the raw 2D deflection (no per-axis dead zone and no
                    // cross-zone snapping to the axes, both of which made diagonal aiming coarse)
                    // through the radial dead zone + response curve.
                    float[] shaped = InputControlsView.shapeStickMouse(deltaX, deltaY);
                    applyMouseMoveBindings(shaped[0], shaped[1]);

                    // Key bindings keep the axis snapping (right for WASD-style movement).
                    final boolean[] states = {adjDeltaY <= -STICK_DEAD_ZONE, adjDeltaX >= STICK_DEAD_ZONE, adjDeltaY >= STICK_DEAD_ZONE, adjDeltaX <= -STICK_DEAD_ZONE};
                    for (byte i = 0; i < 4; i++) {
                        Binding binding = getBindingAt(i);
                        if (binding.isMouseMove()) continue;
                        float value = i == 1 || i == 3 ? adjDeltaX : adjDeltaY;
                        boolean state = states[i];
                        // Keys go out on a change only (a held direction isn't pressed again on
                        // every move); gamepad bindings keep coming, their analog value follows
                        // the finger (the view drops the ones that changed nothing).
                        if (state != this.states[i] || (state && binding.isGamepad())) {
                            inputControlsView.handleInputEvent(binding, state, value);
                        }
                        this.states[i] = state;
                    }
                }
                if (redraw) invalidateSelf();
                return true;
            }
            else if (type == Type.TRACKPAD) {
                
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    // Velocity, not per-event delta: the old mapping read the delta of one touch
                    // event, so the same swipe gave half the stick at 120 Hz, and a finger that
                    // stopped without lifting either kept the camera spinning (no more events) or
                    // snapped it to 0 (a zero-delta event). The tick below eases it back instead.
                    long now = android.os.SystemClock.uptimeMillis();
                    float dt = trackpadLastMoveMs == 0 ? 1f / 60f : (now - trackpadLastMoveMs) / 1000f;
                    dt = Math.max(0.004f, Math.min(0.05f, dt));
                    trackpadLastMoveMs = now;
                    float density = screenDensity();
                    float vx = rawDx / density / dt;
                    float vy = rawDy / density / dt;
                    trackpadVelX = trackpadVelX * 0.5f + vx * 0.5f;
                    trackpadVelY = trackpadVelY * 0.5f + vy * 0.5f;
                    sendTrackpadStick(firstBinding);
                    scheduleTrackpadTick();

                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    
                    final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                    // Mouse-bound directions move the cursor like a finger on the touch area:
                    // same Cursor Speed and acceleration, with the sub-pixel remainder carried.
                    // (This path used to ignore every speed setting.)
                    float speed = touchpadView.getSensitivity();
                    if (trackpadAccel == null) trackpadAccel = new com.winlator.cmod.math.PointerAccel(screenDensity());
                    float gain = trackpadAccel.gain(rawDx, rawDy, android.os.SystemClock.uptimeMillis());
                    float cursorX = 0;
                    float cursorY = 0;

                    for (byte i = 0; i < 4; i++) {
                        float value = (i == 1 || i == 3 ? deltaX : deltaY);
                        Binding binding = getBindingAt(i);
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorX = value * speed * gain;
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorY = value * speed * gain;
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }

                    trackpadMotion.add(cursorX, cursorY);
                    int cursorDx = trackpadMotion.x();
                    int cursorDy = trackpadMotion.y();

                    if (cursorDx != 0 || cursorDy != 0)  {
                        XServer xServer = inputControlsView.getXServer();
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                        else
                            inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                    }
                }
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                if (usesNativeGamepadDPad()) {
                    // A direction is kept until the finger is slightly inside its threshold, so
                    // it doesn't flicker between released and pressed right on the edge.
                    final float releaseZone = DPAD_DEAD_ZONE - 0.05f;
                    final boolean up = deltaY <= -(this.states[0] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean right = deltaX >= (this.states[1] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean down = deltaY >= (this.states[2] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean left = deltaX <= -(this.states[3] ? releaseZone : DPAD_DEAD_ZONE);
                    if (this.states[0] == up && this.states[1] == right &&
                        this.states[2] == down && this.states[3] == left) return true;

                    boolean hadDirection = this.states[0] || this.states[1] || this.states[2] || this.states[3];
                    boolean newDirection = (up && !this.states[0]) || (right && !this.states[1]) ||
                                           (down && !this.states[2]) || (left && !this.states[3]);
                    inputControlsView.handleDPadInput(up, right, down, left);
                    this.states[0] = up;
                    this.states[1] = right;
                    this.states[2] = down;
                    this.states[3] = left;
                    if (newDirection) {
                        inputControlsView.playTouchHaptic(hadDirection ? TouchHaptics.TICK : TouchHaptics.PRESS);
                    }
                    invalidateSelf();
                    return true;
                }

                boolean stateChanged = false;

                // Haptics on rising edges only: a direction that wasn't held before. From rest
                // that's a press; rolling to another direction without lifting is a lighter
                // tick. Releasing a direction or returning to centre is silent.
                boolean hadDirection = this.states[0] || this.states[1] || this.states[2] || this.states[3];
                boolean newDirection = false;

                // Mouse-move bindings: same per-axis D-pad gate and finger-position magnitude as
                // before, but as one 2D source with the direction taken from the binding.
                applyMouseMoveBindings(states[1] || states[3] ? deltaX : 0f, states[0] || states[2] ? deltaY : 0f);
                for (byte i = 0; i < 4; i++) {
                    Binding binding = getBindingAt(i);
                    if (this.states[i] != states[i]) stateChanged = true;
                    if (binding.isMouseMove()) {
                        if (states[i] && !this.states[i]) newDirection = true;
                        this.states[i] = states[i];
                        continue;
                    }
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    boolean state = states[i];
                    if (state && !this.states[i]) newDirection = true;
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }

                if (type == Type.D_PAD && newDirection) {
                    inputControlsView.playTouchHaptic(hadDirection ? TouchHaptics.TICK : TouchHaptics.PRESS);
                }
                if (stateChanged) invalidateSelf();
                return true;
            }

            // Trackpad: only its pressed look is drawn (set on touch down and up), so moving the
            // finger changes the input but not the picture.
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            invalidateSelf();
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON) {
                if (mouseMoveMode) {
                    inputControlsView.getTouchpadView().mouseMove(0, 0, MotionEvent.ACTION_UP);
                    invalidateSelf();
                }
                else if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        setButtonBindingsActive(false);
                    }
                    touchTime = null;
                    invalidateSelf();
                }
                else if (!toggleSwitch || selected) {
                    setButtonBindingsActive(false);
                }

                if (!mouseMoveMode && toggleSwitch) {
                    selected = !selected;
                    invalidateSelf();
                }
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
                
                
                
                
                if (type == Type.TRACKPAD) {
                    inputControlsView.removeCallbacks(trackpadTick);
                    trackpadTickScheduled = false;
                    trackpadVelX = trackpadVelY = 0;
                    trackpadLastMoveMs = 0;
                }
                if ((type == Type.STICK || type == Type.TRACKPAD) && getBindingAt(0).isGamepad()) {
                    inputControlsView.handleStickInput(getBindingAt(0), 0f, 0f);
                    for (byte i = 0; i < states.length; i++) states[i] = false;
                }
                else if (usesNativeGamepadDPad()) {
                    inputControlsView.handleDPadInput(false, false, false, false);
                    for (byte i = 0; i < states.length; i++) states[i] = false;
                }
                else {
                    for (byte i = 0; i < states.length; i++) {
                        if (states[i] && !getBindingAt(i).isMouseMove()) inputControlsView.handleInputEvent(getBindingAt(i), false);
                        states[i] = false;
                    }
                    if (type == Type.STICK || type == Type.D_PAD) inputControlsView.setStickMouse(this, 0f, 0f);
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                }
                else if (type == Type.STICK) {
                    // Dynamic stick: straight back home (no return animation, by design).
                    anchorActive = false;
                    if (anchorTickScheduled) {
                        inputControlsView.removeCallbacks(anchorTick);
                        anchorTickScheduled = false;
                    }
                    invalidateSelf();
                }

                if (currentPosition != null) currentPosition = null;
            }
            currentPointerId = -1;
            invalidateSelf();
            return true;
        }
        return false;
    }

    
}
