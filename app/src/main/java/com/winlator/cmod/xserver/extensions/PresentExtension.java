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
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class PresentExtension implements Extension, XResourceManager.OnResourceLifecycleListener, WindowManager.OnWindowModificationListener {
    public static final byte MAJOR_OPCODE = -103;
    // Fallback MSC clock (no display vsync): the same 60 Hz time base VsyncClock anchors to.
    private static final long FAKE_INTERVAL_NS = com.winlator.cmod.widget.XServerRendererView.FALLBACK_MSC_PERIOD_NS;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private XServer xServer;
    private final Object limiterLock = new Object();
    private ScheduledThreadPoolExecutor limiterExecutor;
    private ExecutorService gpuIdleExecutor;

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
    private static final long NO_TOKEN = Long.MIN_VALUE;
    // A buffer the renderer's GPU work may still be reading waits on its own thread until the
    // GPU is done with it (the renderer's timeline semaphore), for at most this in all: a stalled
    // renderer must not stall the guest for good.
    private static final long GPU_BUSY_MAX_NS = 50_000_000L;
    private static final long NO_DEADLINE = Long.MIN_VALUE;

    private static final class Presented {
        final Window window;
        final Pixmap pixmap;
        final int serial;
        final int idleFence;
        // The hand-back this present was planned for (its entry in WindowPacing.outstanding);
        // NO_TOKEN = none, deliver unconditionally. Guarded by limiterLock.
        long token = NO_TOKEN;

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
        // Live pixmaps this window has been presented with. A client with a single buffer
        // (a swapchain of one image) must get it back after every present: holding the newest
        // one would leave it nothing to draw into.
        final HashSet<Pixmap> seen = new HashSet<>();
        ScheduledFuture<?> stallGuard;
        long lastMsc = Long.MIN_VALUE;
        long lastCompleteAtNs = 0;   // when the latest CompleteNotify is (or was) due

        /** How many of the newest presented pixmaps stay held. */
        int keep() { return seen.size() >= 2 ? 1 : 0; }
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

    private ExecutorService gpuIdleExecutor() {
        synchronized (limiterLock) {
            if (gpuIdleExecutor == null) {
                gpuIdleExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    // Its wake-up, like the limiter's, is a frame release.
                    Thread thread = new Thread(() -> {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                        runnable.run();
                    }, "PresentGpuIdle");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return gpuIdleExecutor;
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
                        // Part of the frame's path, so part of the renderer's ADPF session (when
                        // on). Not the GPU-idle thread: it only blocks on a fence.
                        com.winlator.cmod.core.PerfHintThreads.runRegistered(runnable);
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
        p.token = when;
        if (when - nowNs <= 200_000L) {
            now.add(p);
        } else {
            limiterExecutor().schedule(() -> deliverIdle(p, NO_DEADLINE), when - nowNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Sends a planned hand-back, unless it no longer applies, and not while the renderer's GPU
     * work may still be reading the buffer: the guest would draw into it under a composite
     * (with no limit the previous buffer goes back the moment the next one is presented, while
     * the composite that showed it can still be running).
     */
    private void deliverIdle(Presented p, long gpuDeadlineNs) {
        if (p.token != NO_TOKEN) {
            synchronized (limiterLock) {
                WindowPacing wp = pacing.get(p.window.id);
                Long planned = wp != null ? wp.outstanding.get(p.pixmap) : null;
                // Freed (pixmap or window) or presented again since: not ours to idle any more.
                if (planned == null || planned != p.token) return;
            }
        }
        final com.winlator.cmod.widget.XServerRendererView view = xServer.getXServerView();
        if (view != null && view.isGuestBufferBusy(p.pixmap.drawable)) {
            final long nowNs = System.nanoTime();
            final long deadlineNs = gpuDeadlineNs != NO_DEADLINE ? gpuDeadlineNs : nowNs + GPU_BUSY_MAX_NS;
            if (deadlineNs - nowNs > 0) {
                // Block on the GPU on a thread of its own - not on this one, which delivers every
                // window's planned hand-backs, nor on the X server thread that may have called
                // this - then come back here: the plan may have changed meanwhile, and a newer
                // composite may have sampled the buffer again.
                gpuIdleExecutor().execute(() -> {
                    view.waitGuestBufferIdle(p.pixmap.drawable, deadlineNs - System.nanoTime());
                    limiterExecutor().execute(() -> deliverIdle(p, deadlineNs));
                });
                return;
            }
        }
        sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
    }

    /**
     * Applies the renderer's plan after a present: plan[0] earliest release, plan[1] the
     * previously newest frame is on screen until then, plan[2] max buffers out (0 = no limit).
     */
    private void planReleasesLocked(WindowPacing wp, long[] plan, long nowNs, ArrayList<Presented> now) {
        final long releaseNs = plan[0];
        final long onScreenNs = plan[1];
        final int depth = (int) plan[2];
        final int keep = wp.keep();
        while (wp.held.size() > keep && (depth <= 0 || wp.outstanding.size() < depth)) {
            Presented p = wp.held.pollFirst();
            boolean shownUntilNewFrame = keep == 1 && wp.held.size() == 1;
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
                if (pacing.get(windowId) != wp || wp.held.size() <= wp.keep()) return;
                releaseLocked(wp, wp.held.pollFirst(), 0, System.nanoTime(), now);
            }
            for (Presented p : now) deliverIdle(p, NO_DEADLINE);
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
        inputStream.readInt();   // options: COPY is all this server does, ASYNC changes nothing here
        inputStream.skip(4);
        // The vsync count the client wants the frame for. Mesa's X11 WSI sends 0 in MAILBOX and
        // IMMEDIATE, and in FIFO waits for each CompleteNotify, then asks for the next vsync
        // (last completed MSC + 1). Completing at once made FIFO run unthrottled, like MAILBOX.
        long targetMsc = inputStream.readLong();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        final long nowNs = System.nanoTime();
        final long[] plan = new long[4];
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
            wp.seen.add(pixmap);
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

        // Complete when the frame goes on screen (plan[3], its display slot, while the guest
        // keeps up with its slots), not when it is received: a guest that starts its next frame
        // on completion (DXVK 2.3+ through present_wait) then samples input right after this
        // one is shown. Never before an earlier present's completion, so they stay in order.
        long completeAt = plan[3];
        // A requested vsync is honoured too (FIFO): complete at that vsync, as an X server that
        // copies at vblank does. The frame itself is already shown; this only paces the client.
        final long minMsc = targetMsc > 0 ? targetMsc : 0;
        if (minMsc > 0) {
            long targetAt = vsyncTimeOf(minMsc, nowNs);
            if (targetAt > completeAt) completeAt = targetAt;
        }
        final WindowPacing pacingState = wp;
        synchronized (limiterLock) {
            if (wp.lastCompleteAtNs > nowNs && wp.lastCompleteAtNs > completeAt)
                completeAt = wp.lastCompleteAtNs;
            wp.lastCompleteAtNs = completeAt;
        }
        if (completeAt - nowNs > 200_000L) {
            limiterExecutor().schedule(() -> sendComplete(window, pacingState, serial, minMsc),
                    completeAt - nowNs, TimeUnit.NANOSECONDS);
        } else {
            sendComplete(window, wp, serial, minMsc);
        }

        synchronized (limiterLock) {
            wp.held.addLast(new Presented(window, pixmap, serial, idleFence));
            planReleasesLocked(wp, plan, nowNs, releaseNow);
            armStallGuardLocked(window.id, wp);
        }
        for (Presented p : releaseNow) deliverIdle(p, NO_DEADLINE);
    }

    /** Furthest ahead a requested vsync is waited for; beyond this the request is not one. */
    private static final long MAX_TARGET_WAIT_NS = 250_000_000L;

    /**
     * When vsync {@code msc} happens (CLOCK_MONOTONIC ns), 0 when it already has or the
     * request is implausibly far ahead (a client's own MSC base, a stale count).
     */
    private long vsyncTimeOf(long msc, long nowNs) {
        com.winlator.cmod.widget.XServerRendererView view = xServer.getXServerView();
        long[] vsync = new long[3];
        long curMsc, ustNs, periodNs;
        if (view != null && view.getVsync(nowNs, vsync) && vsync[2] > 0) {
            curMsc = vsync[0]; ustNs = vsync[1]; periodNs = vsync[2];
        } else {
            curMsc = nowNs / FAKE_INTERVAL_NS; ustNs = curMsc * FAKE_INTERVAL_NS; periodNs = FAKE_INTERVAL_NS;
        }
        if (msc <= curMsc) return 0;
        long ahead = msc - curMsc;
        if (ahead > MAX_TARGET_WAIT_NS / periodNs + 1) return 0;
        long at = ustNs + ahead * periodNs;
        return at - nowNs > MAX_TARGET_WAIT_NS ? 0 : at;
    }

    private void sendComplete(Window window, WindowPacing wp, int serial, long minMsc) {
        final long nowNs = System.nanoTime();
        com.winlator.cmod.widget.XServerRendererView view = xServer.getXServerView();
        long[] vsync = new long[3];
        long msc, ustNs;
        if (view != null && view.getVsync(nowNs, vsync)) {
            // The display's own vsync count and time (Choreographer), not a made-up 60 Hz clock.
            msc = vsync[0];
            ustNs = vsync[1];
        } else {
            msc = nowNs / FAKE_INTERVAL_NS;
            ustNs = msc * FAKE_INTERVAL_NS;
        }
        // Completed for the vsync that was asked for. A timer a hair early would otherwise
        // report the previous count, and FIFO would ask for the same vsync again and complete
        // at once: two frames in one refresh.
        if (minMsc > msc && minMsc - msc <= 2) {
            long periodNs = vsync[2] > 0 ? vsync[2] : FAKE_INTERVAL_NS;
            ustNs += (minMsc - msc) * periodNs;
            msc = minMsc;
        }
        synchronized (limiterLock) {
            // MSC must never go backwards for a window (clock switch, surface re-creation):
            // WSIs compute their next target from the last completed MSC.
            if (msc < wp.lastMsc) msc = wp.lastMsc;
            wp.lastMsc = msc;
        }
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ustNs / 1000, msc);
    }

    // One renderer call per present: shows the pixmap in the window and counts the present
    // together, waking the render thread once.
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
                    wp.seen.remove(pixmap);
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
