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
    // A guest frame was presented through the X Present extension. Renderers that
    // pace frame generation on guest frames override this; the default ignores it.
    public void onGuestFramePresented() {}
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
