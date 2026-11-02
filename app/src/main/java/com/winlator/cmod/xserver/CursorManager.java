package com.winlator.cmod.xserver;

import android.util.SparseArray;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;
    private ArrayList<OnCursorModificationListener> listeners = new ArrayList<>();
    
    public interface OnCursorModificationListener {
        default void onCreateCursor(Cursor cursor) {}
        default void onFreeCursor(Cursor cursor) {}
    };
    
    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        // RecolorCursor reads the source and mask again, possibly after the client freed those
        // pixmaps: the cursor keeps their buffers alive until it is freed itself.
        sourcePixmap.drawable.retain();
        if (maskPixmap != null) maskPixmap.drawable.retain();
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        triggerOnCreateCursor(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        final Cursor cursor = cursors.get(id);
        triggerOnFreeResourceListener(cursor);
        triggerOnFreeCursor(cursor);
        cursors.remove(id);
        if (cursor != null) {
            // cursorImage is left alone: a window may keep showing this cursor after FreeCursor.
            drawableManager.dropReference(cursor.sourceImage);
            drawableManager.dropReference(cursor.maskImage);
        }
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        ByteBuffer maskBuffer = maskImage.lockBuffer(maskImage.backingAHB);
        if (maskBuffer == null) return true;
        IntBuffer maskData = maskBuffer.asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        maskImage.unlockBuffer(maskImage.backingAHB);
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }

    public void addOnCursorModificationListener(OnCursorModificationListener listener) {
        listeners.add(listener);
    }

    private void triggerOnCreateCursor(Cursor cursor) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onCreateCursor(cursor);
        }
    }

    private void triggerOnFreeCursor(Cursor cursor) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onFreeCursor(cursor);
        }
    }
}