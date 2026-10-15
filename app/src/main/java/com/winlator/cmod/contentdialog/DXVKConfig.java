package com.winlator.cmod.contentdialog;

import android.content.Context;

import java.util.Locale;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.xenvironment.ImageFs;

public class DXVKConfig {
    // Was DXVKConfigDialog. The interactive dialog UI here was dead code — its
    // only caller was the legacy ShortcutSettingsDialog, which is itself never
    // shown (the live per-shortcut editor is ui/shortcut/ShortcutEditorV2.kt).
    // Trimmed to the static config codec used live by XServerDisplayActivity,
    // and renamed since it's no longer a dialog.
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    // ---- Texture filtering (dxwrapperConfig keys, so a shortcut overrides the container) ----
    // anisotropy: "0"/absent = leave the game's own filtering; 2/4/8/16 = force that anisotropy
    //   (d3d9/d3d11.samplerAnisotropy, range 0..16 in DXVK >= 2.x dxvk.conf).
    // lodBias: "0"/absent = off; "auto" = derived at launch from the upscale ratio (see autoLodBias);
    //   otherwise a negative number added to the game's own mip LOD bias
    //   (d3d9/d3d11.samplerLodBias, range -2.0..1.0) - sharper textures at the cost of some shimmer.
    // Read by DXVK (D3D9/10/11) only; VKD3D-Proton and WineD3D ignore them.
    public static final String[] ANISOTROPY_VALUES = {"0", "2", "4", "8", "16"};
    public static final String[] ANISOTROPY_LABELS = {"Game default", "2x", "4x", "8x", "16x"};
    public static final String LOD_BIAS_AUTO = "auto";
    public static final String[] LOD_BIAS_VALUES = {"0", LOD_BIAS_AUTO, "-0.25", "-0.5", "-0.75", "-1.0"};
    public static final String[] LOD_BIAS_LABELS = {"Off", "Auto", "-0.25", "-0.5", "-0.75", "-1.0"};
    private static final float LOD_BIAS_MIN = -2.0f;

    /**
     * Mip LOD bias for a game rendered at gameW x gameH and aspect-fitted onto a panelW x panelH
     * screen by a spatial upscaler: log2(render / output) (AMD FSR 1 guidance; 720p on a 1080p
     * screen gives -0.58). 0 when the game is not upscaled.
     */
    public static float autoLodBias(int gameW, int gameH, int panelW, int panelH) {
        if (gameW <= 0 || gameH <= 0 || panelW <= 0 || panelH <= 0) return 0f;
        int gameLong = Math.max(gameW, gameH), gameShort = Math.min(gameW, gameH);
        int panelLong = Math.max(panelW, panelH), panelShort = Math.min(panelW, panelH);
        float scale = Math.min((float) panelLong / gameLong, (float) panelShort / gameShort);
        if (scale <= 1.0f) return 0f;
        return Math.max((float) (-Math.log(scale) / Math.log(2.0)), LOD_BIAS_MIN);
    }

    static float resolveLodBias(String value, float autoLodBias) {
        if (value == null || value.isEmpty() || value.equals("0")) return 0f;
        if (LOD_BIAS_AUTO.equals(value)) return autoLodBias;
        try {
            return Math.max(Math.min(Float.parseFloat(value), 0f), LOD_BIAS_MIN);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** DXVK_CONFIG options for the texture-filtering keys, or "" when both are left to the game. */
    public static String textureFilteringOptions(KeyValueSet config, float autoLodBias) {
        StringBuilder sb = new StringBuilder();
        int aniso;
        try {
            aniso = Integer.parseInt(config.get("anisotropy"));
        } catch (NumberFormatException e) {
            aniso = 0;
        }
        if (aniso > 0) {
            aniso = Math.min(aniso, 16);
            sb.append("d3d9.samplerAnisotropy = ").append(aniso).append("; ");
            sb.append("d3d11.samplerAnisotropy = ").append(aniso);
        }
        float bias = resolveLodBias(config.get("lodBias"), autoLodBias);
        if (bias < 0f) {
            // Locale.US: DXVK parses "-0.58", never "-0,58".
            String b = String.format(Locale.US, "%.2f", bias);
            if (sb.length() > 0) sb.append("; ");
            sb.append("d3d9.samplerLodBias = ").append(b).append("; ");
            sb.append("d3d11.samplerLodBias = ").append(b);
        }
        return sb.toString();
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        setEnvVars(context, config, envVars, 0f, false);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, float autoLodBias) {
        setEnvVars(context, config, envVars, autoLodBias, false);
    }

    /**
     * @param autoLodBias what lodBias=auto resolves to for this launch (see autoLodBias); 0 = none.
     * @param frameLimiterActive the X server's FPS limiter is on for this launch. It throttles the
     *        game by holding its swapchain buffers; with DXVK's default frame latency (3) the game
     *        thread then keeps running up to three frames ahead of what is shown, and every one of
     *        those frames is input lag. One frame of latency keeps the limiter's wait in front of
     *        the game's input sampling instead.
     */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, float autoLodBias,
                                  boolean frameLimiterActive) {
        String content = "";

        String framerate = config.get("framerate");

        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = " + framerate + "; ";
            content += "d3d9.maxFrameRate = " + framerate;
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String maxFrameLatency = config.get("maxFrameLatency");
        if (frameLimiterActive || (!maxFrameLatency.isEmpty() && !maxFrameLatency.equals("0"))) {
            if (!content.isEmpty()) content += "; ";
            content += "dxgi.maxFrameLatency = 1; d3d9.maxFrameLatency = 1";
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        String textureOptions = textureFilteringOptions(config, autoLodBias);
        if (!textureOptions.isEmpty()) {
            if (!content.isEmpty()) content += "; ";
            content += textureOptions;
        }

        if (!content.isEmpty())
            envVars.put("DXVK_CONFIG", content);

        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
    }
}
