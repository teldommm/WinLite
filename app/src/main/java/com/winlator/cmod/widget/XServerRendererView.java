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

    /**
     * The display clock, for the FPS limiters: {refresh period ns, how far the latest paced game
     * frame arrived from the phase that lands it on a refresh with the least latency (ns, > 0 =
     * late), that measurement's sequence number}. False when the renderer has no fresh refresh
     * grid; a renderer without one keeps the default.
     */
    public boolean getDisplayClock(long[] out) { return false; }

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
    public abstract void nativeRemoveDirectContent(int windowId, int pixmapId);
    public abstract void nativeSetCompositeRedirected(int windowId, boolean redirected);
    // Returns true if the copy was handled by moving windows; false means the caller must do a real pixel copy.
    public abstract boolean nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY);

    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable);
    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable, short xOff, short yOff);
    public abstract void onPointerMove(short x, short y);
    public abstract void requestRender();
    public abstract void queueEvent(Runnable action);
    public abstract void onSurfaceChanged(int width, int height);
    public abstract void forceCleanup();
    public abstract void setFpsWindowId(int windowId);
    public abstract void setPipMode(boolean pipMode);
    public abstract void setFrameRating(Object frameRating);

    /** True if this renderer cooperates in freeing drawable buffers (see onBufferReleased). */
    public boolean ownsDrawableBuffers() { return false; }
    /**
     * A drawable's buffer is about to be given back by the X server: forget any import of it.
     * Only called when {@link #ownsDrawableBuffers()}; the renderer keeps what it needs alive itself.
     */
    public void onBufferReleased(long ahbPtr) {}

    public abstract void setFpsLimit(int fps);
    public abstract int getFpsLimit();
}
