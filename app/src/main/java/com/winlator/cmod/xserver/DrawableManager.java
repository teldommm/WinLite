package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.widget.XServerRendererView;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable != null && drawable.backingAHB == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data when fetched.");
        }
        return drawable;
    }


    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) {
            Drawable drawable = new Drawable(id, width, height, visual, xServer.getSurfaceFormat());
            if (drawable.backingAHB == 0) {
                throw new IllegalStateException("Drawable with id 0 has null data at creation.");
            }
            return drawable;
        }
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual, xServer.getSurfaceFormat());
        if (drawable.backingAHB == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data at creation.");
        }
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) {
            throw new IllegalStateException("Attempting to remove non-existent Drawable with id " + id);
        }
        if (drawable.backingAHB == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data during removal.");
        }

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
        dropReference(drawable);
    }

    /**
     * Gives up the X resource's (or a cursor's) reference to a drawable's buffer. The renderer
     * forgets its import first - it keeps its own reference until the GPU is done with the
     * frames that sampled it - then the last reference frees the buffer. With a renderer that
     * does not take part in this, the buffer is left alone, as it always was.
     */
    public void dropReference(Drawable drawable) {
        if (drawable == null) return;
        XServerRendererView view = xServer.getXServerView();
        if (view == null || !view.ownsDrawableBuffers()) return;
        final long ahb = drawable.backingAHB;
        final long toFree = drawable.releaseRef();
        if (ahb != 0) view.onBufferReleased(ahb);
        Drawable.dropBacking(toFree);
    }


    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            Drawable drawable = pixmap.drawable;
            if (drawable.backingAHB == 0) {
                throw new IllegalStateException("Drawable for Pixmap with id " + pixmap.drawable.id + " has null data during free.");
            }
            removeDrawable(drawable.id);
        }
    }


    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
