package com.winlator.cmod.core;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thread placement: keeps the few threads that really load a core (the game's main / render /
 * physics threads) on the fastest cores the player allowed, and moves everything else of the
 * Wine processes off those cores.
 *
 * It sits on top of the per-process settings and never widens them: the allowed cores of a
 * process (the "Processor Affinity" lists, applied through Wine) are read back from the process's
 * main thread and every decision is made inside that set. Sync CPU Topology is independent of this.
 *
 * Every {@link #TICK_MS} the controller samples utime+stime of every thread in
 * /proc/PID/task/TID/stat. A thread becomes "hot" after {@link #ENTER_SAMPLES} samples above
 * {@link #HOT_ENTER} of a core and stops being hot after {@link #EXIT_SAMPLES} samples below
 * {@link #HOT_EXIT}, so threads do not bounce between sets. With no hot thread nothing is changed
 * (and anything that was changed is put back to the process's own mask).
 *
 * Wine re-applies the process mask whenever a window is mapped; that shows up as a change of
 * the main thread's mask that the controller did not make, and is taken as the new base.
 */
public final class ThreadPlacementController {
    private static final String TAG = "ThreadPlacement";

    /** Per-thread affinity, backed by {@link ThreadAffinity}. */
    interface AffinityApi {
        /** 0 on success, -errno on failure. */
        int set(int tid, long mask);

        /** The thread's mask, or -1. */
        long get(int tid);
    }

    interface PidSource {
        int[] pids();
    }

    static final long TICK_MS = 2000L;
    static final double HOT_ENTER = 0.45;
    static final double HOT_EXIT = 0.25;
    static final int ENTER_SAMPLES = 2;
    static final int EXIT_SAMPLES = 3;
    static final int MAX_HOT = 4;
    /** Every Nth tick the masks of managed threads are read back, to catch resets done by Wine. */
    static final int VERIFY_EVERY = 5;
    /** The process list is rescanned every Nth tick, the thread lists of known processes every tick. */
    static final int PID_RESCAN_EVERY = 3;
    static final int GIVE_UP_AFTER_FAILURES = 24;
    private static final int ESRCH = 3;

    private static final class ProcState {
        final int pid;
        /** The process's own allowed mask (what the player configured), 0 = not read yet. */
        long baseMask;
        /** Base changed during this sampling pass (Wine re-applied the process mask). */
        boolean baseChanged;
        /** Some thread of this process has been moved by the controller. */
        boolean touched;
        int seenTick;

        ProcState(int pid) {
            this.pid = pid;
        }
    }

    private static final class ThreadState {
        final int pid;
        final int tid;
        String name = "";
        long lastTicks;
        double inst;
        double avg;
        int hotStreak;
        int coldStreak;
        boolean hot;
        boolean placed;
        boolean fresh = true;
        /** The mask the controller last asked for, 0 = thread is at its process's base. */
        long requested;
        /** The mask read back after our last change (the kernel may narrow it, e.g. by cpuset). */
        long effective;
        int seenTick;

        ThreadState(int pid, int tid) {
            this.pid = pid;
            this.tid = tid;
        }
    }

    static final class StatSample {
        String name;
        long utime;
        long stime;
    }

    private final File procRoot;
    private final AffinityApi api;
    private final PidSource pidSource;
    private final int[] capacity;
    private final long onlineMask;
    private final double clkTck;

    private final Map<Integer, ProcState> procs = new HashMap<>();
    private final Map<Long, ThreadState> threads = new HashMap<>();
    private final byte[] statBuf = new byte[2048];
    private final StatSample sample = new StatSample();

    private int[] cachedPids = new int[0];
    private int tickCount;
    private long lastNs;
    private long totalDeltaTicks;
    private int consecutiveFailures;
    private boolean gaveUp;
    private String lastSignature = "";

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;

    ThreadPlacementController(File procRoot, AffinityApi api, PidSource pidSource, int[] capacity, double clkTck) {
        this.procRoot = procRoot;
        this.api = api;
        this.pidSource = pidSource;
        this.capacity = capacity;
        this.clkTck = clkTck > 0 ? clkTck : 100.0;
        this.onlineMask = capacity.length >= 63 ? -1L >>> 1 : (1L << capacity.length) - 1L;
    }

    /**
     * Builds the controller for the running game, or null where it cannot work: native library
     * missing, fewer than 4 CPUs (nothing to arrange) or more than 62 (does not fit a 64-bit mask).
     */
    public static ThreadPlacementController create() {
        if (!ThreadAffinity.isAvailable()) {
            Log.i(TAG, "native affinity support not available");
            return null;
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        if (cpus < 4 || cpus > 62) {
            Log.i(TAG, "not enabled for " + cpus + " CPUs");
            return null;
        }

        final int selfPid = Process.myPid();
        PidSource source = () -> {
            List<String> names = ProcessHelper.listRunningWineProcesses();
            int[] out = new int[names.size()];
            int count = 0;
            for (String name : names) {
                try {
                    int pid = Integer.parseInt(name);
                    if (pid != selfPid) out[count++] = pid;
                } catch (NumberFormatException ignored) {
                }
            }
            return Arrays.copyOf(out, count);
        };

        double clk = 100.0;
        try {
            long value = Os.sysconf(OsConstants._SC_CLK_TCK);
            if (value > 0) clk = value;
        } catch (Throwable ignored) {
        }

        AffinityApi api = new AffinityApi() {
            @Override
            public int set(int tid, long mask) {
                return ThreadAffinity.setAffinity(tid, mask);
            }

            @Override
            public long get(int tid) {
                return ThreadAffinity.getAffinity(tid);
            }
        };
        return new ThreadPlacementController(new File("/proc"), api, source,
                readCapacities(new File("/sys/devices/system/cpu"), cpus), clk);
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /** Starts (or resumes) sampling. The first tick after a start only records a baseline. */
    public synchronized void start() {
        if (gaveUp || (future != null && !future.isDone())) return;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "WinLiteThreadPlacement");
                thread.setDaemon(true);
                return thread;
            });
        }
        lastNs = 0;
        future = executor.scheduleWithFixedDelay(this::safeTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops sampling; masks already set stay as they are. */
    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    /** Stops for good and frees the sampling thread. */
    public synchronized void release() {
        stop();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
        }
        if (gaveUp) {
            synchronized (this) {
                if (future != null) future.cancel(false);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // One sampling pass
    // ---------------------------------------------------------------------------------------

    synchronized void tick() {
        if (gaveUp) return;

        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0 : (now - lastNs) / 1e9;
        boolean baseline = dt < 0.2;
        lastNs = now;
        tickCount++;

        if (cachedPids.length == 0 || tickCount % PID_RESCAN_EVERY == 1) cachedPids = pidSource.pids();

        totalDeltaTicks = 0;
        for (ProcState proc : procs.values()) proc.baseChanged = false;
        for (int pid : cachedPids) sampleProcess(pid, baseline ? 0 : dt);

        Iterator<ThreadState> threadIt = threads.values().iterator();
        while (threadIt.hasNext()) {
            if (threadIt.next().seenTick != tickCount) threadIt.remove();
        }
        Iterator<ProcState> procIt = procs.values().iterator();
        while (procIt.hasNext()) {
            if (procIt.next().seenTick != tickCount) procIt.remove();
        }

        if (baseline) return;
        // Nothing ran since the last tick: the game is stopped (paused by hand or in the
        // background). Keep everything as it is instead of "cooling" all threads down.
        if (totalDeltaTicks == 0) return;

        reconcile();
    }

    private void sampleProcess(int pid, double dt) {
        File[] tasks = new File(procRoot, pid + "/task").listFiles();
        if (tasks == null) return;

        ProcState proc = procs.get(pid);
        if (proc == null) {
            proc = new ProcState(pid);
            procs.put(pid, proc);
        }
        proc.seenTick = tickCount;

        for (File task : tasks) {
            int tid;
            try {
                tid = Integer.parseInt(task.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (tid <= 0 || !readStat(new File(task, "stat"), sample)) continue;

            long key = key(pid, tid);
            long ticks = sample.utime + sample.stime;
            ThreadState thread = threads.get(key);
            if (thread == null) {
                thread = new ThreadState(pid, tid);
                thread.lastTicks = ticks;
                threads.put(key, thread);
            } else {
                long delta = Math.max(0L, ticks - thread.lastTicks);
                thread.lastTicks = ticks;
                totalDeltaTicks += delta;
                if (dt > 0) updateLoad(thread, delta / clkTck / dt);
            }
            thread.name = sample.name;
            thread.seenTick = tickCount;

            if (tid == pid) refreshBase(proc, thread);
        }
    }

    private static void updateLoad(ThreadState thread, double inst) {
        thread.inst = inst;
        thread.avg = thread.avg * 0.5 + inst * 0.5;
        if (inst >= HOT_ENTER) {
            thread.hotStreak++;
            thread.coldStreak = 0;
        } else if (inst < HOT_EXIT) {
            thread.coldStreak++;
            thread.hotStreak = 0;
        } else {
            // In between: neither warming up nor cooling down.
            thread.hotStreak = 0;
            thread.coldStreak = 0;
        }
        if (!thread.hot && thread.hotStreak >= ENTER_SAMPLES) thread.hot = true;
        if (thread.hot && thread.coldStreak >= EXIT_SAMPLES) thread.hot = false;
    }

    /**
     * The main thread carries the process's own mask unless the controller moved it. Anything
     * other than where we left it means the whole process was reset (Wine re-applies the process
     * mask whenever a window is mapped, to every thread, even when the mask is the same as
     * before): what the main thread has now is the base, and the process is placed again.
     */
    private void refreshBase(ProcState proc, ThreadState main) {
        long current = api.get(main.tid);
        if (current <= 0) return;
        current &= onlineMask;
        if (current == 0) return;
        if (proc.baseMask == 0) {
            proc.baseMask = current;
            return;
        }
        long expected = main.requested != 0 ? main.effective : proc.baseMask;
        if (current != expected) {
            proc.baseMask = current;
            proc.baseChanged = true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------

    private void reconcile() {
        List<ThreadState> hot = new ArrayList<>();
        for (ThreadState thread : threads.values()) {
            thread.placed = false;
            if (thread.hot) hot.add(thread);
        }
        Collections.sort(hot, (a, b) -> Double.compare(b.avg, a.avg));
        int placedCount = Math.min(MAX_HOT, hot.size());
        for (int i = 0; i < placedCount; i++) hot.get(i).placed = true;

        boolean verify = tickCount % VERIFY_EVERY == 0;
        Map<Long, Long> performanceByBase = new HashMap<>();

        for (ThreadState thread : threads.values()) {
            ProcState proc = procs.get(thread.pid);
            if (proc == null) continue;
            if (proc.baseChanged) {
                // Wine reset the whole process to the new base.
                thread.requested = 0;
                thread.effective = 0;
            }

            long base = proc.baseMask != 0 ? proc.baseMask : onlineMask;
            Long cached = performanceByBase.get(base);
            if (cached == null) {
                cached = performanceMask(base, placedCount, capacity);
                performanceByBase.put(base, cached);
            }
            long perf = cached;
            long others = base & ~perf;
            if (others == 0) others = base;
            long want = placedCount == 0 ? base : (thread.placed ? perf : others);

            if (thread.requested == 0) {
                // At the process's base.
                if (want != base) {
                    apply(proc, thread, want, base);
                } else if (thread.fresh && proc.touched) {
                    // New thread of a process we touched: it may have inherited a moved mask.
                    long current = api.get(thread.tid);
                    if (current > 0 && (current & onlineMask) != base) apply(proc, thread, base, base);
                }
            } else if (thread.requested != want) {
                apply(proc, thread, want, base);
            } else if (verify) {
                long current = api.get(thread.tid);
                if (current > 0 && current != thread.effective) apply(proc, thread, want, base);
            }
            thread.fresh = false;
        }
        for (ProcState proc : procs.values()) proc.baseChanged = false;

        logPlacement(hot, placedCount);
    }

    private void apply(ProcState proc, ThreadState thread, long want, long base) {
        int result = api.set(thread.tid, want);
        if (result == 0) {
            consecutiveFailures = 0;
            proc.touched = true;
            if (want == base) {
                thread.requested = 0;
                thread.effective = 0;
            } else {
                thread.requested = want;
                long current = api.get(thread.tid);
                thread.effective = current > 0 ? current : want;
            }
        } else if (result != -ESRCH) {
            if (++consecutiveFailures >= GIVE_UP_AFTER_FAILURES) {
                gaveUp = true;
                Log.w(TAG, "giving up: setting thread affinity keeps failing (last error " + result + ")");
            }
        }
    }

    private void logPlacement(List<ThreadState> hot, int placedCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < placedCount; i++) {
            ThreadState thread = hot.get(i);
            sb.append(thread.pid).append('/').append(thread.tid).append(' ').append(thread.name).append(' ');
        }
        String signature = sb.toString();
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        if (placedCount == 0) {
            Log.i(TAG, "no hot threads, masks restored");
            return;
        }
        StringBuilder text = new StringBuilder("hot threads:");
        for (int i = 0; i < placedCount; i++) {
            ThreadState thread = hot.get(i);
            text.append(String.format(Locale.US, " [%d/%d %s %.0f%%]", thread.pid, thread.tid, thread.name, thread.avg * 100));
        }
        Log.i(TAG, text.toString());
    }

    // ---------------------------------------------------------------------------------------
    // Pure helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The cores for the hot threads: the {@code hotCount} fastest cores of {@code base}, at most
     * half of them (so the other threads keep room) and at least one. Ties go to the higher index,
     * which is the faster cluster on the SoCs this runs on.
     */
    static long performanceMask(long base, int hotCount, int[] capacity) {
        int available = Long.bitCount(base);
        if (available == 0) return base;
        int count = Math.max(1, Math.min(hotCount, Math.max(1, available / 2)));

        List<Integer> cores = new ArrayList<>();
        for (int cpu = 0; cpu < 63; cpu++) {
            if ((base & (1L << cpu)) != 0) cores.add(cpu);
        }
        Collections.sort(cores, (a, b) -> {
            int capA = a < capacity.length ? capacity[a] : 0;
            int capB = b < capacity.length ? capacity[b] : 0;
            if (capA != capB) return Integer.compare(capB, capA);
            return Integer.compare(b, a);
        });

        long mask = 0;
        for (int i = 0; i < count && i < cores.size(); i++) mask |= 1L << cores.get(i);
        return mask;
    }

    /**
     * Fields of /proc/PID/task/TID/stat. The comm field is in parentheses and may contain spaces
     * and parentheses itself, so the rest is parsed after the LAST closing parenthesis. After
     * ") " the fields start at number 3 (state), so utime (14) and stime (15) are entries 11, 12.
     */
    static boolean parseStat(String stat, StatSample out) {
        int open = stat.indexOf('(');
        int close = stat.lastIndexOf(')');
        if (open < 0 || close < open || close + 2 > stat.length()) return false;
        String[] fields = stat.substring(close + 2).trim().split(" ");
        if (fields.length < 13) return false;
        try {
            out.utime = Long.parseLong(fields[11]);
            out.stime = Long.parseLong(fields[12]);
        } catch (NumberFormatException e) {
            return false;
        }
        out.name = stat.substring(open + 1, close);
        return true;
    }

    private boolean readStat(File file, StatSample out) {
        try (FileInputStream in = new FileInputStream(file)) {
            int count = in.read(statBuf);
            if (count <= 0) return false;
            return parseStat(new String(statBuf, 0, count, StandardCharsets.ISO_8859_1), out);
        } catch (IOException e) {
            return false;
        }
    }

    private static long key(int pid, int tid) {
        return ((long) pid << 32) | (tid & 0xffffffffL);
    }

    /**
     * Relative core speed: cpu_capacity when every core reports it, else the maximum frequency,
     * else all zeros (then the highest indices count as the fastest).
     */
    static int[] readCapacities(File cpuRoot, int cpus) {
        int[] capacity = new int[cpus];
        boolean complete = true;
        for (int cpu = 0; cpu < cpus; cpu++) {
            capacity[cpu] = readInt(new File(cpuRoot, "cpu" + cpu + "/cpu_capacity"));
            if (capacity[cpu] <= 0) complete = false;
        }
        if (complete) return capacity;

        complete = true;
        for (int cpu = 0; cpu < cpus; cpu++) {
            capacity[cpu] = readInt(new File(cpuRoot, "cpu" + cpu + "/cpufreq/cpuinfo_max_freq"));
            if (capacity[cpu] <= 0) complete = false;
        }
        if (complete) return capacity;
        return new int[cpus];
    }

    private static int readInt(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[32];
            int count = in.read(buf);
            if (count <= 0) return -1;
            return Integer.parseInt(new String(buf, 0, count, StandardCharsets.ISO_8859_1).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
