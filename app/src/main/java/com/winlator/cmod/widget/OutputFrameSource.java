package com.winlator.cmod.widget;

/**
 * What the display shows beyond the game's own frames: the HUD reads it to print
 * "game fps → output fps" while WFG generates frames.
 */
public interface OutputFrameSource {
    /** Frames presented to the display, real and generated, since the renderer started. */
    long getPresentedFrameCount();

    /** Frames WFG generated since the renderer started. */
    long getGeneratedFrameCount();

    /**
     * One consistent read into {@code out} (4 longs): {presented, generated, presented as of the
     * latest real frame's burst of presents, that burst's time in ns on the
     * {@link System#nanoTime()} clock}. The output fps is taken from burst to burst, see
     * {@link OutputRateMeter}. False: no renderer.
     */
    default boolean readFrameCounts(long[] out) {
        out[0] = getPresentedFrameCount();
        out[1] = getGeneratedFrameCount();
        out[2] = out[0];
        out[3] = System.nanoTime();
        return true;
    }
}
