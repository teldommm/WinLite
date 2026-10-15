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
    private volatile long msc = -1;
    private volatile long lastFrameTimeNs = 0;

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

    /** Vsync count since the clock first ran, -1 before the first tick. */
    long msc() { return msc; }

    /** CLOCK_MONOTONIC time of the latest vsync. */
    long lastVsyncNs() { return lastFrameTimeNs; }

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
        lastFrameTimeNs = frameTimeNs;
        msc = msc < 0 ? 0 : msc + 1;
        listener.onVsync(frameTimeNs, deadlineNs, presentNs);
        post();
    }
}
