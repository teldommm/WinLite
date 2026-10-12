package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.MotionAccumulator;
import com.winlator.cmod.math.PointerAccel;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

public class TouchpadView extends View {
    // Indexed by pointer id; on-screen controls hold pointers too, so a touch-area finger can get
    // a high id (it used to be 4, and a 5th finger was ignored while 4 were on controls).
    private static final byte MAX_FINGERS = 10;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final short MAX_TAP_MILLISECONDS = 200;
    // Touch-area gesture thresholds in dp of the physical screen. They used to be X-server pixels,
    // so taps and scrolling got stricter or looser with the game resolution.
    public static final float TAP_TRAVEL_DP = 8f;
    private static final float SCROLL_STEP_DP = 36f;
    // How long a synthesized click (tap, long press) keeps the button down, so games that poll
    // the button state once per frame still see it.
    private static final long CLICK_HOLD_MS = 30;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    // volatile: InputControlsView's stick mouse timer reads it from its own thread.
    private volatile float sensitivity = 1.0f;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private final XServer xServer;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();
    private static final int UPDATE_FORM_DELAYED_TIME = 50;
    private boolean mouseEnabled = true;
    private float density = 1f;
    // With two fingers on the touch area only one moves the cursor: the first one that travels
    // past the tap distance (the other one is holding a drag or just resting).
    private Finger cursorFinger;

    // ---------- Touch mode ----------
    // TRACKPAD: relative cursor (tap = click, two-finger tap = right click, scroll, drag).
    // TOUCHSCREEN: the cursor goes where the finger is (press = left button down), always with
    // absolute X pointer events — Relative Mouse is not applied in this mode.
    public static final int MODE_TRACKPAD = 0;
    public static final int MODE_TOUCHSCREEN = 1;
    // Touch Mode "Off" as the UI and the activity see it: not a gesture mode of this view, but
    // setMouseEnabled(false) — the touch surface ignores the finger entirely.
    public static final int MODE_OFF = 2;
    private int touchMode = MODE_TRACKPAD;
    // Mode captured on the first finger down and kept until every finger is up, so switching
    // modes can never re-route a gesture halfway through.
    private int gestureMode = -1;

    // Tap/press-to-click for every touch gesture (tap, two-finger tap, long press, touchscreen
    // press). Off = the touch surface only moves / scrolls; on-screen buttons still click.
    private boolean tapToClickEnabled = true;

    // Pointers currently held by on-screen controls (InputControlsView keeps this in sync):
    // those fingers never count as trackpad / touchscreen fingers.
    private final Set<Integer> pointerIdsToIgnore = new HashSet<>();

    // Trackpad: hold one finger still ~1 s = right click.
    private static final long LONG_PRESS_RIGHT_CLICK_MS = 1000;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private boolean longPressActive = false;
    private final Runnable longPressRunnable = this::onLongPress;

    // Touchscreen: own pointer tracking (independent of the event's pointer order, so a finger
    // on a stick can't become "the" touch).
    private static final long TOUCHSCREEN_DOUBLE_TAP_MS = 500;
    // In dp of the physical screen (were 100 raw px and 100 X-server px: the first depended on
    // the screen density, the second on the game resolution).
    private static final float TOUCHSCREEN_DOUBLE_TAP_DISTANCE_DP = 36f;
    private static final float TOUCHSCREEN_SCROLL_STEP_DP = 36f;
    private int tsPrimaryId = -1;
    private int tsSecondaryId = -1;
    private boolean tsScrolled = false;
    private long tsSecondaryDownTime;
    private float tsScrollLastY;
    private float tsScrollAccum;
    private long lastTapDownTime;
    private float lastTapRawX, lastTapRawY;
    private int lastTapX, lastTapY;

    private SharedPreferences preferences;


    // Flag to control touchpad vs touchscreen mode

