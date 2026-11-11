package com.winlator.cmod.core;

import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.xserver.XServer;

import java.util.Timer;
import java.util.TimerTask;

/** Pulls the pointer back from beyond the screen edge, a little each tick, while the mouse is not captured. */
public class CursorLocker extends TimerTask {
    private final XServer xServer;
    private float damping = 0.25f;
    private short maxDistance;
    private boolean enabled = true;
    private final Object pauseLock = new Object();

    public CursorLocker(XServer xServer) {
        this.xServer = xServer;
        maxDistance = (short)(xServer.screenInfo.width * 0.05f);
        Timer timer = new Timer("CursorLocker", true);
        timer.scheduleAtFixedRate(this, 0, 1000 / 60);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            synchronized (pauseLock) {
                this.enabled = true;
                pauseLock.notifyAll();
            }
        }
        else this.enabled = enabled;
    }

    @Override
    public void run() {
        synchronized (pauseLock) {
            if (!enabled) {
                try {
                    pauseLock.wait();
                }
                catch (InterruptedException e) {}
            }
        }

        if (!xServer.isRunning()) return;
        final short width = xServer.screenInfo.width, height = xServer.screenInfo.height;
        final short px = xServer.pointer.getX(), py = xServer.pointer.getY();
        short x = (short)Mathf.clamp(px, -maxDistance, width + maxDistance);
        short y = (short)Mathf.clamp(py, -maxDistance, height + maxDistance);
        short nx = px, ny = py;

        if (x < 0) {
            nx = (short)Math.ceil(x * damping);
        }
        else if (x >= width) {
            nx = (short)Math.floor(width + (x - width) * damping);
        }
        if (y < 0) {
            ny = (short)Math.ceil(y * damping);
        }
        else if (y >= height) {
            ny = (short)Math.floor(height + (y - height) * damping);
        }
        if (nx != px || ny != py) xServer.pointer.setPositionSilently(nx, ny);
    }
}
