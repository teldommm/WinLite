package com.winlator.cmod.xserver;

/**
 * Pixel order of the X server's window buffers (AHardwareBuffers) and of the guest's swapchain
 * images. The single source of truth: the container/shortcut setting, the buffer allocation, the
 * cursor texture and the wrapper's WRAPPER_SURFACE_FORMAT all come from here.
 *
 * RGBA is the default: AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM is a public format every HAL and
 * compositor accepts. BGRA is HAL_PIXEL_FORMAT_BGRA_8888 (5), which is not part of the public NDK
 * API; it stays available for guests that need that order. UBWC compression is a separate option
 * (it only works together with RGBA).
 */
public enum SurfaceFormat {
    RGBA("rgba8", "RGBA", 1),
    BGRA("bgra8", "BGRA", 5);

    public static final SurfaceFormat DEFAULT = RGBA;

    /** Value of WRAPPER_SURFACE_FORMAT and of the "surfaceFormat" container/shortcut extra. */
    public final String id;
    /** Name shown in the settings. */
    public final String label;
    /** HAL pixel format used to allocate the buffers. */
    public final int halFormat;

    SurfaceFormat(String id, String label, int halFormat) {
        this.id = id;
        this.label = label;
        this.halFormat = halFormat;
    }

    public static SurfaceFormat fromId(String id) {
        return BGRA.id.equalsIgnoreCase(id) ? BGRA : RGBA.id.equalsIgnoreCase(id) ? RGBA : DEFAULT;
    }

    public static SurfaceFormat fromLabel(String label) {
        return BGRA.label.equalsIgnoreCase(label) ? BGRA : RGBA.label.equalsIgnoreCase(label) ? RGBA : DEFAULT;
    }

    /** The format of an existing buffer, from its HAL format (anything but BGRA is RGBA order). */
    public static SurfaceFormat fromHalFormat(int halFormat) {
        return halFormat == BGRA.halFormat ? BGRA : RGBA;
    }
}
