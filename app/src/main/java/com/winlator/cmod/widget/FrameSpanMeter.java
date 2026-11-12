package com.winlator.cmod.widget;

/**
 * The game's fps for the HUDs, from the frames' own times.
 *
 * <p>The HUDs used to add up the frames that arrived between two ticks of their own timer and
 * divide by the tick interval. Frames come at discrete times, so a window of 400 ms over a
 * limiter at 30 fps holds 12 or 13 of them depending on where the ticks fall, and 13 frames in a
 * 405 ms tick read 32; on top of that the arrival time was when the event thread drained its
 * batch, not when the frame was made. Here the span runs from one frame to another (both stamped
 * by the X server), so it always holds a whole number of frame intervals and the reading is the
 * real rate: 30.0 for a limiter at 30.
 *
 * <p>Thread-safe: frames come on the X event thread, readings are taken on the UI thread.
 */
final class FrameSpanMeter {
    /** No frame for this long: the game stalled, the reading drops to 0. */
    private static final long STALL_NS = 1_000_000_000L;
    /**
     * A window that DRI3-presents is rated by its presents only: X-drawn updates of the same window
     * (Wine painting it, a GDI overlay) are not frames of the game and used to count on top.
     */
    private static final long DIRECT_HOLD_NS = 1_000_000_000L;

    private long startNs = 0;   // the frame the span starts at (not counted in it)
    private long lastNs = 0;    // the latest frame
    private int frames = 0;     // frames after startNs, up to and including lastNs
    private long lastDirectNs = 0;

    synchronized void add(int count, long timeNs, boolean direct) {
        if (direct) {
            lastDirectNs = timeNs;
        } else if (lastDirectNs != 0 && timeNs - lastDirectNs < DIRECT_HOLD_NS) {
            return;
        }
        if (startNs == 0 || timeNs < lastNs) {
            // First frame (or a clock that went back: start over from here).
            startNs = timeNs;
            lastNs = timeNs;
            frames = 0;
            return;
        }
        frames += Math.max(1, count);
        lastNs = timeNs;
    }

    /**
     * The fps over the frames since the previous reading, once they span at least
     * {@code minSpanNs}; then the next span starts at the latest frame. {@code -1}: not enough yet,
     * keep showing the previous value. {@code 0}: no frames for a second.
     */
    synchronized float take(long nowNs, long minSpanNs) {
        if (startNs == 0) return 0f;
        if (nowNs - lastNs >= STALL_NS) {
            startNs = 0;
            lastNs = 0;
            frames = 0;
            return 0f;
        }
        long span = lastNs - startNs;
        if (frames == 0 || span < minSpanNs) return -1f;
        float fps = frames * 1_000_000_000f / span;
        startNs = lastNs;
        frames = 0;
        return fps;
    }

    synchronized void reset() {
        startNs = 0;
        lastNs = 0;
        frames = 0;
        lastDirectNs = 0;
    }
}