    @SuppressLint("ResourceType")
    public TouchpadView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        density = context.getResources().getDisplayMetrics().density;

        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBg());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
        // Initialize SharedPreferences here
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Set up the generic motion listener for hover events
        setOnGenericMotionListener(new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return handleStylusHoverEvent(event);
                }
                return false;
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, xServer.screenInfo.width, xServer.screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);

        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.getXServerView().isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(xform, invAspect, invAspect);
        } else
            XForm.makeScale(xform, (float) innerWidth / outerWidth, (float) innerHeight / outerHeight);
    }

    private class Finger {
        private int x;
        private int y;
        // Unrounded X-screen position: deltas are taken from these so sub-pixel finger motion
        // isn't lost before the speed is applied (the int fields stay for absolute use).
        private float fx;
        private float fy;
        private float lastFx;
        private float lastFy;
        private final MotionAccumulator motion = new MotionAccumulator();
        private final long touchTime;
        // Physical screen position: gesture thresholds and finger speed are measured here, so
        // they don't depend on the game resolution.
        private final float startRawX;
        private final float startRawY;
        private float rawX;
        private float rawY;
        private float lastRawX;
        private float lastRawY;
        private final PointerAccel accel = new PointerAccel(density);

        public Finger(float x, float y, long eventTimeMs) {
            startRawX = rawX = lastRawX = x;
            startRawY = rawY = lastRawY = y;
            accel.reset(eventTimeMs);
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.fx = this.lastFx = transformedPoint[0];
            this.fy = this.lastFy = transformedPoint[1];
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastFx = fx;
            lastFy = fy;
            lastRawX = rawX;
            lastRawY = rawY;
            rawX = x;
            rawY = y;
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            fx = transformedPoint[0];
            fy = transformedPoint[1];
            this.x = (int)fx;
            this.y = (int)fy;
        }

        /**
         * Scales the last move by the touch speed and the velocity-based acceleration (one gain
         * for the whole vector) and returns it as whole pixels in {@link #motion}.
         */
        private void accumulateDelta(long eventTimeMs) {
            float gain = accel.gain(rawX - lastRawX, rawY - lastRawY, eventTimeMs);
            motion.add((fx - lastFx) * sensitivity * gain, (fy - lastFy) * sensitivity * gain);
        }

        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < TAP_TRAVEL_DP;
        }

        /** Distance from the touch-down point, in dp of the physical screen. */
        private float travelDistance() {
            return (float)Math.hypot(rawX - startRawX, rawY - startRawY) / density;
        }
    }

