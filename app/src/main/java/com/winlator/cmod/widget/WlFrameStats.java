package com.winlator.cmod.widget;

import com.winlator.cmod.core.WlControl;

/**
 * The game's frames as the Vulkan wrapper's WL swapchain counts them (wl.control status), for the
 * HUDs: with WL Direct the game's frames never reach the X server, and with frame generation the
 * X server sees the generated ones too, so its count is neither the game's nor the output's.
 *
 * <p>Live while the counters move (a game with a WL swapchain); otherwise the HUDs keep their
 * X server reading. Thread-safe.
 */
final class WlFrameStats {
    /** No new frame for this long: not live any more. */
    private static final long STALE_NS = 1_000_000_000L;

    static final class Reading {
        boolean live;
        int sink, reason, fgState;
        /** -1: not enough time since the previous reading, keep the shown values. */
        float gameFps = -1, outputFps = -1;
    }

    private final WlControl.Status status = new WlControl.Status();
    private final Reading reading = new Reading();
    private final int[] directStatus = new int[3];
    private long lastReal = -1, lastGenerated, lastNs, lastChangeNs;
    private int lastPid;

    /** At {@code nowNs} ({@link System#nanoTime()}), over at least {@code minSpanNs}. */
    synchronized Reading take(long nowNs, long minSpanNs, OutputFrameSource outputSource) {
        reading.gameFps = -1;
        reading.outputFps = -1;
        if (!WlControl.readStatus(status)) {
            reading.live = false;
            lastReal = -1;
            return reading;
        }
        // DirectServer alone decides where the game's frames go. wl.control is shared by every
        // swapchain that inherited its path, so its sink/reason may belong to another client.
        if (outputSource != null && outputSource.readDirectStatus(directStatus)) {
            reading.sink = directStatus[0] == 0 ? WlControl.SINK_X : WlControl.SINK_DIRECT;
            reading.reason = directStatus[1];
        } else {
            reading.sink = status.sink;
            reading.reason = status.reason;
        }
        reading.fgState = status.fgState;
        final long real = status.realFrames, generated = status.generatedFrames;
        if (lastReal < 0 || status.pid != lastPid || real < lastReal || generated < lastGenerated) {
            // First reading, or another swapchain: start from here.
            lastReal = real;
            lastGenerated = generated;
            lastNs = nowNs;
            lastChangeNs = 0;
            lastPid = status.pid;
            reading.live = false;
            return reading;
        }
        if (real != lastReal || generated != lastGenerated) lastChangeNs = nowNs;
        reading.live = lastChangeNs != 0 && nowNs - lastChangeNs < STALE_NS;
        final long span = nowNs - lastNs;
        if (span < minSpanNs) return reading;
        final float game = (real - lastReal) * 1_000_000_000f / span;
        final float made = (generated - lastGenerated) * 1_000_000_000f / span;
        reading.gameFps = game;
        reading.outputFps = made > 0 ? game + made : 0f;
        lastReal = real;
        lastGenerated = generated;
        lastNs = nowNs;
        return reading;
    }

    /** "Direct", "X" or "X: overlap"-style label of where the game's frames go. */
    static String sinkLabel(Reading r) {
        if (r.sink == WlControl.SINK_DIRECT) return "Direct";
        if (r.sink != WlControl.SINK_X) return "";
        // Why not Direct, unless that says nothing (no emulator to talk to, waiting to switch).
        if (r.reason == 0 || r.reason == 2 || r.reason == 13) return "X";
        return "X: " + WlControl.reasonName(r.reason);
    }
}
