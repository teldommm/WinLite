package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.GPUInformation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class WinlatorHUD extends View {
    public static final String PREFS = "winlator_hud";
    private static final String KEY_X = "hud_x";
    private static final String KEY_Y = "hud_y";
    private static final String KEY_VIS = "hud_vis";
    public static final String KEY_SHOW = "hud_show";
    private static final String KEY_SHOW_V2 = "hud_show_v2";
    private static final String KEY_SCALE = "hud_scale";
    private static final String KEY_ALPHA = "hud_alpha_int";
    private static final String KEY_VERT = "hud_vertical";
    private static final String KEY_ALIGN_RIGHT = "hud_align_right";
    public static final String KEY_DUAL_CELL = "hud_dual_cell_correction";

    public static final int SHOW_FPS = 1;
    public static final int SHOW_GPU_USAGE = 1 << 1;
    public static final int SHOW_CPU_USAGE = 1 << 2;
    public static final int SHOW_POWER = 1 << 3;
    public static final int SHOW_RENDERER = 1 << 5;
    public static final int SHOW_RAM = 1 << 6;
    public static final int SHOW_GPU_NAME = 1 << 7;
    public static final int SHOW_CPU_TEMP = 1 << 8;
    public static final int SHOW_BATTERY_TEMP = 1 << 9;
    public static final int SHOW_CHARGE_STATE = 1 << 10;

    public static final int SHOW_GPU = SHOW_GPU_USAGE;
    public static final int SHOW_CPU = SHOW_CPU_USAGE;
    public static final int SHOW_BATT = SHOW_POWER;

    private static final int LEGACY_SHOW_DEFAULT = 0x6F;
    private static final int SHOW_DEFAULT = SHOW_FPS | SHOW_GPU_USAGE | SHOW_CPU_USAGE
            | SHOW_POWER | SHOW_RENDERER | SHOW_RAM | SHOW_GPU_NAME | SHOW_CPU_TEMP
            | SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE;

    private static final int C_BG = Color.argb(180, 0, 0, 0);
    private static final int C_WHITE = Color.WHITE;
    private static final int C_GPU_NAME = Color.rgb(0xA9, 0xD6, 0xFF);
    private static final int C_GPU_USAGE = Color.rgb(0xE0, 0x40, 0xFB);
    private static final int C_CPU = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int C_BATT = Color.rgb(0xFF, 0x80, 0x00);
    private static final int C_CHG = Color.rgb(0x40, 0xC4, 0x40);
    private static final int C_TEMP = Color.rgb(0xEF, 0x53, 0x50);
    private static final int C_FPS = Color.rgb(0x76, 0xFF, 0x03);
    private static final int C_REND = Color.rgb(0xFF, 0xEA, 0x00);
    private static final int C_RAM = Color.rgb(0xB0, 0xFF, 0xB0);
    private static final int C_SEP = Color.rgb(0x60, 0x60, 0x60);

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private static final long STATS_INTERVAL_MS = 1500L;
    private static final float DRAG_THRESH = 10f;

    private static final String[] GPU_STATIC_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/devices/platform/gpusysfs/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/pvr/utilisation",
            "/sys/class/pvr/gpu_utilisation",
            "/sys/class/drm/card0/device/gpu_busy_percent",
            "/sys/class/devfreq/gpu/load",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading",
            "/proc/mtk_mali/utilization"
    };

    private static final String[] GPU_USAGE_FILES = {
            "gpu_busy_percentage", "gpu_busy_percent", "gpu_load", "utilisation",
            "utilization", "load", "gpu_busy"
    };
    // (Mali's "gpuinfo" used to be read here as a busy-time counter; it is a static description
    // like "Mali-G710 10 cores r0p0 0xA862", so it produced either nothing or a garbage percent.)

    private static final String[] GPU_NODE_TOKENS = {
            "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    };

    private static final String[] CURRENT_CHANNELS = {
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/main/current_now"
    };

    private static final String[] VOLTAGE_CHANNELS = {
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/main/voltage_now"
    };

    private static final String[] POWER_CHANNELS = {
            "/sys/class/power_supply/battery/power_now",
            "/sys/class/power_supply/bms/power_now",
            "/sys/class/power_supply/main/power_now"
    };

    private float TS, TSR, PAD, CORNER;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal = new Paint(TEXT_FLAGS);
    private final Paint pGpuName = new Paint(TEXT_FLAGS);
    private final Paint pGpuUsage = new Paint(TEXT_FLAGS);
    private final Paint pCpu = new Paint(TEXT_FLAGS);
    private final Paint pBat = new Paint(TEXT_FLAGS);
    private final Paint pTmp = new Paint(TEXT_FLAGS);
    private final Paint pFps = new Paint(TEXT_FLAGS);
    private final Paint pRend = new Paint(TEXT_FLAGS);
    private final Paint pRam = new Paint(TEXT_FLAGS);
    private final Paint pSep = new Paint(TEXT_FLAGS);
    private final Paint pChg = new Paint(TEXT_FLAGS);
    private final RectF bgRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelFps, wSep;
    private float wVal100pct, wValCpuTemp, wValFps, wValWatt, wValTemp, wChg, wChgStandalone;
    private float wInnerSpace;

    private volatile String strGpu = "N/A", strCpuUsage = "N/A", strCpuTemp = "";
    private volatile String strRam = "N/A", strPwr = "N/A", strTmp = "", strFps = "0";
    private volatile String strRend = "Vulkan", gpuNameLabel = "";

    private volatile float wDynGpu, wDynCpuUsage, wDynCpuTemp, wDynRam, wDynPwr;
    private volatile float wDynTmp, wDynFps, wDynRend, wDynGpuName;

    private int lastBgAlpha = -1;
    private volatile int showMask = SHOW_DEFAULT;
    private float hudAlpha = 1f;
    private volatile boolean userEnabled = false;
    private volatile boolean rendererActive = false;
    private boolean vertical = false;

    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (!KEY_SHOW.equals(key)) return;
                final int newMask = sharedPreferences.getInt(KEY_SHOW, SHOW_DEFAULT);
                uiHandler.post(() -> {
                    if (showMask == newMask) return;
                    showMask = newMask;
                    requestRelayout();
                });
            };

    private final AtomicInteger frameAccum = new AtomicInteger(0);
    private long lastFpsNs = 0;
    private float snapFps = 0;
    private volatile float frameGenPresentedRate = 0f;

    private int snapGpu = -1, snapCpu = -1, snapCpuTemp = -1;
    // Battery power magnitude in mW; PWR_NA when unavailable.
    private static final int PWR_NA = Integer.MIN_VALUE;
    // |PWR| below this (mW) is shown without a sign: it rounds to 0.0W.
    private static final int PWR_SIGN_MIN_MW = 50;
    private int snapMw = PWR_NA;
    // Sign shown with PWR: 1 = "+", -1 = "−", 0 = none (direction unknown).
    private int snapPwrSign = 0;
    private int snapTmp = -1, snapPct = -1, snapRam = -1;
    private volatile boolean snapCharging = false;

    private volatile String rendererLabel = "Vulkan";
    private boolean isNative = false;
    private boolean mesaRendererActive = false;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;

    // The view is measured at its *reserved* width (worst-case values, CHG slot, ...), but only
    // the current content is drawn. It used to be drawn at the left of that box, so the empty
    // reserved part kept the visible HUD away from the right screen edge (in horizontal layout
    // by a lot). Now the content hugs the box edge on the side of the screen it sits on, the
    // clamp works on the visible part, and on the right side the HUD grows to the left.
    private boolean alignRight = false;
    private long touchDownMs = 0;
    private boolean redrawScheduled = false;

    private HandlerThread statsThread = null;
    private Handler statsHandler = null;
    private final Runnable statsRunnable = this::doStats;

    private final BatteryManager batteryManager;
    private final IntentFilter batteryIntentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

    private String[] gpuPaths = new String[0];
    // Only "no readable GPU node exists at all". Read failures are never latched any more:
    // the old code gave up for good after 3 failed reads and left the last value frozen.
    private boolean gpuUnavailable;
    private int gpuConsecutiveFailures;
    private int battConsecutiveFailures;
    // After this many failed reads in a row the metric shows N/A (and keeps being retried).
    private static final int FAILURES_BEFORE_NA = 3;

    // Frame-generation rate is only trusted while it is fresh and real frames are arriving;
    // otherwise a stalled game kept showing the last generated rate (e.g. 120) instead of 0.
    private static final long FRAME_GEN_RATE_MAX_AGE_NS = 500_000_000L;
    private volatile long frameGenRateAtNs = 0L;

    // Set on the stats thread when a value string changed; widths are then measured on the UI
    // thread (Paint isn't thread-safe, and the UI thread draws with the same Paints).
    private volatile boolean statsDirty = false;
    private boolean layoutCharging = false;

    private final Runnable applyStatsRunnable = this::applyStatsOnUi;
    private final View.OnLayoutChangeListener parentLayoutListener =
            (v, l, t, r, b, ol, ot, orr, ob) -> clampToParent(false);

    private final Runnable redrawRunnable = () -> {
        redrawScheduled = false;
        try {
            snapshot();
            invalidate();
        } catch (Exception ignored) {}
        if (getVisibility() == VISIBLE) scheduleRedraw();
    };

    public WinlatorHUD(Context context) {
        this(context, null);
    }

    public WinlatorHUD(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        float density = context.getResources().getDisplayMetrics().density;
        TS = 12f * density;
        TSR = 11f * density;
        PAD = 6f * density;
        CORNER = 5f * density;

        // Scale from the top-left corner, so the saved x/y is where the HUD's corner really is
        // (scaling around the centre pushed a scaled-up HUD past the screen edge).
        setPivotX(0f);
        setPivotY(0f);

        initPaints(density);
        detectGpuPathsOnce();
        detectGpuNameOnce();
        loadPrefs();
        dropLegacyPowerPrefs();
        refreshBackendRenderer(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void dropLegacyPowerPrefs() {
        if (prefs.contains(LEGACY_KEY_DRAIN_SIGN_BM) || prefs.contains(LEGACY_KEY_DRAIN_SIGN_SYSFS)) {
            prefs.edit()
                    .remove(LEGACY_KEY_DRAIN_SIGN_BM)
                    .remove(LEGACY_KEY_DRAIN_SIGN_SYSFS)
                    .apply();
        }
    }

    private void initPaints(float density) {
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        pBg.setStyle(Paint.Style.FILL);

        pVal.setTextSize(TS); pVal.setTypeface(mono); pVal.setColor(C_WHITE);
        pGpuName.setTextSize(TS); pGpuName.setTypeface(mono); pGpuName.setColor(C_GPU_NAME);
        pGpuUsage.setTextSize(TS); pGpuUsage.setTypeface(mono); pGpuUsage.setColor(C_GPU_USAGE);
        pCpu.setTextSize(TS); pCpu.setTypeface(mono); pCpu.setColor(C_CPU);
        pBat.setTextSize(TS); pBat.setTypeface(mono); pBat.setColor(C_BATT);
        pTmp.setTextSize(TS); pTmp.setTypeface(mono); pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS); pFps.setTypeface(mono); pFps.setColor(C_FPS);
        pRend.setTextSize(TSR); pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS); pRam.setTypeface(mono); pRam.setColor(C_RAM);
        pSep.setTextSize(TS); pSep.setTypeface(mono); pSep.setColor(C_SEP);
        pChg.setTextSize(TS); pChg.setTypeface(mono); pChg.setColor(C_CHG);

        wLabelGpu = pGpuUsage.measureText("GPU ");
        wLabelCpu = pCpu.measureText("CPU ");
        wLabelRam = pRam.measureText("RAM ");
        wLabelPwr = pBat.measureText("PWR ");
        wLabelTmp = pTmp.measureText("BAT ");
        wLabelFps = pFps.measureText("FPS ");
        wSep = pSep.measureText(" | ");
        wInnerSpace = pVal.measureText(" ");

        wVal100pct = pVal.measureText("100%");
        wValCpuTemp = pVal.measureText("150°C");
        wValFps = pVal.measureText("9999") + 2f * density;
        wValWatt = Math.max(pVal.measureText("+99.9W"), pVal.measureText("\u221299.9W"));
        wValTemp = pVal.measureText("150°C");
        wChg = pChg.measureText(" CHG");
        wChgStandalone = pChg.measureText("CHG");

        wDynGpu = pVal.measureText(strGpu);
        wDynCpuUsage = pVal.measureText(strCpuUsage);
        wDynCpuTemp = pVal.measureText(strCpuTemp);
        wDynRam = pVal.measureText(strRam);
        wDynPwr = pVal.measureText(strPwr);
        wDynTmp = pVal.measureText(strTmp);
        wDynFps = pVal.measureText(strFps);
        wDynRend = pRend.measureText(strRend);
        wDynGpuName = 0f;
    }

    private void detectGpuNameOnce() {
        try {
            String name = sanitizeGpuName(GPUInformation.getRenderer(null, getContext()));
            if (isUsefulGpuName(name)) {
                gpuNameLabel = name;
                wDynGpuName = pGpuName.measureText(gpuNameLabel);
            }
        } catch (Throwable ignored) {}
    }

    private String sanitizeGpuName(String name) {
        if (name == null) return "";
        return name.trim().replaceFirst("(?i)^wrapper\\s*[:\\-]?\\s*", "").trim();
    }

    private boolean isUsefulGpuName(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        return !"unknown".equals(lower) && !"device".equals(lower) && !"n/a".equals(lower);
    }

    private String resolveBackendRenderer() {
        return "Vulkan";
    }

    private String readShortcutExtra(String path, String key) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isFile()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            boolean inExtraData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    inExtraData = "[Extra Data]".equals(line);
                    continue;
                }
                if (!inExtraData) continue;
                String prefix = key + "=";
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }
    private void refreshBackendRenderer(boolean relayout) {
        if (mesaRendererActive) return;
        rendererLabel = resolveBackendRenderer();
        strRend = rendererLabel;
        wDynRend = pRend.measureText(strRend);
        if (relayout) requestRelayout();
    }

    private void detectGpuPathsOnce() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String path : GPU_STATIC_PATHS) {
            try {
                if (new File(path).canRead()) found.add(path);
            } catch (Exception ignored) {}
        }

        scanGpuNodesOnce(new File("/sys/class/devfreq"), found);
        scanGpuNodesOnce(new File("/sys/devices/virtual/devfreq"), found);
        scanPlatformGpuNodesOnce(new File("/sys/devices/platform"), found);

        gpuPaths = found.toArray(new String[0]);
        gpuUnavailable = gpuPaths.length == 0;
    }

    private void scanGpuNodesOnce(File root, LinkedHashSet<String> found) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                if (!looksLikeGpuNode(node.getPath())) continue;
                addGpuUsageFiles(node, found);
            }
        } catch (Exception ignored) {}
    }

    private void scanPlatformGpuNodesOnce(File root, LinkedHashSet<String> found) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                if (!looksLikeGpuNode(node.getName())) continue;
                addGpuUsageFiles(node, found);
            }
        } catch (Exception ignored) {}
    }

    private void addGpuUsageFiles(File node, LinkedHashSet<String> found) {
        for (String fileName : GPU_USAGE_FILES) {
            File candidate = new File(node, fileName);
            if (candidate.canRead()) found.add(candidate.getPath());
        }
    }

    private boolean looksLikeGpuNode(String path) {
        String lower = path.toLowerCase(Locale.US);
        for (String token : GPU_NODE_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    public void onFrame() {
        if (!rendererActive && !userEnabled) return;
        frameAccum.incrementAndGet();
    }

    public void update() {
        onFrame();
    }

    /** Presents/sec incl. generated frames, pushed once per real frame while frame gen is
     *  actually running; 0 means "not generating right now", so the plain measured FPS shows. */
    public void setFrameGenPresentedRate(float rate) {
        frameGenPresentedRate = rate;
        frameGenRateAtNs = System.nanoTime();
    }

    public void setIsNative(boolean n) {
        isNative = n;
        if (!mesaRendererActive) uiHandler.post(() -> refreshBackendRenderer(true));
    }

    private void doStats() {
        try {
            readStats();
        } catch (Exception ignored) {}

        // Reschedule only on the looper this run belongs to: after a stop/start the field may
        // already point at a new thread, and posting there would start a second cadence.
        Handler handler = statsHandler;
        if (userEnabled && handler != null && handler.getLooper() == Looper.myLooper()) {
            handler.postDelayed(statsRunnable, STATS_INTERVAL_MS);
        }
        uiHandler.post(applyStatsRunnable);
    }

    // UI thread: measure whatever the stats thread changed, then redraw. Relayout when the
    // charge state flips, since that changes the vertical row count / reserved width.
    private void applyStatsOnUi() {
        if (statsDirty) {
            statsDirty = false;
            wDynGpu = pVal.measureText(strGpu);
            wDynCpuUsage = pVal.measureText(strCpuUsage);
            wDynCpuTemp = pVal.measureText(strCpuTemp);
            wDynRam = pVal.measureText(strRam);
            wDynPwr = pVal.measureText(strPwr);
            wDynTmp = pVal.measureText(strTmp);
        }
        boolean charging = snapCharging;
        if (charging != layoutCharging) {
            layoutCharging = charging;
            requestRelayout();
        } else {
            invalidate();
        }
        // Content width follows the values; keep the visible HUD on screen as it changes.
        clampToParent(false);
    }

    private void readStats() {
        int mask = showMask;
        if ((mask & SHOW_GPU_USAGE) != 0) readGpu();
        if ((mask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) readCpu();
        if ((mask & SHOW_RAM) != 0) readRam();
        if ((mask & (SHOW_POWER | SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE)) != 0) readBattery();
    }

    private void readGpu() {
        if (gpuUnavailable) return;

        int value = -1;
        for (String path : gpuPaths) {
            value = readGpuSample(path);
            if (value >= 0) break;
        }

        if (value >= 0) {
            gpuConsecutiveFailures = 0;
            if (value != snapGpu) {
                snapGpu = value;
                strGpu = value + "%";
                statsDirty = true;
            }
            return;
        }

        // Keep retrying every cycle; after a few misses show N/A rather than a stale number.
        if (++gpuConsecutiveFailures >= FAILURES_BEFORE_NA && snapGpu != -1) {
            snapGpu = -1;
            strGpu = "N/A";
            statsDirty = true;
        }
    }

    private int readGpuSample(String path) {
        try {
            if (path.endsWith("/gpubusy")) {
                String line = readFirstLine(path);
                if (line == null) return -1;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) return -1;
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                // "0 0" = the GPU was idle / power-collapsed for the whole window: that's 0%,
                // not a failed read (treating it as a failure is what froze the GPU value).
                if (total <= 0L) return 0;
                return clampPercent((int) ((busy * 100L) / total));
            }

            if (path.endsWith("/proc/mtk_mali/utilization")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int idx = line.indexOf("ACTIVE=");
                        if (idx < 0) continue;
                        int start = idx + 7;
                        int end = line.indexOf(' ', start);
                        String token = end > start
                                ? line.substring(start, end) : line.substring(start);
                        token = token.replaceAll("[^0-9]", "");
                        if (!token.isEmpty()) {
                            return clampPercent(Integer.parseInt(token));
                        }
                    }
                }
                return -1;
            }

            return readPercent(path);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int readPercent(String path) {
        String line = readFirstLine(path);
        if (line == null) return -1;
        for (String token : line.trim().split("\\s+")) {
            // devfreq "load" is "<busy%>@<freq>" (e.g. "45@585000000Hz"): only the part
            // before '@' is the load. Gluing all digits together overflowed parseInt.
            int at = token.indexOf('@');
            if (at >= 0) token = token.substring(0, at);
            String digits = token.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return clampPercent(Integer.parseInt(digits));
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void readCpu() {
        int usage = CPUStatus.getCpuUsagePercent();
        int temp = CPUStatus.getCpuTempC();
        if (usage == snapCpu && temp == snapCpuTemp) return;

        snapCpu = usage;
        snapCpuTemp = temp;
        strCpuUsage = usage >= 0 ? usage + "%" : "N/A";
        strCpuTemp = temp > 0 ? temp + "°C" : "N/A";
        statsDirty = true;
    }

    private void readRam() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1;
            long avail = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseMeminfoKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    avail = parseMeminfoKb(line);
                    break;
                }
            }

            int value = (total > 0 && avail >= 0)
                    ? (int) (100L * (total - avail) / total) : -1;
            if (value != snapRam) {
                snapRam = value;
                strRam = value >= 0 ? value + "%" : "N/A";
                statsDirty = true;
            }
        } catch (Exception e) {
            if (snapRam != -1) {
                snapRam = -1;
                strRam = "N/A";
                statsDirty = true;
            }
        }
    }

    private long parseMeminfoKb(String line) {
        try {
            return Long.parseLong(line.trim().split("\\s+")[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private void readBattery() {
        try {
            // Fresh sticky intent on every read (registerReceiver(null, ...) is one cheap binder
            // call, no receiver is registered), so plug state, status and the PWR sign derived
            // from them always describe the same moment.
            Intent batt = getContext().registerReceiver(null, batteryIntentFilter);
            if (batt == null) return;

            int rawTemp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int tempC = rawTemp > 0 ? Math.round(rawTemp / 10f) : -1;
            if (tempC != snapTmp) {
                snapTmp = tempC;
                strTmp = tempC > 0 ? tempC + "°C" : "N/A";
                statsDirty = true;
            }

            snapPct = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            // CHG = on external power. Status alone missed plugged-in states that report
            // NOT_CHARGING / DISCHARGING: charge limit (80-85 %), battery protection, thermal
            // pause and bypass ("power the device, not the battery") gaming modes.
            boolean charging = plugged != 0
                    || status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            int voltageMv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (voltageMv <= 0) {
                long voltageUv = readFirstSysFsLong(VOLTAGE_CHANNELS);
                if (voltageUv != 0L) {
                    voltageMv = (int) (Math.abs(voltageUv) / 1000L);
                }
            }

            // Direction comes only from the state reported in this same sticky intent, never
            // from the sign of CURRENT_NOW / current_now: that sign differs between devices, and
            // some kernels report the magnitude only. It used to be learned and stored in prefs,
            // and a wrongly learned value (plug-in race, unsigned kernel, old builds) showed
            // "-" on the charger in every later session.
            //  - status CHARGING                     -> "+" (matches the CHG label);
            //  - on battery                          -> "-" (nothing else can feed the battery);
            //  - external power, DISCHARGING         -> "-" (charger can't cover the load);
            //  - external power, FULL / NOT_CHARGING -> no sign (bypass / charge limit, ~0 W).
            boolean onExternalPower = plugged != 0;
            boolean statusCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
            boolean statusDischarging = status == BatteryManager.BATTERY_STATUS_DISCHARGING;
            int sign;
            if (statusCharging) sign = 1;
            else if (!onExternalPower) sign = -1;
            else if (statusDischarging) sign = -1;
            else sign = 0;

            float amps = getBatteryCurrentAmps(onExternalPower,
                    !onExternalPower && statusDischarging, statusCharging);
            float watts = (!Float.isNaN(amps) && voltageMv > 0)
                    ? (voltageMv / 1000f) * amps : Float.NaN;

            // Only when the current is unavailable. A real 0 A net current on external power
            // (bypass / charge limit) is a valid reading and must stay 0.0W.
            if (Float.isNaN(watts)) {
                long powerUw = readFirstSysFsLong(POWER_CHANNELS);
                if (powerUw != 0L) watts = Math.abs(powerUw) / 1_000_000f;
            }

            if (!Float.isNaN(watts) && prefs.getBoolean(KEY_DUAL_CELL, false)) {
                watts *= 2f;
            }

            // Magnitude in mW; the direction is kept separately in "sign".
            int mw = Float.isNaN(watts) ? PWR_NA : Math.round(watts * 1000f);
            // Below 50 mW the value prints as 0.0W, so a sign there is noise ("+0.0W"/"−0.0W").
            if (mw != PWR_NA && Math.abs(mw) < PWR_SIGN_MIN_MW) sign = 0;
            boolean chargingChanged = charging != snapCharging;
            snapCharging = charging;

            if (mw != snapMw || sign != snapPwrSign || chargingChanged) {
                snapMw = mw;
                snapPwrSign = sign;
                if (mw == PWR_NA) {
                    strPwr = "N/A";
                } else {
                    String prefix = sign > 0 ? "+" : sign < 0 ? "\u2212" : "";
                    strPwr = prefix + String.format(Locale.US, "%.1fW", Math.abs(mw) / 1000f);
                }
                statsDirty = true;
            }
            battConsecutiveFailures = 0;
        } catch (Exception e) {
            // Used to disable battery reading for good (PWR/BAT frozen). Now: retry every
            // cycle, and show N/A after a few misses in a row.
            if (++battConsecutiveFailures >= FAILURES_BEFORE_NA && (snapMw != PWR_NA || snapTmp != -1)) {
                snapMw = PWR_NA;
                snapPwrSign = 0;
                snapTmp = -1;
                strPwr = "N/A";
                strTmp = "N/A";
                statsDirty = true;
            }
        }
    }

    // ---------- Current unit ----------
    // CURRENT_NOW is documented in microamps, but some devices (many Samsung, some Huawei)
    // report milliamps. A magnitude >= 20000 can only be uA (20 A is not a phone current).
    // A smaller one is ambiguous, and the old guess ("mA until a >= 20000 reading is seen",
    // kept in a static field per process) read the near-zero net current of bypass / charge
    // limit / FULL as amps: a few mA showed as +20..50 W on the charger, possibly for the whole
    // session. Now the unit is learned per source and stored in prefs (device calibration, like
    // the screen layout), so it is decided once per install:
    //  - any reading >= 20000                      -> uA (certain);
    //  - certain discharge with 50..19999 (raw)    -> mA (in uA that would be < 20 mA while the
    //    screen is on and a game runs, which is impossible), after SIGN_CONFIRM_SAMPLES samples.
    // Until it is learned, an ambiguous reading is interpreted by state:
    //  - external power, status CHARGING           -> mA (real charging is well above 20 mA);
    //  - external power, FULL / NOT_CHARGING / ...  -> uA (bypass, limit: net current ~0);
    //  - on battery                                -> mA (a uA discharge can't be that small).
    private static final String KEY_UNIT_BM = "hud_current_unit_bm";
    private static final String KEY_UNIT_SYSFS = "hud_current_unit_sysfs";
    private static final int UNIT_UNKNOWN = 0;
    private static final int UNIT_MICRO = 1;
    private static final int UNIT_MILLI = 2;
    private static final long UNIT_CERTAIN_UA = 20000L;
    private static final long UNIT_LEARN_MIN_RAW = 50L;
    private String unitLearnKey = null;
    private int unitLearnCount = 0;

    /** Converts a raw CURRENT_NOW magnitude to amps, learning the source's unit on the way. */
    private float currentMagnitudeToAmps(long magnitude, String unitKey, boolean onExternalPower,
                                         boolean confirmedDischarging, boolean statusCharging) {
        int unit = prefs.getInt(unitKey, UNIT_UNKNOWN);

        if (magnitude >= UNIT_CERTAIN_UA) {
            unitLearnCount = 0;
            if (unit != UNIT_MICRO) prefs.edit().putInt(unitKey, UNIT_MICRO).apply();
            return magnitude / 1_000_000f;
        }

        if (unit == UNIT_UNKNOWN && confirmedDischarging && magnitude >= UNIT_LEARN_MIN_RAW) {
            if (unitKey.equals(unitLearnKey)) {
                unitLearnCount++;
            } else {
                unitLearnKey = unitKey;
                unitLearnCount = 1;
            }
            if (unitLearnCount >= SIGN_CONFIRM_SAMPLES) {
                unit = UNIT_MILLI;
                prefs.edit().putInt(unitKey, UNIT_MILLI).apply();
                unitLearnCount = 0;
            }
        } else {
            unitLearnCount = 0; // confirmations must be consecutive
        }

        boolean milli;
        if (unit == UNIT_MICRO) milli = false;
        else if (unit == UNIT_MILLI) milli = true;
        else if (onExternalPower) milli = statusCharging;
        else milli = true;
        return milli ? magnitude / 1000f : magnitude / 1_000_000f;
    }

    // ---------- Current magnitude ----------
    // Consecutive confirming samples (stats thread only) needed to store a learned unit.
    private static final int SIGN_CONFIRM_SAMPLES = 2;
    // Stored "draining" sign of the old direction learning. No longer used; removed on start.
    private static final String LEGACY_KEY_DRAIN_SIGN_BM = "hud_drain_sign_bm";
    private static final String LEGACY_KEY_DRAIN_SIGN_SYSFS = "hud_drain_sign_sysfs";

    /**
     * Magnitude of the battery current in A; NaN if unavailable. The direction is not taken
     * from here (see readBattery). On external power a net current of 0 is a real reading (the
     * charger covers the whole load: bypass charging / charge limit) and returns 0.
     */
    private float getBatteryCurrentAmps(boolean onExternalPower, boolean confirmedDischarging,
                                        boolean statusCharging) {
        long raw = Long.MIN_VALUE;
        String unitKey = KEY_UNIT_BM;
        if (batteryManager != null) {
            raw = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }
        if (raw == Long.MIN_VALUE || (raw == 0L && !onExternalPower)) {
            long sysfs = readFirstSysFsLong(CURRENT_CHANNELS);
            if (sysfs != 0L) {
                raw = sysfs;
                unitKey = KEY_UNIT_SYSFS;
            }
        }
        if (raw == Long.MIN_VALUE) return Float.NaN;
        if (raw == 0L) return onExternalPower ? 0f : Float.NaN;

        return currentMagnitudeToAmps(Math.abs(raw), unitKey, onExternalPower,
                confirmedDischarging, statusCharging);
    }

    private long readFirstSysFsLong(String[] paths) {
        for (String path : paths) {
            long value = readSysFsLong(path);
            if (value != 0L && value != Long.MIN_VALUE) return value;
        }
        return 0L;
    }

    private long readSysFsLong(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? Long.parseLong(line.trim()) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) lastFpsNs = now;
        long dt = now - lastFpsNs;
        if (dt < 350_000_000L) return;

        int frames = frameAccum.getAndSet(0);
        float measuredFps = frames * 1_000_000_000f / dt;
        float genRate = frameGenPresentedRate;
        boolean genFresh = frames > 0 && genRate > 0f
                && now - frameGenRateAtNs < FRAME_GEN_RATE_MAX_AGE_NS;
        snapFps = genFresh ? genRate : measuredFps;
        lastFpsNs = now;

        String value = String.format(Locale.US, "%.0f", snapFps);
        if (!value.equals(strFps)) {
            strFps = value;
            wDynFps = pVal.measureText(strFps);
            if (wDynFps > wValFps) requestRelayout();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) return;

        try {
            int targetAlpha = (int) (180 * hudAlpha);
            if (targetAlpha != lastBgAlpha) {
                pBg.setAlpha(targetAlpha);
                lastBgAlpha = targetAlpha;
            }

            int save = canvas.save();
            canvas.translate(visibleOffset(), 0f);
            if (vertical) drawVertical(canvas);
            else drawHorizontal(canvas);
            canvas.restoreToCount(save);
        } catch (Exception ignored) {}
    }

    private void drawHorizontal(Canvas canvas) {
        float x = PAD;
        float rowH = TS + PAD * 2;
        float baseline = PAD + TS;
        float contentWidth = Math.min(getWidth(), measureHorizontalContent());

        bgRect.set(0, 0, contentWidth, rowH);
        canvas.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        boolean hasGroup = false;

        if ((showMask & SHOW_RENDERER) != 0) {
            canvas.drawText(strRend, x, baseline, pRend);
            x += wDynRend;
            hasGroup = true;
        }

        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText(gpuNameLabel, x, baseline, pGpuName);
            x += wDynGpuName;
            hasGroup = true;
        }

        if ((showMask & SHOW_GPU_USAGE) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("GPU ", x, baseline, pGpuUsage);
            x += wLabelGpu;
            canvas.drawText(strGpu, x, baseline, pVal);
            x += wDynGpu;
            hasGroup = true;
        }

        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("CPU ", x, baseline, pCpu);
            x += wLabelCpu;

            if ((showMask & SHOW_CPU_USAGE) != 0) {
                canvas.drawText(strCpuUsage, x, baseline, pVal);
                x += wDynCpuUsage;
            }

            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) x += wInnerSpace;
                canvas.drawText(strCpuTemp, x, baseline, pVal);
                x += wDynCpuTemp;
            }
            hasGroup = true;
        }

        if ((showMask & SHOW_RAM) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("RAM ", x, baseline, pRam);
            x += wLabelRam;
            canvas.drawText(strRam, x, baseline, pVal);
            x += wDynRam;
            hasGroup = true;
        }

        if ((showMask & SHOW_POWER) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("PWR ", x, baseline, pBat);
            x += wLabelPwr;
            canvas.drawText(strPwr, x, baseline, pVal);
            x += wDynPwr;

            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
                canvas.drawText(" CHG", x, baseline, pChg);
                x += wChg;
            }
            hasGroup = true;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("CHG", x, baseline, pChg);
            x += wChgStandalone;
            hasGroup = true;
        }

        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("BAT ", x, baseline, pTmp);
            x += wLabelTmp;
            canvas.drawText(strTmp, x, baseline, pVal);
            x += wDynTmp;
            hasGroup = true;
        }

        if ((showMask & SHOW_FPS) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("FPS ", x, baseline, pFps);
            x += wLabelFps;
            canvas.drawText(strFps, x, baseline, pVal);
        }
    }

    private void drawVertical(Canvas canvas) {
        float lineH = TS + PAD * 2;
        float rows = countVerticalRows();
        float width = Math.min(getWidth(), measureVerticalContent());
        float height = rows * lineH + PAD;

        bgRect.set(0, 0, width, height);
        canvas.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        float y = PAD;

        if ((showMask & SHOW_RENDERER) != 0) {
            canvas.drawText(strRend, PAD, y + TS, pRend);
            y += lineH;
        }

        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            canvas.drawText(gpuNameLabel, PAD, y + TS, pGpuName);
            y += lineH;
        }

        if ((showMask & SHOW_GPU_USAGE) != 0) {
            canvas.drawText("GPU ", PAD, y + TS, pGpuUsage);
            canvas.drawText(strGpu, PAD + wLabelGpu, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float x = PAD;
            canvas.drawText("CPU ", x, y + TS, pCpu);
            x += wLabelCpu;

            if ((showMask & SHOW_CPU_USAGE) != 0) {
                canvas.drawText(strCpuUsage, x, y + TS, pVal);
                x += wDynCpuUsage;
            }

            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) x += wInnerSpace;
                canvas.drawText(strCpuTemp, x, y + TS, pVal);
            }
            y += lineH;
        }

        if ((showMask & SHOW_RAM) != 0) {
            canvas.drawText("RAM ", PAD, y + TS, pRam);
            canvas.drawText(strRam, PAD + wLabelRam, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & SHOW_POWER) != 0) {
            canvas.drawText("PWR ", PAD, y + TS, pBat);
            float x = PAD + wLabelPwr;
            canvas.drawText(strPwr, x, y + TS, pVal);
            x += wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
                canvas.drawText(" CHG", x, y + TS, pChg);
            }
            y += lineH;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            canvas.drawText("CHG", PAD, y + TS, pChg);
            y += lineH;
        }

        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            canvas.drawText("BAT ", PAD, y + TS, pTmp);
            canvas.drawText(strTmp, PAD + wLabelTmp, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & SHOW_FPS) != 0) {
            canvas.drawText("FPS ", PAD, y + TS, pFps);
            canvas.drawText(strFps, PAD + wLabelFps, y + TS, pVal);
        }
    }

    private float drawSep(Canvas canvas, float x, float baseline) {
        canvas.drawText(" | ", x, baseline, pSep);
        return wSep;
    }

    private float measureHorizontalContent() {
        float width = PAD * 2;
        int groups = 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            width += wDynRend;
            groups++;
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width += wDynGpuName;
            groups++;
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width += wLabelGpu + wDynGpu;
            groups++;
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            width += wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) width += wDynCpuUsage;
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) width += wInnerSpace;
                width += wDynCpuTemp;
            }
            groups++;
        }
        if ((showMask & SHOW_RAM) != 0) {
            width += wLabelRam + wDynRam;
            groups++;
        }
        if ((showMask & SHOW_POWER) != 0) {
            width += wLabelPwr + wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) width += wChg;
            groups++;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            width += wChgStandalone;
            groups++;
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width += wLabelTmp + wDynTmp;
            groups++;
        }
        if ((showMask & SHOW_FPS) != 0) {
            width += wLabelFps + wDynFps;
            groups++;
        }

        if (groups > 1) width += (groups - 1) * wSep;
        return width;
    }

    private float measureHorizontalReserved() {
        float width = PAD * 2;
        int groups = 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            width += wDynRend;
            groups++;
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width += wDynGpuName;
            groups++;
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width += wLabelGpu + Math.max(wVal100pct, wDynGpu);
            groups++;
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            width += wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) {
                width += Math.max(wVal100pct, wDynCpuUsage);
            }
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) width += wInnerSpace;
                width += Math.max(wValCpuTemp, wDynCpuTemp);
            }
            groups++;
        }
        if ((showMask & SHOW_RAM) != 0) {
            width += wLabelRam + Math.max(wVal100pct, wDynRam);
            groups++;
        }
        if ((showMask & SHOW_POWER) != 0) {
            width += wLabelPwr + Math.max(wValWatt, wDynPwr);
            if ((showMask & SHOW_CHARGE_STATE) != 0) width += wChg;
            groups++;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0) {
            width += wChgStandalone;
            groups++;
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width += wLabelTmp + Math.max(wValTemp, wDynTmp);
            groups++;
        }
        if ((showMask & SHOW_FPS) != 0) {
            width += wLabelFps + Math.max(wValFps, wDynFps);
            groups++;
        }

        if (groups > 1) width += (groups - 1) * wSep;
        return width;
    }

    private float measureVerticalContent() {
        float width = PAD * 2;

        if ((showMask & SHOW_RENDERER) != 0) {
            width = Math.max(width, PAD * 2 + wDynRend);
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width = Math.max(width, PAD * 2 + wDynGpuName);
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width = Math.max(width, PAD * 2 + wLabelGpu + wDynGpu);
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float row = wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) row += wDynCpuUsage;
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) row += wInnerSpace;
                row += wDynCpuTemp;
            }
            width = Math.max(width, PAD * 2 + row);
        }
        if ((showMask & SHOW_RAM) != 0) {
            width = Math.max(width, PAD * 2 + wLabelRam + wDynRam);
        }
        if ((showMask & SHOW_POWER) != 0) {
            float row = wLabelPwr + wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) row += wChg;
            width = Math.max(width, PAD * 2 + row);
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            width = Math.max(width, PAD * 2 + wChgStandalone);
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width = Math.max(width, PAD * 2 + wLabelTmp + wDynTmp);
        }
        if ((showMask & SHOW_FPS) != 0) {
            width = Math.max(width, PAD * 2 + wLabelFps + wDynFps);
        }
        return width;
    }

    private float measureVerticalReserved() {
        float width = PAD * 2;

        if ((showMask & SHOW_RENDERER) != 0) {
            width = Math.max(width, PAD * 2 + wDynRend);
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width = Math.max(width, PAD * 2 + wDynGpuName);
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelGpu + Math.max(wVal100pct, wDynGpu));
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float row = wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) {
                row += Math.max(wVal100pct, wDynCpuUsage);
            }
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) row += wInnerSpace;
                row += Math.max(wValCpuTemp, wDynCpuTemp);
            }
            width = Math.max(width, PAD * 2 + row);
        }
        if ((showMask & SHOW_RAM) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelRam + Math.max(wVal100pct, wDynRam));
        }
        if ((showMask & SHOW_POWER) != 0) {
            float row = wLabelPwr + Math.max(wValWatt, wDynPwr);
            if ((showMask & SHOW_CHARGE_STATE) != 0) row += wChg;
            width = Math.max(width, PAD * 2 + row);
        } else if ((showMask & SHOW_CHARGE_STATE) != 0) {
            width = Math.max(width, PAD * 2 + wChgStandalone);
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelTmp + Math.max(wValTemp, wDynTmp));
        }
        if ((showMask & SHOW_FPS) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelFps + Math.max(wValFps, wDynFps));
        }
        return width;
    }

    /** Width of what is actually drawn (unscaled view px). */
    private float visibleWidth() {
        float content = vertical ? measureVerticalContent() : measureHorizontalContent();
        return Math.min(getWidth(), content);
    }

    /** Left edge of the drawn content inside the view (unscaled view px). */
    private float visibleOffset() {
        return alignRight ? Math.max(0f, getWidth() - visibleWidth()) : 0f;
    }

    /**
     * Picks the side the content hugs from where the visible HUD is (right half of the screen =
     * right-aligned). When the side flips, X is compensated so the visible HUD doesn't jump.
     */
    private void updateAlignment(float parentWidth) {
        float s = getScaleX();
        float offset = visibleOffset();
        float centre = getX() + (offset + visibleWidth() * 0.5f) * s;
        boolean right = centre > parentWidth * 0.5f;
        if (right == alignRight) return;
        alignRight = right;
        float shift = (offset - visibleOffset()) * s;
        setX(getX() + shift);
        // Mid-drag the position is recomputed from the drag origin on every move: shift the
        // origin too, or the compensation would be undone on the next event.
        startX += shift;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Right-aligned: keep the right edge where it was when the reserved width changes
        // (orientation toggle, CHG slot, wider values), so the HUD grows / shrinks to the left.
        if (alignRight && oldw > 0 && w != oldw) setX(getX() - (w - oldw) * getScaleX());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float lineH = TS + PAD * 2;
        float width = vertical ? measureVerticalReserved() : measureHorizontalReserved();
        float height = vertical ? (countVerticalRows() * lineH + PAD) : lineH;
        setMeasuredDimension((int) Math.ceil(width), (int) Math.ceil(height));
    }

    private float countVerticalRows() {
        float rows = 0;
        if ((showMask & SHOW_RENDERER) != 0) rows++;
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) rows++;
        if ((showMask & SHOW_GPU_USAGE) != 0) rows++;
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) rows++;
        if ((showMask & SHOW_RAM) != 0) rows++;
        if ((showMask & SHOW_POWER) != 0) rows++;
        else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) rows++;
        if ((showMask & SHOW_BATTERY_TEMP) != 0) rows++;
        if ((showMask & SHOW_FPS) != 0) rows++;
        return Math.max(1, rows);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getPointerCount() > 1) return true;
                // Touches on the empty reserved part of the box go to the game, not the HUD.
                float offset = visibleOffset();
                if (event.getX() < offset || event.getX() > offset + visibleWidth()) return false;
                touchX = event.getRawX();
                touchY = event.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                touchDownMs = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchX;
                float dy = event.getRawY() - touchY;
                if (!dragging && Math.hypot(dx, dy) > DRAG_THRESH) dragging = true;
                if (dragging) {
                    setX(startX + dx);
                    setY(startY + dy);
                    clampToParent(false);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                touchDownMs = 0;
                return true;

            case MotionEvent.ACTION_UP:
                if (event.getPointerCount() > 1) {
                    dragging = false;
                    return true;
                }

                if (dragging) {
                    savePosition();
                } else if (touchDownMs > 0
                        && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    requestRelayout();
                    uiHandler.postDelayed(this::ensureVisible, 250);
                }
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        if (getParent() instanceof View) ((View) getParent()).addOnLayoutChangeListener(parentLayoutListener);
        if (userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(VISIBLE);
            scheduleRedraw();
            // onDetachedFromWindow stopped the stats thread; without this GPU/CPU/RAM/PWR
            // stayed frozen after a re-attach while FPS kept updating.
            startStatsThread();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (getParent() instanceof View) ((View) getParent()).removeOnLayoutChangeListener(parentLayoutListener);
        uiHandler.removeCallbacks(applyStatsRunnable);
        uiHandler.removeCallbacks(redrawRunnable);
        stopStatsThread();
        redrawScheduled = false;
    }

    private void startStatsThread() {
        if (statsThread == null) {
            statsThread = new HandlerThread("WinlatorHUD-stats", Process.THREAD_PRIORITY_BACKGROUND);
            statsThread.start();
            statsHandler = new Handler(statsThread.getLooper());
        }
        statsHandler.removeCallbacks(statsRunnable);
        statsHandler.post(statsRunnable);
    }

    private void stopStatsThread() {
        if (statsHandler != null) statsHandler.removeCallbacks(statsRunnable);
        if (statsThread != null) {
            statsThread.quitSafely();
            statsThread = null;
            statsHandler = null;
        }
    }

    private void ensureVisible() {
        if (userEnabled) {
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    private void savePosition() {
        prefs.edit()
                .putFloat(KEY_X, getX())
                .putFloat(KEY_Y, getY())
                .putBoolean(KEY_ALIGN_RIGHT, alignRight)
                .apply();
    }

    // Keeps the (scaled) HUD fully on screen. Runs on drag, on (re)layout of the HUD or its
    // parent (rotation, resolution change) and after a size change; a saved position that is
    // off screen is pulled back and, when persist is set, re-saved.
    private void clampToParent(boolean persist) {
        if (!(getParent() instanceof View)) return;
        View parent = (View) getParent();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        updateAlignment(pw);
        // Clamp the visible part, not the reserved box: the box may stick out past a screen edge
        // on the side the content doesn't hug.
        float s = getScaleX();
        float offset = visibleOffset() * s;
        float minX = -offset;
        float maxX = Math.max(minX, pw - offset - visibleWidth() * s);
        float maxY = Math.max(0f, ph - getHeight() * getScaleY());
        float x = Math.max(minX, Math.min(maxX, getX()));
        float y = Math.max(0f, Math.min(maxY, getY()));
        if (x != getX() || y != getY()) {
            setX(x);
            setY(y);
            if (persist) savePosition();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) clampToParent(false);
    }

    private void scheduleRedraw() {
        if (!redrawScheduled) {
            redrawScheduled = true;
            uiHandler.postDelayed(redrawRunnable, 400);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && userEnabled) {
            scheduleRedraw();
        } else {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            uiHandler.postDelayed(this::ensureVisible, 150);
            startStatsThread();
        } else if (visibility != VISIBLE) {
            // Game not on screen: stop polling sysfs. Besides wasting battery, the idle GPU
            // while in another app used to trip the (now removed) permanent GPU "failure" latch.
            stopStatsThread();
        }
    }

    private void loadPrefs() {
        int mask;
        if (!prefs.getBoolean(KEY_SHOW_V2, false)) {
            if (prefs.contains(KEY_SHOW)) {
                int legacy = prefs.getInt(KEY_SHOW, LEGACY_SHOW_DEFAULT);
                mask = legacy;
                if ((legacy & SHOW_GPU_USAGE) != 0) mask |= SHOW_GPU_NAME;
                if ((legacy & SHOW_CPU_USAGE) != 0) mask |= SHOW_CPU_TEMP;
                if ((legacy & SHOW_POWER) != 0) {
                    mask |= SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE;
                }
            } else {
                mask = SHOW_DEFAULT;
            }

            prefs.edit()
                    .putInt(KEY_SHOW, mask)
                    .putBoolean(KEY_SHOW_V2, true)
                    .apply();
        } else {
            mask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        }

        showMask = mask;
        hudAlpha = prefs.getInt(KEY_ALPHA, 100) / 100f;
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale);
        setScaleY(scale);
        alignRight = prefs.getBoolean(KEY_ALIGN_RIGHT, false);
        setX(prefs.getFloat(KEY_X, DEFAULT_POS));
        setY(prefs.getFloat(KEY_Y, DEFAULT_POS));
        userEnabled = false;
        setVisibility(GONE);
    }

    public static boolean isOptionEnabled(Context context, int bit) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return (prefs.getInt(KEY_SHOW, SHOW_DEFAULT) & bit) != 0;
    }

    // percent = scale * 50, the inverse of onHudScale()'s "1f + (percent - 50f) / 50f" — so a
    // freshly-opened HUD Size slider reflects the actually-persisted scale (50% = 1.0x) instead
    // of always showing 0 regardless of the saved value.
    public static int getSavedScalePercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        return Math.round(scale * 50f);
    }

    public static int getSavedAlphaPercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_ALPHA, 100);
    }

    public static void setOptionPreference(Context context, int bit, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        mask = enabled ? (mask | bit) : (mask & ~bit);
        prefs.edit().putInt(KEY_SHOW, mask).apply();
    }

    public boolean hasSavedPref() {
        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {
        return prefs.getBoolean(KEY_VIS, false);
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void enableByUser() {
        userEnabled = true;
        rendererActive = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        if (!mesaRendererActive) refreshBackendRenderer(true);
        startStatsThread();
        setVisibility(VISIBLE);
        scheduleRedraw();
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        stopStatsThread();
        if (savePrefs) prefs.edit().putBoolean(KEY_VIS, false).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(GONE);
    }

    public void resetFromContainer() {
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0;
            lastFpsNs = 0;
            mesaRendererActive = false;
            refreshBackendRenderer(true);

            if (userEnabled) {
                setVisibility(VISIBLE);
                scheduleRedraw();
                startStatsThread();
            } else {
                setVisibility(GONE);
                stopStatsThread();
            }
        });
    }

    public void onRendererDetected(String name) {
        rendererActive = true;
        uiHandler.post(() -> {
            if (name != null && !name.trim().isEmpty()) {
                mesaRendererActive = true;
                rendererLabel = name.trim();
                strRend = rendererLabel;
                wDynRend = pRend.measureText(strRend);
                requestRelayout();
            } else {
                mesaRendererActive = false;
                refreshBackendRenderer(true);
            }

            if (userEnabled) {
                startStatsThread();
                setVisibility(VISIBLE);
                scheduleRedraw();
            }
        });
    }

    public void onRendererGone() {
        rendererActive = false;
        uiHandler.post(() -> {
            mesaRendererActive = false;
            refreshBackendRenderer(true);
        });

        uiHandler.postDelayed(() -> {
            if (rendererActive) return;
            frameAccum.set(0);
            snapFps = 0;
            lastFpsNs = 0;
            if (!"0".equals(strFps)) {
                strFps = "0";
                wDynFps = pVal.measureText("0");
            }

            if (userEnabled) {
                invalidate();
                return;
            }

            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            stopStatsThread();
            setVisibility(GONE);
        }, 400);
    }

    public void setRenderer(String name) {
        if (name == null || name.trim().isEmpty()) return;

        rendererActive = true;
        String clean = name.trim();
        uiHandler.post(() -> {
            mesaRendererActive = true;
            rendererLabel = clean;
            strRend = rendererLabel;
            wDynRend = pRend.measureText(strRend);
            requestRelayout();

            if (userEnabled) {
                startStatsThread();
                if (getVisibility() != VISIBLE) {
                    setVisibility(VISIBLE);
                    scheduleRedraw();
                }
            }
        });
    }

    public void setGpuName(String name) {
        String clean = sanitizeGpuName(name);
        if (!isUsefulGpuName(clean) || clean.equals(gpuNameLabel)) return;

        gpuNameLabel = clean;
        uiHandler.post(() -> {
            wDynGpuName = pGpuName.measureText(gpuNameLabel);
            requestRelayout();
        });
    }

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit;
        else showMask &= ~bit;
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        requestRelayout();
    }

    public void setDataSource(Object dataSource) {}

    public void setHudScale(float scale) {
        setHudScale(scale, true);
    }

    // persist = false for live slider drags: only the view changes, SharedPreferences are
    // written once when the slider is released instead of on every drag tick.
    public void setHudScale(float scale, boolean persist) {
        setScaleX(scale);
        setScaleY(scale);
        clampToParent(persist);
        if (persist) prefs.edit().putFloat(KEY_SCALE, scale).apply();
    }

    public void setHudAlpha(float alpha) {
        setHudAlpha(alpha, true);
    }

    public void setHudAlpha(float alpha, boolean persist) {
        hudAlpha = Math.max(0f, Math.min(1f, alpha));
        if (persist) prefs.edit().putInt(KEY_ALPHA, (int) (hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        frameAccum.set(0);
        snapFps = 0;
        lastFpsNs = 0;
        mesaRendererActive = false;
        refreshBackendRenderer(true);
    }

    // Default layout: top-left corner (16, 16), 1.0x size, full opacity, horizontal. Used by
    // "Reset HUD Layout" in the in-game sidebar. Visibility and the chosen metrics stay as they are.
    private static final float DEFAULT_POS = 16f;

    public void resetLayout() {
        // Prefs first and synchronously: the sidebar re-reads the metric checkboxes and the
        // dual-cell toggle from them right after this returns, so a posted reset would race it.
        resetSavedLayout(getContext());
        resetSavedMetrics(getContext());
        uiHandler.post(() -> {
            showMask = SHOW_DEFAULT;
            alignRight = false;
            setX(DEFAULT_POS);
            setY(DEFAULT_POS);
            setScaleX(1f);
            setScaleY(1f);
            hudAlpha = 1f;
            vertical = false;
            requestRelayout();
        });
    }

    // Prefs-only variants for when the HUD view doesn't exist yet; it reads these on creation.
    // Metrics: back to "show everything, no dual-cell correction". The mask is written explicitly (not
    // removed) so the pref listener sees the change. The learned current-unit keys are cleared
    // too (relearned on the next reads), so a reset also recovers from a wrongly learned unit.
    public static void resetSavedMetrics(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SHOW, SHOW_DEFAULT)
                .remove(KEY_DUAL_CELL)
                .remove(KEY_UNIT_BM)
                .remove(KEY_UNIT_SYSFS)
                .remove(LEGACY_KEY_DRAIN_SIGN_BM)
                .remove(LEGACY_KEY_DRAIN_SIGN_SYSFS)
                .apply();
    }
    public static void resetSavedLayout(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_X)
                .remove(KEY_Y)
                .remove(KEY_SCALE)
                .remove(KEY_ALPHA)
                .remove(KEY_VERT)
                .remove(KEY_ALIGN_RIGHT)
                .apply();
    }

    private void requestRelayout() {
        try {
            requestLayout();
            invalidate();
        } catch (Exception ignored) {}
    }

    private int idxToMask(int idx) {
        switch (idx) {
            case 0:
                return SHOW_FPS;
            case 2:
                return SHOW_GPU_USAGE;
            case 3:
                return SHOW_CPU_USAGE;
            case 4:
                return SHOW_POWER;
            case 6:
                return SHOW_RENDERER;
            case 7:
                return SHOW_RAM;
            default:
                return 0;
        }
    }
}

