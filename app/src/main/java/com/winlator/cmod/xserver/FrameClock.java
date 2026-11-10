package com.winlator.cmod.xserver;

import com.winlator.cmod.widget.XServerRendererView;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The interval between two releases of an FPS-limited game, on the renderer's display clock.
 * Both limiters use it (PresentExtension for Present clients, ShmFramePacer for MIT-SHM
 * blitters), with the same model as the renderer's own present pacing:
 *  - an interval within 1% of a whole number of refreshes becomes exactly that many refreshes
 *    (the rule the renderer applies to its schedules and to the panel mode), so the limiter and
 *    the panel cannot drift apart;
 *  - each new arrival phase error the renderer measures slews the next release by a quarter of
 *    it, at most 0.5 ms per frame: the game is released so its frame arrives at the phase that
 *    lands it on a refresh with the least latency, whatever the game's own render time is.
 * Without a display clock (no renderer, no fresh grid) it is the plain 1/fps interval.
 */
public final class FrameClock {
    private static final long MAX_SLEW_NS = 500_000L;
    private static final int  GAIN_DIV = 4;

    // Each measurement is applied once, by whichever limiter releases next: one game, one error.
    private static final AtomicLong appliedSeq = new AtomicLong();
    private static final ThreadLocal<long[]> scratch = ThreadLocal.withInitial(() -> new long[3]);

    private FrameClock() {}

    public static long nextInterval(XServerRendererView view, int fps) {
        long interval = 1_000_000_000L / fps;
        if (view == null) return interval;
        final long[] clock = scratch.get();
        if (!view.getDisplayClock(clock)) return interval;

        final long period = clock[0];
        if (period > 0) {
            final long k = (interval + period / 2) / period;
            if (k >= 1 && Math.abs(interval - k * period) * 100 <= interval) interval = k * period;
        }

        final long seq = clock[2];
        final long prev = appliedSeq.get();
        if (seq != prev && appliedSeq.compareAndSet(prev, seq)) {
            long corr = clock[1] / GAIN_DIV;
            if (corr > MAX_SLEW_NS) corr = MAX_SLEW_NS;
            if (corr < -MAX_SLEW_NS) corr = -MAX_SLEW_NS;
            interval -= corr;   // arrived late: release sooner
        }
        return interval;
    }
}
