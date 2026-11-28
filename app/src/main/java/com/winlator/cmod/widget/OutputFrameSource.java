package com.winlator.cmod.widget;

/**
 * The renderer's output, including generated frames: the HUD reads it to print
 * "game fps → output fps" while WFG generates frames.
 */
public interface OutputFrameSource {
    /**
     * Snapshot after the latest game batch was queued: total queued frames, queued WFG frames,
     * and the timestamp on the {@link System#nanoTime()} clock. False when no renderer is active.
     */
    boolean readFrameCounts(long[] out);
}
