package com.winlator.cmod.widget;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Choreographer;

/**
 * The display's vsync, from Choreographer on its own thread. Feeds the renderer's frame pacing
 * (which refresh a frame is queued for) and the X Present extension's MSC/UST, which used to be
 * made up from a fixed 60 Hz clock.
 *
 * On Android 13+ each tick carries the preferred frame timeline: the deadline by which a buffer
 * must be queued to be shown at the expected presentation time. Older versions only give the
 * frame (app vsync) time.
 */
final class VsyncClock {
    interface Listener {
        /** @param deadlineNs / presentNs 0 when the frame timeline is not available. */
        void onVsync(long frameTimeNs, long deadlineNs, long presentNs);
    }

    private final Listener listener;
    private HandlerThread thread;
    private Choreographer choreographer;
    private volatile boolean running = false;

    /** Coherent (msc, ust, period) triple; replaced as a whole on every tick. */
    private static final class Sample {
        final long msc, frameTimeNs, periodNs;
        Sample(long msc, long frameTimeNs, long periodNs) {
            this.msc = msc; this.frameTimeNs = frameTimeNs; this.periodNs = periodNs;
        }
    }
    private volatile Sample sample;   // null before the first tick
    // The display's own period (from its refresh rate). Counting and learning are anchored to
    // it: starting from a fixed 60 Hz guess, a 120/144/165 Hz panel never passed the learning
    // gate and missed vsyncs were counted at 60 Hz (5 missed at 144 Hz counted as 2).
    private volatile long nominalPeriodNs = 0;

    /** The display's refresh period; a change (mode switch) restarts period learning from it. */
    void setNominalPeriod(long periodNs) {
        if (periodNs <= 0 || periodNs == nominalPeriodNs) return;
        nominalPeriodNs = periodNs;
        Sample s = sample;
        if (s != null) sample = new Sample(s.msc, s.frameTimeNs, periodNs);
    }

    private final Choreographer.FrameCallback frameCallback = this::onFrame;
    private Object vsyncCallback;   // Choreographer.VsyncCallback on API 33+

    VsyncClock(Listener listener) {
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("VsyncClock", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        new Handler(thread.getLooper()).post(() -> {
            choreographer = Choreographer.getInstance();
            if (Build.VERSION.SDK_INT >= 33) {
                vsyncCallback = (Choreographer.VsyncCallback) data -> {
                    Choreographer.FrameTimeline t = data.getPreferredFrameTimeline();
                    tick(data.getFrameTimeNanos(), t.getDeadlineNanos(),
                            t.getExpectedPresentationTimeNanos());
                };
            }
            post();
        });
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    /** Nominal 60 Hz period the MSC is anchored to (and the fallback clock uses). */
    static final long ANCHOR_PERIOD_NS = XServerRendererView.FALLBACK_MSC_PERIOD_NS;

    /**
     * The vsync count and time (CLOCK_MONOTONIC ns) of the latest vsync at {@code nowNs}:
     * out[0] = msc, out[1] = ust in ns. Returns false before the first vsync.
     *
     * The count follows time, not callbacks: a vsync Choreographer did not deliver (busy looper,
     * surface gone, clock stopped) still counts, extrapolated with the measured period. So the
     * MSC never stalls or goes backwards across surface re-creation.
     */
    boolean sampleAt(long nowNs, long[] out) {
        Sample s = sample;
        if (s == null) return false;
        long msc = s.msc, ust = s.frameTimeNs;
        long dt = nowNs - ust;
        if (dt >= s.periodNs) {
            long n = dt / s.periodNs;
            msc += n;
            ust += n * s.periodNs;
        }
        out[0] = msc;
        out[1] = ust;
        return true;
    }

    private void post() {
        if (!running || choreographer == null) return;
        if (Build.VERSION.SDK_INT >= 33 && vsyncCallback != null) {
            choreographer.postVsyncCallback((Choreographer.VsyncCallback) vsyncCallback);
        } else {
            choreographer.postFrameCallback(frameCallback);
        }
    }

    private void onFrame(long frameTimeNanos) {
        tick(frameTimeNanos, 0, 0);
    }

    private void tick(long frameTimeNs, long deadlineNs, long presentNs) {
        Sample prev = sample;
        if (prev == null) {
            // Anchored to the same 60 Hz time base the fallback clock uses, so switching from
            // the fallback to the real clock does not move the MSC backwards by much.
            long nominal = nominalPeriodNs;
            sample = new Sample(frameTimeNs / ANCHOR_PERIOD_NS, frameTimeNs,
                    nominal > 0 ? nominal : ANCHOR_PERIOD_NS);
        } else {
            long dt = frameTimeNs - prev.frameTimeNs;
            if (dt > 0) {
                long period = prev.periodNs;
                long n = Math.max(1, (dt + period / 2) / period);
                // Refine the period from consecutive vsyncs only (n == 1, within 25 %): the
                // measured one absorbs the panel's small deviation from its nominal rate.
                if (n == 1 && dt * 4 >= period * 3 && dt * 4 <= period * 5) period = (period * 7 + dt) / 8;
                sample = new Sample(prev.msc + n, frameTimeNs, period);
            }
        }
        listener.onVsync(frameTimeNs, deadlineNs, presentNs);
        post();
    }
}