//    public void setTouchscreenMode(boolean isTouchscreenMode) {
//        this.isTouchscreenMode = isTouchscreenMode;
//    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // A physical mouse dragging with a button held arrives here as touch events (its buttons
        // come separately as ACTION_BUTTON_PRESS/RELEASE via onExternalMouseEvent). Only the
        // movement matters, in every touch mode: Touchscreen and Off used to drop it, so the
        // cursor froze while dragging (box selection, drag-and-drop, camera drag).
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) externalMouseMoveTo(event.getX(), event.getY());
            return true;
        }

        // Touch surface off: ignore finger input
        if (!mouseEnabled) return true;

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return handleStylusEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || gestureMode == -1) gestureMode = touchMode;

        boolean result = gestureMode == MODE_TOUCHSCREEN ? handleTouchscreenEvent(event) : handleTouchpadEvent(event);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureMode = -1;
        return result;
    }

    private boolean handleStylusHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                Log.d("StylusEvent", "Hover Enter");
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                Log.d("StylusEvent", "Hover Exit");
                break;
            default:
                return false;
        }
        return true;
    }

    private boolean handleStylusEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                    handleStylusRightClick(event);
                } else {
                    handleStylusLeftClick(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                handleStylusMove(event);
                break;
            case MotionEvent.ACTION_UP:
                handleStylusUp(event);
                break;
        }

        return true;
    }

    private void handleStylusLeftClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerWarp((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
    }

    private void handleStylusRightClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerWarp((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
    }

    private void handleStylusMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleStylusUp(MotionEvent event) {
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }



    private boolean handleTouchpadEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (actionMasked != MotionEvent.ACTION_MOVE && actionMasked != MotionEvent.ACTION_CANCEL
                && (pointerId >= MAX_FINGERS || pointerIdsToIgnore.contains(pointerId))) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex), event.getEventTime());
                numFingers++;
                if (numFingers == 1) {
                    longPressActive = false;
                    longPressHandler.removeCallbacks(longPressRunnable);
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_RIGHT_CLICK_MS);
                } else {
                    longPressHandler.removeCallbacks(longPressRunnable);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    externalMouseMoveTo(event.getX(), event.getY());
                } else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            if (pointerIdsToIgnore.contains((int) i)) {
                                // Taken by an on-screen control after all: drop it quietly.
                                releasePointerButtonLeft(fingers[i]);
                                releasePointerButtonRight(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                                continue;
                            }
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(fingers[i], event.getEventTime());
                            } else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                longPressHandler.removeCallbacks(longPressRunnable);
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    // After a long-press right click the lift must not also count as a tap.
                    if (longPressActive) {
                        releasePointerButtonLeft(fingers[pointerId]);
                        releasePointerButtonRight(fingers[pointerId]);
                    } else {
                        handleFingerUp(fingers[pointerId]);
                    }
                    fingers[pointerId] = null;
                    numFingers--;
                }
                if (numFingers <= 0) longPressActive = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacks(longPressRunnable);
                longPressActive = false;
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }

        return true;
    }

    // Touchscreen mode. Tracks its own primary/secondary pointer ids (fingers held by on-screen
    // controls are skipped), uses absolute X pointer events only, and:
    //  - one finger: cursor under the finger, left button held while touching (Tap to Click);
    //    a second tap within 500 ms / 100 px lands on the first tap's point (clean double-click)
    //  - two fingers: scroll by the fingers' vertical *movement*; a quick two-finger tap without
    //    scrolling = right click.
    // (The old version scrolled by the *distance* between the fingers — every move event while
    // they were apart — and fed absolute coordinates to Relative Mouse as if they were deltas.)
    private boolean handleTouchscreenEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) || pointerIdsToIgnore.contains(pointerId)) break;
                if (tsPrimaryId == -1) {
                    tsPrimaryId = pointerId;
                    tsSecondaryId = -1;
                    touchscreenPrimaryDown(event.getX(actionIndex), event.getY(actionIndex));
                } else if (tsSecondaryId == -1) {
                    tsSecondaryId = pointerId;
                    tsScrolled = false;
                    tsScrollAccum = 0;
                    tsSecondaryDownTime = System.currentTimeMillis();
                    tsScrollLastY = touchscreenMidY(event);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (tsPrimaryId == -1 || pointerIdsToIgnore.contains(tsPrimaryId)) break;
                if (tsSecondaryId != -1) {
                    float midY = touchscreenMidY(event);
                    if (Float.isNaN(midY)) break;
                    tsScrollAccum += midY - tsScrollLastY;
                    tsScrollLastY = midY;
                    if (Math.abs(tsScrollAccum) >= TOUCHSCREEN_SCROLL_STEP_DP) {
                        // Scrolling: never keep the left button held while doing it.
                        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                        }
                        Pointer.Button button = tsScrollAccum > 0 ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
                        xServer.injectPointerButtonPress(button);
                        xServer.injectPointerButtonRelease(button);
                        tsScrollAccum = 0;
                        tsScrolled = true;
                    }
                } else {
                    int index = event.findPointerIndex(tsPrimaryId);
                    if (index < 0) break;
                    float[] p = XForm.transformPoint(xform, event.getX(index), event.getY(index));
                    xServer.injectPointerMove((int) p[0], (int) p[1]);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                if (pointerId == tsSecondaryId) {
                    boolean quick = System.currentTimeMillis() - tsSecondaryDownTime < 300;
                    if (!tsScrolled && quick && tapToClickEnabled) {
                        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                        }
                        clickRightHeld();
                    }
                    tsSecondaryId = -1;
                } else if (pointerId == tsPrimaryId) {
                    if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    }
                    // The gesture ends with its first finger; a remaining second finger is ignored.
                    tsPrimaryId = -1;
                    tsSecondaryId = -1;
                }
                if (action == MotionEvent.ACTION_UP) {
                    tsPrimaryId = -1;
                    tsSecondaryId = -1;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                resetInputState();
                break;
        }
        return true;
    }

    private void touchscreenPrimaryDown(float rawX, float rawY) {
        float[] p = XForm.transformPoint(xform, rawX, rawY);
        int x = (int) p[0];
        int y = (int) p[1];
        long now = System.currentTimeMillis();
        boolean near = Math.hypot(rawX - lastTapRawX, rawY - lastTapRawY) / density < TOUCHSCREEN_DOUBLE_TAP_DISTANCE_DP;
        if (now - lastTapDownTime < TOUCHSCREEN_DOUBLE_TAP_MS && near) {
            // Second tap of a double tap: hit exactly the same spot as the first.
            x = lastTapX;
            y = lastTapY;
        }
        lastTapDownTime = now;
        lastTapRawX = rawX;
        lastTapRawY = rawY;
        lastTapX = x;
        lastTapY = y;

        // A new touch is a jump to a new spot, not movement: no raw (XI2) motion for it.
        xServer.injectPointerWarp(x, y);
        // Tap to Click is the only switch for touch clicks, in both modes.
        if (tapToClickEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        }
    }

    // Average Y of the two touchscreen fingers in dp of the physical screen, NaN if either is gone.
    private float touchscreenMidY(MotionEvent event) {
        int a = event.findPointerIndex(tsPrimaryId);
        int b = event.findPointerIndex(tsSecondaryId);
        if (a < 0 || b < 0) return Float.NaN;
        return (event.getY(a) + event.getY(b)) * 0.5f / density;
    }

    // Trackpad long press: one finger held still for LONG_PRESS_RIGHT_CLICK_MS = right click.
    private void onLongPress() {
        if (!tapToClickEnabled || numFingers != 1) return;
        Finger finger = null;
        for (byte i = 0; i < MAX_FINGERS; i++) if (fingers[i] != null) { finger = fingers[i]; break; }
        if (finger == null || finger.travelDistance() >= TAP_TRAVEL_DP) return;
        longPressActive = true;
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        }
        clickRightHeld();
    }

    // Right click held for CLICK_HOLD_MS like a tap click (press + release in the same instant
    // could be missed by games that poll the button state once per frame).
    private void clickRightHeld() {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) return;
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
        postDelayed(() -> {
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT))
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        }, CLICK_HOLD_MS);
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (tapToClickEnabled && finger1.isTap()) pressPointerButtonLeft(finger1);
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (tapToClickEnabled && finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1);
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    }
                    fourFingersTapCallback.run();
                }
                break;
        }

        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1, long eventTimeMs) {
        if (finger1.travelDistance() >= TAP_TRAVEL_DP) longPressHandler.removeCallbacks(longPressRunnable);
        boolean skipPointerMove = false;

        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            final float resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
            float currDistance = (float)Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;

            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                // Midpoint movement in dp: each finger contributes only its own half. (Each call
                // used to add the whole midpoint change, so one finger's move was counted twice.)
                scrollAccumY += (finger1.rawY - finger1.lastRawY) * 0.5f / density;

                if (scrollAccumY < -SCROLL_STEP_DP) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                }
                else if (scrollAccumY > SCROLL_STEP_DP) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            }
            else if (tapToClickEnabled && currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                     finger2.travelDistance() < TAP_TRAVEL_DP) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }

        boolean drivesCursor = true;
        if (cursorFinger != null && !isActiveFinger(cursorFinger)) cursorFinger = null;
        if (numFingers <= 1) {
            if (finger1.travelDistance() >= TAP_TRAVEL_DP) cursorFinger = finger1;
        }
        else {
            if (cursorFinger == null && finger1.travelDistance() >= TAP_TRAVEL_DP) cursorFinger = finger1;
            drivesCursor = finger1 == cursorFinger;
        }

        if (!scrolling && numFingers <= 2 && !skipPointerMove && drivesCursor) {
            finger1.accumulateDelta(eventTimeMs);
            int dx = finger1.motion.x();
            int dy = finger1.motion.y();

            if (xServer.isRelativeMouseMovement()) {
                WinHandler winHandler = xServer.getWinHandler();
                winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            }
            else xServer.injectPointerMoveDelta(dx, dy);
        }
    }

    private boolean isActiveFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) if (fingers[i] == finger) return true;
        return false;
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            fingerPointerButtonLeft = finger;
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            fingerPointerButtonRight = finger;
        }
    }

    private void releasePointerButtonLeft(final Finger finger) {
        if (finger == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(final Finger finger) {
        if (finger == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = null;
            }, 30);
        }
    }

    /**
     * Cursor Speed: finger on the free touch area, Trackpad elements, mouse-move buttons and
     * stick / D-pad / gamepad MOUSE_MOVE bindings.
     */
    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public float getDensity() {
        return density;
    }

    // Mouse-move buttons (ControlElement BUTTON with mouseMoveMode) drag the cursor like a
    // finger on the touch area, so they share the Cursor Speed.
    private float lastMouseMoveX;
    private float lastMouseMoveY;
    private final MotionAccumulator mouseMoveMotion = new MotionAccumulator();
    private float lastMouseMoveRawX;
    private float lastMouseMoveRawY;
    private PointerAccel mouseMoveAccel;

    public void mouseMove(float x, float y, int action) {
        if (mouseMoveAccel == null) mouseMoveAccel = new PointerAccel(density);
        float[] transformedPoint = XForm.transformPoint(xform, x, y);
        float tx = transformedPoint[0];
        float ty = transformedPoint[1];

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastMouseMoveX = tx;
                lastMouseMoveY = ty;
                lastMouseMoveRawX = x;
                lastMouseMoveRawY = y;
                mouseMoveMotion.reset();
                mouseMoveAccel.reset(android.os.SystemClock.uptimeMillis());
                break;
            case MotionEvent.ACTION_MOVE: {
                float gain = mouseMoveAccel.gain(x - lastMouseMoveRawX, y - lastMouseMoveRawY, android.os.SystemClock.uptimeMillis());
                lastMouseMoveRawX = x;
                lastMouseMoveRawY = y;
                mouseMoveMotion.add((tx - lastMouseMoveX) * sensitivity * gain,
                                    (ty - lastMouseMoveY) * sensitivity * gain);
                int dx = mouseMoveMotion.x();
                int dy = mouseMoveMotion.y();
                lastMouseMoveX = tx;
                lastMouseMoveY = ty;

                if (dx != 0 || dy != 0) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                    else
                        xServer.injectPointerMoveDelta(dx, dy);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            default:
                break;
        }
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    // ---------- Physical mouse without pointer capture ----------
    // Relative Mouse needs deltas, but an uncaptured mouse reports its absolute position; that
    // position used to be sent as if it were a delta, which flung the cursor / camera. Deltas are
    // now taken from the previous position (scaled by Cursor Speed, like a captured mouse).
    private boolean externalMouseAnchored = false;
    private float externalMouseLastX;
    private float externalMouseLastY;
    private final MotionAccumulator externalMouseMotion = new MotionAccumulator();

    private void externalMouseMoveTo(float x, float y) {
        float[] p = XForm.transformPoint(xform, x, y);
        if (!xServer.isRelativeMouseMovement()) {
            externalMouseAnchored = false;
            xServer.injectPointerMove((int) p[0], (int) p[1]);
            return;
        }
        if (!externalMouseAnchored) {
            externalMouseAnchored = true;
            externalMouseLastX = p[0];
            externalMouseLastY = p[1];
            externalMouseMotion.reset();
            return;
        }
        externalMouseMotion.add((p[0] - externalMouseLastX) * sensitivity, (p[1] - externalMouseLastY) * sensitivity);
        externalMouseLastX = p[0];
        externalMouseLastY = p[1];
        int dx = externalMouseMotion.x();
        int dy = externalMouseMotion.y();
        if (dx != 0 || dy != 0) xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
    }

    // ---------- Mouse wheel ----------
    // One path for every physical wheel (captured or not). Fractional values from smooth-scrolling
    // wheels and laptop touchpads are accumulated instead of dropped, a value of 2 gives two
    // notches, and Relative Mouse gets the Windows standard 120 per notch (it used to be 1 for an
    // uncaptured mouse and 270 for a captured one).
    private static final int WHEEL_DELTA = 120;
    private float wheelAccum = 0;

    /** Vertical wheel movement in notches (positive = up / away from the user). */
    public void injectWheel(float scrollY) {
        if (scrollY == 0) return;
        if (Math.signum(scrollY) != Math.signum(wheelAccum)) wheelAccum = 0;
        wheelAccum += scrollY;
        int notches = (int) wheelAccum;
        if (notches == 0) return;
        wheelAccum -= notches;
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            if (winHandler != null) winHandler.mouseEvent(MouseEventFlags.WHEEL, 0, 0, notches * WHEEL_DELTA);
        }
        else {
            Pointer.Button button = notches > 0 ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
            for (int i = Math.abs(notches); i > 0; i--) {
                xServer.injectPointerButtonPress(button);
                xServer.injectPointerButtonRelease(button);
            }
        }
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        boolean handled = false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                    // Re-anchor: the next relative delta starts from where the mouse comes back.
                    externalMouseAnchored = false;
                    handled = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    externalMouseMoveTo(event.getX(), event.getY());
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    injectWheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    handled = true;
                    break;
            }
        }
        return handled;
    }


    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        final float[] result = {0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    private StateListDrawable createTransparentBg() {
        StateListDrawable stateListDrawable = new StateListDrawable();

        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);

        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[]{}, defaultDrawable);

        return stateListDrawable;
    }

    // Trackpad / Touchscreen. Applied from the next touch on; any held buttons and tracked
    // fingers are dropped right away so nothing stays pressed across the switch.
    public void setTouchMode(int mode) {
        if (mode != MODE_TOUCHSCREEN) mode = MODE_TRACKPAD;
        xServer.setSimulateTouchScreen(mode == MODE_TOUCHSCREEN);
        if (touchMode == mode) return;
        touchMode = mode;
        resetInputState();
    }

    public int getTouchMode() {
        return touchMode;
    }

    public void setTapToClickEnabled(boolean enabled) {
        tapToClickEnabled = enabled;
        if (!enabled) resetInputState();
    }

    public boolean isTapToClickEnabled() {
        return tapToClickEnabled;
    }

    public void setPointerIdsToIgnore(Set<Integer> ids) {
        pointerIdsToIgnore.clear();
        pointerIdsToIgnore.addAll(ids);
    }

    // Releases every button this view may be holding and forgets all fingers.
    public void resetInputState() {
        longPressHandler.removeCallbacks(longPressRunnable);
        longPressActive = false;
        scrolling = false;
        scrollAccumY = 0;
        for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
        numFingers = 0;
        cursorFinger = null;
        externalMouseAnchored = false;
        wheelAccum = 0;
        fingerPointerButtonLeft = null;
        fingerPointerButtonRight = null;
        tsPrimaryId = -1;
        tsSecondaryId = -1;
        gestureMode = -1;
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_MIDDLE)) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
    }

    public void toggleFullscreen() {
        new Handler().postDelayed(() -> updateXform(getWidth(), getHeight(), xServer.screenInfo.width, xServer.screenInfo.height),
                UPDATE_FORM_DELAYED_TIME);
    }
    
    public void setMouseEnabled(boolean enabled) {
        this.mouseEnabled = enabled;
        if (!enabled) resetInputState();
    }
}
