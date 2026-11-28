package com.winlator.cmod.widget;

/**
 * The output FPS for the HUDs, based on display timing when available. The queue estimate is
 * measured between whole bursts, using their timestamps from the renderer.
 *
 * <p>Thread-safe: the source may be swapped from another thread than the one reading.
 */
final class OutputRateMeter {
    /** No new sample for this long: the output stalled. */
    private static final long STALL_NS = 1_000_000_000L;

    static final class Reading {
        static final Reading EMPTY = new Reading(0f, false);
        final float fps;
        final boolean estimated;

        Reading(float fps, boolean estimated) {
            this.fps = fps;
            this.estimated = estimated;
        }
    }

    private final long[] counts = new long[5];
    private long lastFrames = -1;
    private long lastNs = 0;
    private long lastGenerated = -1;
    private boolean lastEstimated;

    /**
     * Returns a reading after at least {@code minSpanNs}. An empty reading clears the HUD.
     * A null reading keeps the previous value while a sample accumulates.
     */
    synchronized Reading take(OutputFrameSource source, long nowNs, long minSpanNs) {
        if (source == null || !source.readFrameCounts(counts)) {
            reset();
            return Reading.EMPTY;
        }
        final long generated = counts[1], frames = counts[2], ns = counts[3];
        final boolean estimated = counts[4] != 0;
        if (lastNs == 0 || ns <= 0 || frames < lastFrames || generated < lastGenerated ||
                ns < lastNs || estimated != lastEstimated || nowNs - ns >= STALL_NS) {
            // Never calculate a rate across clock changes or across a stalled output.
            lastFrames = frames;
            lastNs = ns;
            lastGenerated = generated;
            lastEstimated = estimated;
            return Reading.EMPTY;
        }
        final long span = ns - lastNs;
        if (span < minSpanNs) return null;
        final boolean generating = generated > lastGenerated;
        final float fps = generating ? (frames - lastFrames) * 1_000_000_000f / span : 0f;
        lastFrames = frames;
        lastNs = ns;
        lastGenerated = generated;
        return generating ? new Reading(fps, estimated) : Reading.EMPTY;
    }

    synchronized void reset() {
        lastFrames = -1;
        lastNs = 0;
        lastGenerated = -1;
    }
}
