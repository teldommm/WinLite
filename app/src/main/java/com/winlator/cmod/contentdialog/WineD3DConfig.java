package com.winlator.cmod.contentdialog;

import android.content.Context;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GpuSpoof;
import com.winlator.cmod.core.KeyValueSet;

import java.util.Map;

public class WineD3DConfig {
    // Was WineD3DConfigDialog; only the env-var helper used live by XServerDisplayActivity is
    // left.
    //
    // VideoPciVendorID / VideoPciDeviceID come from the GPU Spoof window (Vendor ID / Device ID
    // of the graphics driver config, see core/GpuSpoof) and are only passed when set. Otherwise
    // WineD3D detects the GPU itself: with the Vulkan renderer it reads the wrapper's
    // VkPhysicalDeviceProperties, which already carry the spoofed IDs and name.
    //
    // wined3d (wined3d_main.c / directx.c): an ID above 0xffff is rejected, and the override is
    // only used when the vendor:device pair is in its gpu_description_table, otherwise it is
    // ignored ("Invalid GPU override"). Values are parsed with strtoul(.., 0), so they are passed
    // in decimal (a leading 0 would be octal).
    private static final long MAX_PCI_ID = 0xffff;

    public static void setEnvVars(Context context, KeyValueSet config, Map<String, String> graphicsDriverConfig, EnvVars vars) {
        GpuSpoof spoof = GpuSpoof.fromMap(graphicsDriverConfig);
        long vendorId = GpuSpoof.parseId(spoof.getVendorId());
        long deviceId = GpuSpoof.parseId(spoof.getDeviceId());

        StringBuilder wined3dConfig = new StringBuilder();
        wined3dConfig.append("csmt=0x").append(config.get("csmt"))
                .append(",strict_shader_math=0x").append(config.get("strict_shader_math"))
                .append(",OffscreenRenderingMode=").append(config.get("OffscreenRenderingMode"))
                .append(",VideoMemorySize=").append(config.get("videoMemorySize"));
        if (deviceId > 0 && deviceId <= MAX_PCI_ID)
            wined3dConfig.append(",VideoPciDeviceID=").append(deviceId);
        if (vendorId > 0 && vendorId <= MAX_PCI_ID)
            wined3dConfig.append(",VideoPciVendorID=").append(vendorId);
        wined3dConfig.append(",renderer=").append(config.get("renderer"));
        vars.put("WINE_D3D_CONFIG", wined3dConfig.toString());
    }
}
