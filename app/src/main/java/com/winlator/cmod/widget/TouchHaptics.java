package com.winlator.cmod.widget;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

// The single owner of the phone's vibrator: haptic feedback for the on-screen controls (the
// sidebar's touchscreen "Vibration" switch) and game rumble played on the phone (the on-screen
// gamepad, and a physical controller without a motor of its own).
//
// One owner because the phone has one actuator and a new vibrate() replaces whatever is
// playing. A touch click used to cut a running rumble off, and a "stop" from the game used to
// cancel touch clicks (patched with a per-slot "rumbleUntil" in WinHandler). Now a click played
// during a rumble resumes the rumble for its remaining time, and a stop only cancels a rumble
// this class is actually playing for that slot.
//
// Touch feedback, two weights:
//   PRESS — a button press, a D-pad direction from rest, a swipe onto another button
//   TICK  — the D-pad rolling to another direction without lifting (lighter than a press)
// Sticks (fixed and dynamic) and trackpads don't vibrate at all: continuous input.
// Each weight is built once, best available first:
//   Android 11+ composition primitives (crispest, if the motor supports them)
//   → Android 10+ predefined effects (tuned by the OEM for its motor)
//   → a very short one-shot, with an explicit amplitude where the motor allows it.
//
// Rumble: strong/weak (0..65535, the evdev FF_RUMBLE magnitudes) are mixed into one perceived
// intensity on a perceptual curve (a linear 0..255 mapping left mid and low rumble below what a
// phone's linear motor can be felt at). Short pulses become a composition primitive (THUD for
// a strong-motor hit, CLICK otherwise) when supported, so a shot / recoil is a knock instead of
// a buzz; longer ones are a one-shot with amplitude.
//
// No VibrationAttributes on purpose: both follow the system's general vibration settings. The
// Vibrator is called directly (not View.performHapticFeedback): the game has its own on/off
// switch, and routing through the system "touch feedback" setting would silently mute it for
// anyone who turned that off.
public final class TouchHaptics {
    public static final int NONE = -1;
    public static final int PRESS = 0;
    public static final int TICK = 1;

    // Ticks closer together than this are dropped, so a finger wobbling on a D-pad boundary
    // can't turn into a continuous rattle.
    private static final long MIN_TICK_INTERVAL_MS = 35;

    // Rumble shorter than this is played as a primitive (if supported).
    private static final int RUMBLE_PULSE_MAX_MS = 50;
    // evdev replay.length 0 = "play until stopped". Capped, so a lost stop (crashed or paused
    // game) can't leave the phone vibrating; games refresh a held rumble well within this.
    private static final int RUMBLE_UNTIL_STOPPED_MS = 10_000;
    // Lowest amplitude used for a non-zero rumble: below roughly this a linear motor is not felt.
    private static final int RUMBLE_MIN_AMPLITUDE = 40;
    // The weak (high-frequency) motor of a pad is felt as much lighter than the strong one.
    private static final float RUMBLE_WEAK_WEIGHT = 0.6f;
    // Remaining rumble shorter than this is not worth resuming after a click.
    private static final long RUMBLE_RESUME_MIN_MS = 20;

    private static TouchHaptics shared;

    /** The process-wide instance (the phone has one vibrator). */
    public static synchronized TouchHaptics shared(Context context) {
        if (shared == null) shared = new TouchHaptics(context.getApplicationContext());
        return shared;
    }

    private final Vibrator vibrator;
    private final boolean amplitudeControl;
    private final boolean clickPrimitive;
    private final boolean thudPrimitive;
    private final VibrationEffect pressEffect;
    private final VibrationEffect tickEffect;
    private final long pressMs;
    private final long tickMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTickAt;

    // Rumble currently playing on the phone (guarded by "this").
    private int rumbleSlot = -1;
    private int rumbleAmplitude;
    private long rumbleEndAt;
    // Bumped on every rumble change, so a pending resume of an older rumble does nothing.
    private int rumbleGeneration;

