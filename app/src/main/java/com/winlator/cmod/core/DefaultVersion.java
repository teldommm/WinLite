package com.winlator.cmod.core;

public abstract class DefaultVersion {
    public static final String BOX64 = "0.4.4";
    public static final String WOWBOX64 = "0.4.4";
    public static final String FEXCORE = "2610";
    // Every FEXCore build shipped in assets/fexcore (fexcore-<version>.tzst), default first.
    public static final String[] FEXCORE_BUNDLED = { FEXCORE, "2610-unix" };
    public static final String WRAPPER = "System";
    public static final String WRAPPER_ADRENO = "turnip26.2-v2";
    public static final String DXVK = GPUInformation.getRenderer(null, null).contains("Mali") ? "1.10.3" : "2.3.1";
    public static final String D8VK = "1.0";
    public static final String VKD3D = "None";
}
