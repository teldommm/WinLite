package com.winlator.cmod.core;

import java.util.ArrayList;

/**
 * The app's own threads that carry a frame towards the screen besides the renderer's: the X
 * connector's epoll thread and the Present extension's limiter threads. They join the renderer's
 * ADPF hint session (see VulkanRendererAdpf.cpp), so the system sees the whole pipeline of one
 * frame and not only its last step.
 *
 * A thread registers itself from inside (it needs its own native tid) for as long as it lives.
 * Only threads of this process count: a hint session cannot hold another process's threads, so
 * the guest's Wine processes are out of reach.
 *
 * The renderer subscribes only while the hint is switched on; with it off, registering costs a
 * list insert at thread start and nothing else.
 */
public final class PerfHintThreads {
    public interface Listener {
        /** The registered set changed; {@code tids} is a copy. May run on any thread. */
        void onPerfHintThreadsChanged(int[] tids);
    }

    private static final Object LOCK = new Object();
    private static final ArrayList<Integer> TIDS = new ArrayList<>();
    private static Listener listener;

    private PerfHintThreads() {}

    /** Registers the calling thread; pass the returned id to {@link #unregister}. */
    public static int register() {
        final int tid = android.os.Process.myTid();
        final Listener target;
        final int[] snapshot;
        synchronized (LOCK) {
            if (!TIDS.contains(tid)) TIDS.add(tid);
            target = listener;
            snapshot = target != null ? snapshotLocked() : null;
        }
        if (target != null) target.onPerfHintThreadsChanged(snapshot);
        return tid;
    }

    public static void unregister(int tid) {
        final Listener target;
        final int[] snapshot;
        synchronized (LOCK) {
            if (!TIDS.remove((Integer) tid)) return;
            target = listener;
            snapshot = target != null ? snapshotLocked() : null;
        }
        if (target != null) target.onPerfHintThreadsChanged(snapshot);
    }

    /** Runs {@code body} on the calling thread as a registered one. */
    public static void runRegistered(Runnable body) {
        final int tid = register();
        try {
            body.run();
        } finally {
            unregister(tid);
        }
    }

    /** Subscribes (null = unsubscribes) and, when subscribing, delivers the current set at once. */
    public static void setListener(Listener value) {
        final int[] snapshot;
        synchronized (LOCK) {
            listener = value;
            snapshot = value != null ? snapshotLocked() : null;
        }
        if (value != null) value.onPerfHintThreadsChanged(snapshot);
    }

    /** Unsubscribes {@code value} if it is still the subscriber (a newer renderer may have replaced it). */
    public static void clearListener(Listener value) {
        synchronized (LOCK) {
            if (listener == value) listener = null;
        }
    }

    /** The registered threads now. */
    public static int[] snapshot() {
        synchronized (LOCK) {
            return snapshotLocked();
        }
    }

    private static int[] snapshotLocked() {
        final int[] out = new int[TIDS.size()];
        for (int i = 0; i < out.length; i++) out[i] = TIDS.get(i);
        return out;
    }
}
