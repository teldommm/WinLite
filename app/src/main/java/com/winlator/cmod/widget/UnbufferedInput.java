package com.winlator.cmod.widget;

import android.app.Activity;
import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * Turns off Android's per-frame input batching for the game screen.
 *
 * By default a window receives ACTION_MOVE (touch, mouse, captured mouse, joystick axes) once
 * per display frame: samples are queued and handed over together at the next vsync, so a
 * movement can sit up to one refresh before the app sees it. A view can ask for its events as
 * they arrive instead. Pointer sources (touch, mouse) apply from any view in the window;
 * non-pointer ones (captured mouse = trackball class, joystick) only from the focused view's
 * chain, which is why the focused view is re-registered whenever focus moves.
 *
 * Only reasonable together with render pacing (VulkanRendererContext::pacingWaitNs): every
 * extra motion event moves the cursor, and without pacing each one would queue a frame.
 */
public final class UnbufferedInput {
    private static final int SOURCES = InputDevice.SOURCE_CLASS_POINTER
            | InputDevice.SOURCE_CLASS_TRACKBALL   // captured mouse (SOURCE_MOUSE_RELATIVE)
            | InputDevice.SOURCE_CLASS_POSITION    // touchpads
            | InputDevice.SOURCE_CLASS_JOYSTICK;   // sticks / triggers

    private UnbufferedInput() {}

    /** Android 11+: all game input sources for this view, no event needed. */
    public static void request(View view) {
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.requestUnbufferedDispatch(SOURCES);
        }
    }

    /**
     * Android 9/10 fallback for touch: unbuffered for the rest of this gesture. Call at the top
     * of onTouchEvent; on 11+ {@link #request(View)} already covers it.
     */
    public static void onTouchEvent(View view, MotionEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            view.requestUnbufferedDispatch(event);
        }
    }

    /** Registers the given views and follows focus, so the focused view always carries it. */
    public static void install(Activity activity, View... views) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        for (View view : views) request(view);
        View decor = activity.getWindow().getDecorView();
        request(decor.findFocus());
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> request(newFocus));
    }
}
