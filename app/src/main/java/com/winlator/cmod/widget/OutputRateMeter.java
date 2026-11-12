package com.winlator.cmod.widget;

/**
 * The output fps (real and WFG-generated frames shown) for the HUDs.
 *
 * <p>The renderer sends a real frame and its generated ones as one burst of presents, so the
 * presented count grows in steps of the WFG multiplier. Taken per HUD tick, a 400 ms tick over
 * 30 game fps holds 12 or 13 bursts and x2 read 58..65, x3 87..97. Here the span runs from the
 * time of one burst to another's (both stamped by the renderer), so it always holds whole bursts.
 *
 * <p>Thread-safe: the source may be swapped from another thread than the one reading.
 */
final class OutputRateMeter {
    /** No burst for this long: the output stalled, the reading drops to 0. */
    private static final long STALL_NS = 1_000_000_000L;

    private final long[] counts = new long[4];
    private long lastFrames = -1;
    private long lastNs = 0;
    private long lastGenerated = -1;

    /**
     * The output fps since the previous reading, once the bursts span at least {@code minSpanNs}.
     * {@code 0}: nothing generated since then (no WFG), or no renderer, or a stall; {@code -1}:
     * not enough yet, keep showing the previous value.
     */
    synchronized float take(OutputFrameSource source, long nowNs, long minSpanNs) {
        if (source == null || !source.readFrameCounts(counts)) {
            reset();
            return 0f;
        }
        final long generated = counts[1], frames = counts[2], ns = counts[3];
        if (lastFrames < 0 || ns <= 0 || frames < lastFrames || ns < lastNs) {
            // First read, or a new renderer: start from here.
            lastFrames = frames;
            lastNs = ns;
            lastGenerated = generated;
            return 0f;
        }
        if (nowNs - ns >= STALL_NS) {
            lastFrames = frames;
            lastNs = ns;
            lastGenerated = generated;
            return 0f;
        }
        final long span = ns - lastNs;
        if (span < minSpanNs) return -1f;
        final boolean generating = generated > lastGenerated;
        final float fps = generating ? (frames - lastFrames) * 1_000_000_000f / span : 0f;
        lastFrames = frames;
        lastNs = ns;
        lastGenerated = generated;
        return fps;
    }

    synchronized void reset() {
        lastFrames = -1;
        lastNs = 0;
        lastGenerated = -1;
    }
}
