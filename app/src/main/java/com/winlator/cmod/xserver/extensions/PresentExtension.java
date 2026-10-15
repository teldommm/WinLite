package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentConfigureNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class PresentExtension implements Extension, XResourceManager.OnResourceLifecycleListener, WindowManager.OnWindowModificationListener {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL = 1000000 / 60;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private XServer xServer;
    private final Object limiterLock = new Object();
    private ScheduledThreadPoolExecutor limiterExecutor;

    /*
     * Buffer pacing. A guest (DXVK & co. through the X11 WSI) can only render into a pixmap the
     * server has declared idle (PresentIdleNotify / idle fence), so when a pixmap is handed back
     * IS when the guest's next frame starts - this is the frame limiter.
     *
     * The newest presented pixmap of a window is never handed back: the renderer may still be
     * compositing it (cursor motion re-composites without a new frame). Before, every pixmap
     * was idled the moment it was presented, so a guest could start drawing into the very
     * buffer on screen. Older ones go back according to the renderer's plan
     * (VulkanRendererContext::presentFrame): with no limit at once; with a limit at most `depth`
     * of them at a time, each as late as its frame can still make its display slot, and the
     * one shown until the new frame's slot not before it has left the screen.
     */
    private static final long STALL_GUARD_NS = 200_000_000L;

    private static final class Presented {
        final Window window;
        final Pixmap pixmap;
        final int serial;
        final int idleFence;

        Presented(Window window, Pixmap pixmap, int serial, int idleFence) {
            this.window = window;
            this.pixmap = pixmap;
            this.serial = serial;
            this.idleFence = idleFence;
        }
    }

    private static final class WindowPacing {
        // Presented and not yet handed back, oldest first; the last one is the newest frame.
        final ArrayDeque<Presented> held = new ArrayDeque<>();
        // Handed back (or scheduled to be) and not presented again since: pixmap -> release time.
        final HashMap<Pixmap, Long> outstanding = new HashMap<>();
        ScheduledFuture<?> stallGuard;
    }

    // Guarded by limiterLock.
    private final HashMap<Integer, WindowPacing> pacing = new HashMap<>();

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }
    
    public PresentExtension(XServer xserver) {
        this.xServer = xserver;
        this.xServer.windowManager.addOnResourceLifecycleListener(this);
        this.xServer.pixmapManager.addOnResourceLifecycleListener(this);
        this.xServer.windowManager.addOnWindowModificationListener(this);
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendConfigureNotify(Window window, int pixmapFlags) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentConfigureNotify.getEventMask())) {
                    event.client.sendEvent(new PresentConfigureNotify(event.id, window, pixmapFlags));
                }
            }
        }
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private ScheduledThreadPoolExecutor limiterExecutor() {
        synchronized (limiterLock) {
            if (limiterExecutor == null) {
                ThreadFactory factory = runnable -> {
                    // The idle notify IS the frame release; a late wake-up is a late frame.
                    Thread thread = new Thread(() -> {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                        runnable.run();
                    }, "PresentFpsLimiter");
                    thread.setDaemon(true);
                    return thread;
                };
                limiterExecutor = new ScheduledThreadPoolExecutor(1, factory);
                limiterExecutor.setRemoveOnCancelPolicy(true);
            }
            return limiterExecutor;
        }
    }

    /** Hands a presented pixmap back at {@code atNs}; immediate ones are collected in {@code now}. */
    private void releaseLocked(WindowPacing wp, Presented p, long atNs, long nowNs, ArrayList<Presented> now) {
        long when = Math.max(atNs, nowNs);
        wp.outstanding.put(p.pixmap, when);
        if (when - nowNs <= 200_000L) {
            now.add(p);
        } else {
            limiterExecutor().schedule(() -> sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence),
                    when - nowNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Applies the renderer's plan after a present: plan[0] earliest release, plan[1] the
     * previously newest frame is on screen until then, plan[2] max buffers out (0 = no limit).
     */
    private void planReleasesLocked(WindowPacing wp, long[] plan, long nowNs, ArrayList<Presented> now) {
        final long releaseNs = plan[0];
        final long onScreenNs = plan[1];
        final int depth = (int) plan[2];
        while (wp.held.size() > 1 && (depth <= 0 || wp.outstanding.size() < depth)) {
            Presented p = wp.held.pollFirst();
            boolean shownUntilNewFrame = wp.held.size() == 1;
            long at = shownUntilNewFrame ? Math.max(releaseNs, onScreenNs) : releaseNs;
            releaseLocked(wp, p, at, nowNs, now);
        }
    }

    /**
     * Safety net: a client that needs another buffer while the plan holds them all (one that
     * renders two frames ahead, or simply stopped presenting) gets the oldest held one back
     * after a while instead of stalling for good.
     */
    private void armStallGuardLocked(int windowId, WindowPacing wp) {
        if (wp.stallGuard != null) wp.stallGuard.cancel(false);
        wp.stallGuard = limiterExecutor().schedule(() -> {
            ArrayList<Presented> now = new ArrayList<>(1);
            synchronized (limiterLock) {
                if (pacing.get(windowId) != wp || wp.held.size() <= 1) return;
                releaseLocked(wp, wp.held.pollFirst(), 0, System.nanoTime(), now);
            }
            for (Presented p : now) sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
        }, STALL_GUARD_NS, TimeUnit.NANOSECONDS);
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(2);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        final long nowNs = System.nanoTime();
        final long[] plan = new long[3];
        long guestFrameNs = -1;
        WindowPacing wp;
        ArrayList<Presented> releaseNow = new ArrayList<>(2);
        synchronized (limiterLock) {
            wp = pacing.get(window.id);
            if (wp == null) {
                wp = new WindowPacing();
                pacing.put(window.id, wp);
            }
            // How long the guest took for this frame: from getting the buffer back to now.
            Long releasedAt = wp.outstanding.remove(pixmap);
            if (releasedAt != null && releasedAt <= nowNs) guestFrameNs = nowNs - releasedAt;
            // Presenting a pixmap that is still held (a client that does not wait for idle):
            // the earlier present of it is over.
            for (Iterator<Presented> it = wp.held.iterator(); it.hasNext();) {
                Presented p = it.next();
                if (p.pixmap == pixmap) { it.remove(); releaseNow.add(p); }
            }
        }

        presentToRenderer(client, window, pixmap, guestFrameNs, plan);

        com.winlator.cmod.widget.XServerRendererView view = client.xServer.getXServerView();
        long msc = view != null ? view.getVsyncMsc() : -1;
        long ust;
        if (msc >= 0) {
            // The display's own vsync count and time (Choreographer), not a made-up 60 Hz clock.
            ust = view.getVsyncUstNs() / 1000;
        } else {
            ust = nowNs / 1000;
            msc = ust / FAKE_INTERVAL;
        }
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);

        synchronized (limiterLock) {
            wp.held.addLast(new Presented(window, pixmap, serial, idleFence));
            planReleasesLocked(wp, plan, nowNs, releaseNow);
            armStallGuardLocked(window.id, wp);
        }
        for (Presented p : releaseNow) sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
    }

    // One renderer call per present: shows the pixmap in the window and counts the present (the
    // exact frame clock frame generation paces on) together, waking the render thread once.
    private static void presentToRenderer(XClient client, Window window, Pixmap pixmap,
                                          long guestFrameNs, long[] plan) {
        com.winlator.cmod.widget.XServerRendererView view = client.xServer.getXServerView();
        if (view != null) view.onGuestPresent(window, pixmap.drawable, guestFrameNs, plan);
        else pixmap.drawable.updateDirect();
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }
    
    
    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        sendConfigureNotify(window, 0);
    }

    @Override
    public void onClientDisconnected(XClient client) {
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.valueAt(i).client == client) events.removeAt(i);
            }
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            // A freed pixmap needs no idle notify any more; forget it.
            Pixmap pixmap = (Pixmap) resource;
            synchronized (limiterLock) {
                for (WindowPacing wp : pacing.values()) {
                    wp.outstanding.remove(pixmap);
                    wp.held.removeIf(p -> p.pixmap == pixmap);
                }
            }
            return;
        }
        if (resource instanceof Window) {
            Window window = (Window) resource;
            synchronized (limiterLock) {
                WindowPacing wp = pacing.remove(window.id);
                if (wp != null && wp.stallGuard != null) wp.stallGuard.cancel(false);
            }
            synchronized (events) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    Event event = events.valueAt(i);
                    if (event.window == window) {
                        if (event.mask.isSet(PresentConfigureNotify.getEventMask())) {
                            event.client.sendEvent(new PresentConfigureNotify(
                                    event.id,
                                    window,
                                    PresentConfigureNotify.PIXMAP_FLAG_WINDOW_DESTROYED));
                        }
                        events.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
