package com.winlator.cmod.core;

import com.winlator.cmod.widget.VulkanXServerView;

/**
 * wl.control: the settings WinLite hands the Vulkan wrapper's WL swapchain in the game's process
 * (Direct output mode, the FPS limit, frame generation) and the swapchain's status for the HUD.
 * A 128-byte file the guest maps (WL_CONTROL_PATH), see app/src/main/cpp/winlator/wl_proto.h.
 *
 * <p>The settings are kept here as they change and go into the page whenever there is one, so it
 * does not matter whether the UI sets them before or after {@link #open} made the file. The
 * swapchain reads them on every frame: a change applies at once, a running game included.
 */
public final class WlControl {
    /** wl_output_mode. */
    public static final int OUTPUT_OFF = 0, OUTPUT_AUTO = 1, OUTPUT_ALWAYS = 2;
    /** wl_sink. */
    public static final int SINK_NONE = 0, SINK_X = 1, SINK_DIRECT = 2;
    /** wl_fg_state. */
    public static final int FG_OFF = 0, FG_ACTIVE = 1, FG_PAUSED = 2;

    private static final int FG_MODE_OFF = 0, FG_MODE_FIXED = 1, FG_MODE_TARGET = 2;
    private static final int FG_FAST_INTERP = 1;

    private static int outputMode = OUTPUT_AUTO;
    private static int fpsLimit = 0;
    private static int fgMode = FG_MODE_OFF;
    private static int fgMultiplier = 2;
    private static int fgFlags = 0;
    private static int fgTargetFps = 0;
    private static int fgFlowSide = 0;

    private WlControl() {}

    private static native boolean nativeOpen(String path);
    private static native void nativeSetSettings(int[] values);
    private static native boolean nativeReadStatus(long[] out);

    static {
        VulkanXServerView.loadNativeLibrary();
    }

    /** Creates the file at {@code path} (before Wine starts), with the settings so far. */
    public static synchronized boolean open(String path) {
        boolean ok = nativeOpen(path);
        push();
        return ok;
    }

    /** Direct output: {@link #OUTPUT_OFF}, {@link #OUTPUT_AUTO} or {@link #OUTPUT_ALWAYS}. */
    public static synchronized void setOutputMode(int mode) {
        outputMode = Math.max(OUTPUT_OFF, Math.min(OUTPUT_ALWAYS, mode));
        push();
    }

    /** The game's FPS limit, 0 = none; the swapchain applies it when the game acquires a frame. */
    public static synchronized void setFpsLimit(int fps) {
        fpsLimit = Math.max(0, Math.min(1000, fps));
        push();
    }

    /**
     * Frame generation in the wrapper.
     * @param flowSide   pixels on the frame's shorter side the motion is analysed at
     * @param fastInterp the Fast preset's cheaper interpolation
     * @param targetFps  output rate to reach, 0 = the display's refresh rate (multiplier 0)
     * @param multiplier 2..4: that many frames per game frame; 0: chase targetFps
     */
    public static synchronized void setFrameGeneration(boolean enabled, int flowSide, boolean fastInterp,
                                                       int targetFps, int multiplier) {
        fgMode = !enabled ? FG_MODE_OFF : (multiplier >= 2 ? FG_MODE_FIXED : FG_MODE_TARGET);
        fgMultiplier = Math.max(2, Math.min(4, multiplier >= 2 ? multiplier : 2));
        fgFlags = fastInterp ? FG_FAST_INTERP : 0;
        fgTargetFps = Math.max(0, targetFps);
        fgFlowSide = Math.max(0, flowSide);
        push();
    }

    private static void push() {
        // fg_min_fps / fg_resume_fps: 0, the generator's own floor (it stops below 12 fps of
        // source and starts again above 15 in the target mode).
        nativeSetSettings(new int[] {outputMode, fgMode, fgMultiplier, fgFlags, fpsLimit,
                fgTargetFps, fgFlowSide, 0, 0});
    }

    /** The swapchain's status. */
    public static final class Status {
        public int pid, sink, fgState, reason;
        public long realFrames, generatedFrames, droppedFrames;
    }

    private static final long[] statusBuf = new long[7];

    /** False: no page yet, or no WL swapchain has written to it. */
    public static synchronized boolean readStatus(Status out) {
        if (!nativeReadStatus(statusBuf)) return false;
        out.pid = (int) statusBuf[0];
        out.sink = (int) statusBuf[1];
        out.fgState = (int) statusBuf[2];
        out.reason = (int) statusBuf[3];
        out.realFrames = statusBuf[4];
        out.generatedFrames = statusBuf[5];
        out.droppedFrames = statusBuf[6];
        return true;
    }

    /** A short name for a wl_direct_reason, for the HUD. */
    public static String reasonName(int reason) {
        switch (reason) {
            case 0: return "ok";
            case 1: return "off";
            case 2: return "settling";
            case 3: return "no window";
            case 4: return "not top";
            case 5: return "windowed";
            case 6: return "overlap";
            case 7: return "rotation";
            case 8: return "scissor";
            case 9: return "detached";
            case 10: return "API < 29";
            case 11: return "other client";
            case 12: return "format";
            case 13: return "error";
            default: return "?";
        }
    }
}
