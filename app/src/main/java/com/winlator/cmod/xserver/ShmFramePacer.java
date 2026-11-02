package com.winlator.cmod.xserver;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Alternative FPS limiter for clients that present via full-drawable MIT-SHM blits instead of the
 * Present extension (the Present-based limiter never sees their frames, so the main limit does
 * nothing for them).
 *
 * The caller suspends the client's socket reads for the returned delay, which blocks the game
 * inside its own per-frame XSync; the blitted frame itself is still displayed immediately.
 *
 * The target FPS is not stored here: WinLite keeps a single source of truth (the renderer view's
 * fpsLimit, the same value PresentExtension reads), and the caller passes it in on every frame.
 */
public class ShmFramePacer {
    private static class Timing { long nextNs; }

    private static final ConcurrentHashMap<Integer, Timing> timings = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_DRAWABLES = 128;

    private ShmFramePacer() {}

    /** Drops all pacing state, e.g. when a new X server session starts. */
    public static void reset() {
        timings.clear();
    }

    /**
     * Registers a presented frame for the drawable.
     *
     * @param fps target FPS; 0 (or less) means no limit
     * @return how long (ns) the caller should suspend this client's reads, or 0
     */
    public static long framePresented(int drawableId, int fps) {
        if (fps <= 0) {
            if (!timings.isEmpty()) timings.clear();
            return 0;
        }
        if (timings.size() > MAX_TRACKED_DRAWABLES) evictStalest(drawableId);
        Timing t = timings.computeIfAbsent(drawableId, k -> new Timing());
        long now = System.nanoTime();
        if (t.nextNs < now) t.nextNs = now;
        long delay = t.nextNs - now;
        t.nextNs += 1_000_000_000L / fps;
        return delay;
    }

    /* Evicts the longest-idle entry (smallest nextNs), never the presenting drawable, so an
     * overflow frees one slot without resetting active pacing. */
    private static void evictStalest(int presentingDrawableId) {
        Integer evict = null;
        long oldest = Long.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Timing> e : timings.entrySet()) {
            if (e.getKey() == presentingDrawableId) continue;
            long nextNs = e.getValue().nextNs;
            if (nextNs < oldest) {
                oldest = nextNs;
                evict = e.getKey();
            }
        }
        if (evict != null) timings.remove(evict);
    }
}
