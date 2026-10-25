package com.winlator.cmod.widget;

import android.content.Context;
import android.view.SurfaceView;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

public abstract class XServerRendererView extends SurfaceView {

    public final ViewTransformation viewTransformation = new ViewTransformation();
    protected final XServer xServer;

    protected XServerRendererView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
    }

    public abstract void onPause();
    public abstract void onResume();
    public abstract void onDestroy();

    public abstract void toggleFullscreen();
    public abstract boolean isFullscreen();

    public abstract void setCursorVisible(boolean visible);
    public abstract void setScreenOffsetYRelativeToCursor(boolean relative);
    public abstract void setMagnifierZoom(float zoom);
    public abstract float getMagnifierZoom();
    public abstract void setUnviewableWMClasses(String... classes);

    public abstract void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage);
    // A drawable's backing buffer is about to be released (pixmap freed, window destroyed,
    // window content replaced on resize). Called under the DRAWABLE_MANAGER lock. Renderers drop
    // whatever they hold for it; the default holds nothing.
    public void onDrawableReleased(Drawable drawable) {}
    public abstract void nativeSetCompositeRedirected(int windowId, boolean redirected);
    // Returns true if the copy was handled by moving windows; false means the caller must do a real pixel copy.
    public abstract boolean nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY);

    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable);
    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable, short xOff, short yOff);
    public abstract void onPointerMove(short x, short y);
    // A guest frame was presented through the X Present extension (PresentPixmap). Renderers
    // show the pixmap in the window and count the present in one step, and fill in when the X
    // server may hand buffers back and complete the present (plan: release time, on-screen-until
    // time, max buffers out, completion time; all 0 = at once). guestFrameNs: how long the guest took for this frame, -1 if unknown.
    // The default only runs the pixmap's ordinary content-update path.
    public void onGuestPresent(Window window, Drawable pixmap, long guestFrameNs, long[] plan) {
        pixmap.updateDirect();
    }
    // The display's vsync count and time (CLOCK_MONOTONIC ns) at nowNs for Present's MSC/UST:
    // out[0] = msc, out[1] = ust ns, out[2] = period ns if the array is long enough.
    // false = no display clock (the caller falls back to 60 Hz).
    public boolean getVsync(long nowNs, long[] out) { return false; }
    // Whether the renderer's GPU work may still be reading this guest buffer (a composite that
    // sampled it has not finished). The X server holds it back from the guest until then.
    public boolean isGuestBufferBusy(Drawable buffer) { return false; }
    // Blocks until the renderer's GPU work is done with this guest buffer, or timeoutNs passes;
    // returns at once when nothing is pending. Never called with any X server lock held.
    public void waitGuestBufferIdle(Drawable buffer, long timeoutNs) {}
    // Period of the 60 Hz time base the fallback MSC (and the real one's anchor) uses.
    public static final long FALLBACK_MSC_PERIOD_NS = 16_666_667L;
    // Windows currently redirected by the Composite extension, so a renderer that replaces this
    // one mid-game (hot renderer switch) can be told about them again.
    public int[] getCompositeRedirectedWindowIds() { return new int[0]; }
    public abstract void requestRender();
    public abstract void queueEvent(Runnable action);
    public abstract void onSurfaceChanged(int width, int height);
    public abstract void forceCleanup();
    public abstract void setFpsWindowId(int windowId);
    public abstract void setPipMode(boolean pipMode);
    public abstract void setFrameRating(Object frameRating);

    public abstract void setFpsLimit(int fps);
    public abstract int getFpsLimit();
}
