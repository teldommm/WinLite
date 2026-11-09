package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private HashMap<Integer, Long> limiterDeadlines;
    private ScheduledThreadPoolExecutor limiterExecutor;

    // Without an FPS limit the pixmap a client presents is reported idle when the renderer no
    // longer shows it, i.e. when the same window presents the next one - not right after the
    // present, while the renderer has not even sampled it yet (the client would redraw it under
    // the renderer's reads: tearing). Switch off with `setprop debug.winlite.present_idle_on_displace 0`
    // to get the old immediate idle back for comparison.
    private static final boolean IDLE_ON_DISPLACE = readFlag("debug.winlite.present_idle_on_displace", true);
    // Safety net only: a client must not wait for a buffer's idle forever. Normally the next
    // present of the window releases it; this fires for a client that never presents again while
    // it still waits for that buffer. It is long on purpose: an earlier release would hand the
    // buffer on screen back to a client that presents slowly.
    private static final long PENDING_IDLE_TIMEOUT_NS = 1_000_000_000L;
    private static final long PENDING_IDLE_SWEEP_MS = 100L;

    private static class PendingIdle {
        private final Window window;
        private final Pixmap pixmap;
        private final int serial;
        private final int idleFence;
        private final long timeNs;

        private PendingIdle(Window window, Pixmap pixmap, int serial, int idleFence, long timeNs) {
            this.window = window;
            this.pixmap = pixmap;
            this.serial = serial;
            this.idleFence = idleFence;
            this.timeNs = timeNs;
        }
    }

    // The buffer each window shows right now, whose idle is still owed. Guarded by pendingLock.
    private final Object pendingLock = new Object();
    private final HashMap<Integer, PendingIdle> pendingIdle = new HashMap<>();
    // Windows whose client presents one and the same pixmap over and over (single-buffered):
    // nothing ever displaces that buffer, so these keep the immediate idle.
    private final HashMap<Integer, Pixmap> singleBuffered = new HashMap<>();
    private boolean sweeperStarted;

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

    private static boolean readFlag(String name, boolean defaultValue) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class, String.class).invoke(null, name, "");
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.equals("0") || text.equalsIgnoreCase("false")) return false;
                if (text.equals("1") || text.equalsIgnoreCase("true")) return true;
            }
        } catch (Throwable ignored) {
            // not readable on this device: keep the default
        }
        return defaultValue;
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0 && syncExtension != null) syncExtension.setTriggered(idleFence);

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

    private void scheduleIdleNotify(Window window, Pixmap pixmap, int serial,
                                    int idleFence, int targetFps) {
        final long frameDurationNs = 1_000_000_000L / targetFps;
        final long now = System.nanoTime();
        final long deadline;
        final ScheduledThreadPoolExecutor executor;

        synchronized (limiterLock) {
            if (limiterDeadlines == null) limiterDeadlines = new HashMap<>();
            Long previous = limiterDeadlines.get(window.id);
            deadline = previous == null || previous < now
                    ? now + frameDurationNs : previous + frameDurationNs;
            limiterDeadlines.put(window.id, deadline);

            executor = limiterExecutorLocked();
        }

        executor.schedule(() -> sendIdleNotify(window, pixmap, serial, idleFence),
                Math.max(0L, deadline - now), TimeUnit.NANOSECONDS);
    }

    // limiterLock held.
    private ScheduledThreadPoolExecutor limiterExecutorLocked() {
        if (limiterExecutor == null) {
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "PresentFpsLimiter");
                thread.setDaemon(true);
                return thread;
            };
            limiterExecutor = new ScheduledThreadPoolExecutor(1, factory);
            limiterExecutor.setRemoveOnCancelPolicy(true);
        }
        return limiterExecutor;
    }

    // Records `pixmap` as the buffer `window` shows now and reports the one it replaced as idle.
    // A client that presents the same pixmap again and again has no second buffer to displace
    // the first with, so for it idle stays immediate (as without this logic).
    private void presentWithoutLimit(Window window, Pixmap pixmap, int serial, int idleFence) {
        PendingIdle previous = null;
        boolean immediate = false;
        synchronized (pendingLock) {
            Pixmap single = singleBuffered.get(window.id);
            if (single != null && single != pixmap) {
                singleBuffered.remove(window.id);   // another buffer showed up: not single-buffered
                single = null;
            }
            final PendingIdle shown = pendingIdle.get(window.id);
            if (single == null && shown != null && shown.pixmap == pixmap) {
                // The client reused the buffer it presented last: it only does that if it is not
                // waiting for that buffer to come back, i.e. there is only one.
                singleBuffered.put(window.id, pixmap);
                single = pixmap;
            }
            if (single != null) {
                pendingIdle.remove(window.id);
                immediate = true;
            } else {
                previous = pendingIdle.put(window.id,
                        new PendingIdle(window, pixmap, serial, idleFence, System.nanoTime()));
            }
            if (!immediate && !sweeperStarted) {
                sweeperStarted = true;
                final ScheduledThreadPoolExecutor executor;
                synchronized (limiterLock) {
                    executor = limiterExecutorLocked();
                }
                executor.scheduleWithFixedDelay(this::sweepPendingIdle,
                        PENDING_IDLE_SWEEP_MS, PENDING_IDLE_SWEEP_MS, TimeUnit.MILLISECONDS);
            }
        }
        if (immediate) {
            sendIdleNotify(window, pixmap, serial, idleFence);
        } else if (previous != null && previous.pixmap != pixmap) {
            sendIdleNotify(previous.window, previous.pixmap, previous.serial, previous.idleFence);
        }
    }

    private void sweepPendingIdle() {
        try {
            final long now = System.nanoTime();
            List<PendingIdle> expired = null;
            synchronized (pendingLock) {
                for (Iterator<Map.Entry<Integer, PendingIdle>> it = pendingIdle.entrySet().iterator(); it.hasNext(); ) {
                    PendingIdle pending = it.next().getValue();
                    if (now - pending.timeNs >= PENDING_IDLE_TIMEOUT_NS) {
                        if (expired == null) expired = new ArrayList<>();
                        expired.add(pending);
                        it.remove();
                    }
                }
            }
            if (expired != null) {
                for (PendingIdle pending : expired) {
                    sendIdleNotify(pending.window, pending.pixmap, pending.serial, pending.idleFence);
                }
            }
        } catch (Throwable t) {
            // a throwing periodic task would never run again
            Log.e("PresentExtension", "pending idle sweep failed", t);
        }
    }

    // The window stopped showing its buffer (unmapped or destroyed): nothing displaces it any more.
    private void flushPendingIdle(Window window) {
        final PendingIdle pending;
        synchronized (pendingLock) {
            pending = pendingIdle.remove(window.id);
            singleBuffered.remove(window.id);
        }
        if (pending != null) sendIdleNotify(pending.window, pending.pixmap, pending.serial, pending.idleFence);
    }

    // The client freed the pixmap: it is gone, there is nobody to tell - but a waiting idle fence
    // must not stay untriggered.
    private void dropPendingIdle(Pixmap pixmap) {
        List<PendingIdle> dropped = null;
        synchronized (pendingLock) {
            for (Iterator<Map.Entry<Integer, PendingIdle>> it = pendingIdle.entrySet().iterator(); it.hasNext(); ) {
                PendingIdle pending = it.next().getValue();
                if (pending.pixmap == pixmap) {
                    if (dropped == null) dropped = new ArrayList<>();
                    dropped.add(pending);
                    it.remove();
                }
            }
            singleBuffered.values().removeIf(p -> p == pixmap);
        }
        if (dropped != null && syncExtension != null) {
            for (PendingIdle pending : dropped) {
                if (pending.idleFence != 0) syncExtension.setTriggered(pending.idleFence);
            }
        }
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

        int targetFps = client.xServer.getXServerView() != null
                ? client.xServer.getXServerView().getFpsLimit() : 0;
        if (targetFps > 1000) targetFps = 1000;

        if (targetFps <= 0) {
            long ust = System.nanoTime() / 1000;
            long msc = ust / FAKE_INTERVAL;

            pixmap.drawable.updateDirect();
            if (IDLE_ON_DISPLACE) {
                presentWithoutLimit(window, pixmap, serial, idleFence);
            } else {
                sendIdleNotify(window, pixmap, serial, idleFence);
            }
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
            return;
        }

        long ust = System.nanoTime() / 1000;
        long msc = ust / (1_000_000L / targetFps);

        pixmap.drawable.updateDirect();
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
        scheduleIdleNotify(window, pixmap, serial, idleFence, targetFps);
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
    public void onUnmapWindow(Window window) {
        flushPendingIdle(window);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            dropPendingIdle((Pixmap) resource);
            return;
        }
        if (resource instanceof Window) {
            Window window = (Window) resource;
            flushPendingIdle(window);   // before the window's events go away
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