    private TouchHaptics(Context context) {
        Vibrator v = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) v = manager.getDefaultVibrator();
        }
        if (v == null) v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        vibrator = (v != null && v.hasVibrator()) ? v : null;

        if (vibrator != null) {
            amplitudeControl = vibrator.hasAmplitudeControl();
            clickPrimitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK);
            thudPrimitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD);
            pressEffect = buildTouch(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, VibrationEffect.EFFECT_CLICK, 16, 190);
            tickEffect = buildTouch(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, VibrationEffect.EFFECT_TICK, 8, 120);
            pressMs = touchDurationMs(VibrationEffect.Composition.PRIMITIVE_CLICK, 30);
            tickMs = touchDurationMs(VibrationEffect.Composition.PRIMITIVE_TICK, 15);
        } else {
            amplitudeControl = clickPrimitive = thudPrimitive = false;
            pressEffect = tickEffect = null;
            pressMs = tickMs = 0;
        }
    }

    public boolean isAvailable() {
        return vibrator != null;
    }

    // ---------- Touch feedback ----------

    public void play(int kind) {
        if (vibrator == null || kind == NONE) return;

        long now = SystemClock.uptimeMillis();
        if (kind == TICK) {
            if (now - lastTickAt < MIN_TICK_INTERVAL_MS) return;
            lastTickAt = now;
        }

        VibrationEffect effect = kind == TICK ? tickEffect : pressEffect;
        if (effect == null) return;

        synchronized (this) {
            vibrator.vibrate(effect);
            // The click replaced a running rumble: resume it once the click is over.
            long clickMs = kind == TICK ? tickMs : pressMs;
            if (rumbleSlot >= 0 && rumbleEndAt - (now + clickMs) >= RUMBLE_RESUME_MIN_MS) {
                final int generation = ++rumbleGeneration;
                handler.postDelayed(() -> resumeRumble(generation), clickMs);
            }
        }
    }

    private synchronized void resumeRumble(int generation) {
        if (generation != rumbleGeneration || rumbleSlot < 0) return;
        long remaining = rumbleEndAt - SystemClock.uptimeMillis();
        if (remaining < RUMBLE_RESUME_MIN_MS) return;
        vibrator.vibrate(VibrationEffect.createOneShot(remaining, rumbleAmplitude));
    }

    private VibrationEffect buildTouch(int primitive, float scale, int predefined, int fallbackMs, int fallbackAmplitude) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(primitive)) {
            return VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return VibrationEffect.createPredefined(predefined);
        }
        int amplitude = vibrator.hasAmplitudeControl() ? fallbackAmplitude : VibrationEffect.DEFAULT_AMPLITUDE;
        return VibrationEffect.createOneShot(fallbackMs, amplitude);
    }

    // How long a touch effect occupies the motor (for resuming a rumble after it).
    private long touchDurationMs(int primitive, long fallbackMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator.areAllPrimitivesSupported(primitive)) {
            int[] durations = vibrator.getPrimitiveDurations(primitive);
            if (durations.length > 0 && durations[0] > 0) return durations[0];
        }
        return fallbackMs;
    }

    // ---------- Game rumble ----------

    /**
     * Plays a game rumble on the phone for {@code slot}. strong/weak are evdev magnitudes
     * (0..65535); durationMs 0 means "until stopped". Both magnitudes 0 is a stop.
     */
    public synchronized void rumble(int slot, int strong, int weak, int durationMs) {
        if (vibrator == null) return;
        if (strong <= 0 && weak <= 0) {
            stopRumble(slot);
            return;
        }

        float intensity = Math.min(1f, Math.max(strong, weak * RUMBLE_WEAK_WEIGHT) / 65535f);
        // Perceptual curve: a linear motor's felt strength grows much slower than amplitude.
        float perceived = (float) Math.sqrt(intensity);
        int duration = durationMs > 0 ? durationMs : RUMBLE_UNTIL_STOPPED_MS;
        long now = SystemClock.uptimeMillis();

        rumbleGeneration++;
        if (durationMs > 0 && durationMs <= RUMBLE_PULSE_MAX_MS && (thudPrimitive || clickPrimitive)) {
            boolean strongHit = strong >= weak && thudPrimitive;
            int primitive = strongHit ? VibrationEffect.Composition.PRIMITIVE_THUD
                    : clickPrimitive ? VibrationEffect.Composition.PRIMITIVE_CLICK
                    : VibrationEffect.Composition.PRIMITIVE_THUD;
            vibrator.vibrate(VibrationEffect.startComposition()
                    .addPrimitive(primitive, Math.max(0.3f, perceived)).compose());
            // A pulse is not resumed after a click; it is still "ours" for a stop.
            rumbleSlot = slot;
            rumbleAmplitude = 0;
            rumbleEndAt = now;
            return;
        }

        int amplitude = amplitudeControl
                ? Math.round(RUMBLE_MIN_AMPLITUDE + (255 - RUMBLE_MIN_AMPLITUDE) * perceived)
                : VibrationEffect.DEFAULT_AMPLITUDE;
        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
        rumbleSlot = slot;
        rumbleAmplitude = amplitude;
        rumbleEndAt = now + duration;
    }

    /** Stops the rumble of {@code slot} if it is the one playing; never touches clicks. */
    public synchronized void stopRumble(int slot) {
        if (vibrator == null || rumbleSlot != slot) return;
        boolean playing = SystemClock.uptimeMillis() < rumbleEndAt;
        clearRumble();
        if (playing) vibrator.cancel();
    }

    /** Stops any rumble (game paused / closed, fake input torn down). */
    public synchronized void stopAllRumble() {
        if (vibrator == null || rumbleSlot < 0) return;
        boolean playing = SystemClock.uptimeMillis() < rumbleEndAt;
        clearRumble();
        if (playing) vibrator.cancel();
    }

    private void clearRumble() {
        rumbleGeneration++;
        rumbleSlot = -1;
        rumbleAmplitude = 0;
        rumbleEndAt = 0;
    }
}
