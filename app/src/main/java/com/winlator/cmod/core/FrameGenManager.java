package com.winlator.cmod.core;

/**
 * Helpers around native LSFG frame generation launch/env handling.
 */
public final class FrameGenManager {
    private FrameGenManager() {}

    /**
     * Strip any leftover frame-generation environment variables and Vulkan
     * layers before launching the guest process. A container that previously
     * ran with an external LSFG-VK layer installed (or any stray env from a
     * prior session) must not have it silently re-activate underneath the
     * native compositor path.
     */
    public static void applyLaunchEnv(EnvVars envVars) {
        for (String name : envVars.toStringArray()) {
            String key = name.substring(0, name.indexOf('='));
            if (key.startsWith("LSFG_")) envVars.remove(key);
        }
        String layers = envVars.get("VK_INSTANCE_LAYERS");
        java.util.ArrayList<String> retained = new java.util.ArrayList<>();
        for (String layer : layers.split(":")) {
            if (!layer.isEmpty() && !layer.equals("VK_LAYER_LS_frame_generation")) retained.add(layer);
        }
        if (retained.isEmpty()) envVars.remove("VK_INSTANCE_LAYERS");
        else envVars.put("VK_INSTANCE_LAYERS", String.join(":", retained));
        envVars.put("DISABLE_LSFG", "1");
    }
}
