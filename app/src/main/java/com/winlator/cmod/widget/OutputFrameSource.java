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
}
