package com.winlator.cmod.widget;

/**
 * What the display shows beyond the game's own frames: the HUD reads it to print
 * "game fps → output fps" while WFG generates frames.
 */
public interface OutputFrameSource {
    /** Successful queue presents, including repaints and copies of real frames. */
    long getPresentedFrameCount();

    /** Generated frames in the current HUD sample's counter. */
    long getGeneratedFrameCount();

    /**
     * One sample into {@code out}: {queued presents, generated output, unique output,
     * sample ns on the {@link System#nanoTime()} clock, 1 if based on queue time}.
     * The output counters share a clock and exclude copies of the same real frame.
     * False if no renderer is available.
     */
    default boolean readFrameCounts(long[] out) {
        out[0] = getPresentedFrameCount();
        out[1] = getGeneratedFrameCount();
        out[2] = out[0];
        out[3] = System.nanoTime();
        if (out.length >= 5) out[4] = 1;
        return true;
    }
}
