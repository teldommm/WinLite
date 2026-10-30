package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private float outputFPS = 0;
    private volatile OutputFrameSource outputSource;
    private long lastPresented = -1, lastGenerated = -1;

    public void setOutputFrameSource(OutputFrameSource source) {
        outputSource = source;
        lastPresented = -1;
        lastGenerated = -1;
    }
    private String totalRAM = null;
    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;
    private HashMap graphicsDriverConfig;
    private static final String PREFS = "winlator_hud";
    private static final String KEY_VIS = "hud_vis";
    // Classic's own size/opacity. Distinct from the Modern HUD's hud_scale / hud_alpha_int, which
    // live in the same prefs file, so the two styles keep separate settings.
    private static final String KEY_SCALE = "classic_hud_scale";
    private static final String KEY_ALPHA = "classic_hud_alpha_int";
    private final SharedPreferences prefs;
    private boolean userEnabled = false;
    // Row backgrounds (#33000000 in frame_rating.xml). Opacity is applied only to these so the
    // text stays fully opaque, matching the Modern HUD where hud_alpha only affects the bg paint.
    private final java.util.ArrayList<Drawable> rowBackgrounds = new java.util.ArrayList<>();

    private String lastKnownRenderer = null;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig ,null);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        tvFPS = view.findViewById(R.id.TVFPS);
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("Vulkan");
        tvGPU = view.findViewById(R.id.TVGPU);
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
        tvRAM = view.findViewById(R.id.TVRAM);
        totalRAM = getTotalRAM();
        this.graphicsDriverConfig = graphicsDriverConfig;
        addView(view);
        if (view instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) view;
            for (int i = 0; i < root.getChildCount(); i++) {
                Drawable bg = root.getChildAt(i).getBackground();
                if (bg != null) {
                    bg = bg.mutate();
                    root.getChildAt(i).setBackground(bg);
                    rowBackgrounds.add(bg);
                }
            }
        }

        // Scale from the top-left corner, where the HUD sits, so it grows/shrinks in place instead
        // of drifting off the screen edge (same as the Modern HUD).
        setPivotX(0f);
        setPivotY(0f);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale);
        setScaleY(scale);
        applyBackgroundAlpha(prefs.getInt(KEY_ALPHA, 100) / 100f);
    }

    private String getTotalRAM() {
        long[] mem = readMeminfo();
        return mem[0] > 0 ? StringUtils.formatBytes(mem[0] * 1024L) : "N/A";
    }

    private long[] readMeminfo() {
        long total = -1, avail = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:"))          total = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) { avail = parseMeminfoKb(line); break; }
            }
        } catch (Exception ignored) {}
        return new long[]{total, avail};
    }

    private long parseMeminfoKb(String line) {
        try { return Long.parseLong(line.trim().split("\\s+")[1]); }
        catch (Exception e) { return -1; }
    }

    private String getAvailableRAM() {
        long[] mem = readMeminfo();
        if (mem[0] <= 0 || mem[1] < 0) return "N/A";
        long usedKb = mem[0] - mem[1];
        return StringUtils.formatBytes(usedKb * 1024L, false);
    }

    private void refreshRendererText() {
        tvRenderer.setText(lastKnownRenderer != null ? lastKnownRenderer : "Vulkan");
    }

    public void setRenderer(String renderer) {
        final String clean = renderer != null ? renderer.trim() : "";
        lastKnownRenderer = clean.isEmpty() ? null : clean;
        refreshRendererText();
    }

    public void setGpuName (String gpuName) {
        if (gpuName == null || gpuName.trim().isEmpty()) return;
        tvGPU.setText(gpuName.trim());
    }

    // The renderer window is gone: back to "Vulkan" and the configured driver's GPU name, like the
    // Modern HUD (the next window reports its own engine name).
    public void reset() {
        lastKnownRenderer = null;
        refreshRendererText();
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
    }

    // persist = false for live slider drags; written once when the slider is released.
    public void setHudScale(float scale, boolean persist) {
        setScaleX(scale);
        setScaleY(scale);
        if (persist) prefs.edit().putFloat(KEY_SCALE, scale).apply();
    }

    public void setHudAlpha(float alpha, boolean persist) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        applyBackgroundAlpha(clamped);
        if (persist) prefs.edit().putInt(KEY_ALPHA, Math.round(clamped * 100f)).apply();
    }

    /** "Reset HUD Layout": natural size, fully opaque. Visibility and the shown rows are untouched. */
    public void resetLayout() {
        resetSavedLayout(context);
        setScaleX(1f);
        setScaleY(1f);
        applyBackgroundAlpha(1f);
    }

    // ColorDrawable.setAlpha modulates the XML color's own alpha, so 1.0 keeps the original #33.
    private void applyBackgroundAlpha(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        for (Drawable bg : rowBackgrounds) bg.setAlpha(a);
    }

    public static void resetSavedLayout(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_SCALE)
                .remove(KEY_ALPHA)
                .apply();
    }

    // Slider positions: size 50% = 1.0x (same mapping as the Modern HUD), opacity in percent.
    public static int getSavedScalePercent(Context context) {
        return Math.round(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_SCALE, 1f) * 50f);
    }

    public static int getSavedAlphaPercent(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ALPHA, 100);
    }

    public boolean hasSavedPref() {
        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {
        return prefs.getBoolean(KEY_VIS, false);
    }

    public void enableByUser() {
        userEnabled = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();

        post(() -> setVisibility(View.VISIBLE));
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        if (savePrefs) prefs.edit().putBoolean(KEY_VIS, false).apply();
        setVisibility(View.GONE);
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void update() {
        if (!userEnabled) return;
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS = ((float)(frameCount * 1000) / (time - lastTime));
            outputFPS = 0;
            OutputFrameSource source = outputSource;
            if (source != null) {
                long presented = source.getPresentedFrameCount();
                long generated = source.getGeneratedFrameCount();
                if (lastPresented >= 0 && generated > lastGenerated && presented >= lastPresented)
                    outputFPS = (float)((presented - lastPresented) * 1000) / (time - lastTime);
                lastPresented = presented;
                lastGenerated = generated;
            }
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (!userEnabled) return;
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        tvFPS.setText(outputFPS > 0
                ? String.format(Locale.ENGLISH, "%.1f → %.0f", lastFPS, outputFPS)
                : String.format(Locale.ENGLISH, "%.1f", lastFPS));
        tvRAM.setText(getAvailableRAM() + " GB Used / " + totalRAM + " Total");
    }
}

