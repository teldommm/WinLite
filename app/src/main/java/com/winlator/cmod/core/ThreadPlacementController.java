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
 * process (the "Processor Affinity" lists, applied through Wine's SetProcessAffinityMask) are
 * worked out from the masks of its threads and every decision is made inside that set. Sync CPU
 * Topology is independent of this.
 *
 * Every {@link #TICK_MS} the controller samples utime+stime and the affinity mask of every thread
 * of the Wine processes. A thread becomes "hot" after {@link #ENTER_SAMPLES} samples above
 * {@link #HOT_ENTER} of a core and stops being hot after {@link #EXIT_SAMPLES} samples below
 * {@link #HOT_EXIT}, so threads do not bounce between sets. For a thread sitting on the fast
 * cores the exit test uses the load it would have on the slowest of the other cores, otherwise
 * a thread that is light on a prime core but heavy on a little one would be moved back and forth.
 * With no hot thread nothing is changed (and anything that was changed is put back to the
 * process's own mask).
 *
 * Who owns which mask:
 * <ul>
 * <li>The process base is the OR of its threads' masks when it is first seen. Wine keeps every
 *     thread mask inside the process mask, so a game that pins one thread (even the main one)
 *     does not shrink the base.</li>
 * <li>A process reset (SetProcessAffinityMask, sent by WinLite when a window is mapped or called
 *     by the game itself) rewrites every thread to the same mask. It is recognised when the same
 *     unexpected mask shows up on most of the threads; that mask becomes the new base.</li>
 * <li>A single thread whose mask changed to something the controller never handed out was pinned
 *     by the game (SetThreadAffinityMask). After {@link #FOREIGN_SAMPLES} samples it is left
 *     alone until the game puts it back to the base or the process is reset.</li>
 * <li>A thread that shows one of the masks the controller handed out without having been moved
 *     inherited it from its creator (plain pthread_create, which Wine does not re-mask) and is
 *     managed like any other thread.</li>
 * </ul>
 * sched_getaffinity reports the mask ANDed with the active CPUs, so masks are compared only on
 * the CPUs that showed up in some thread's mask this tick: a core that is offline or paused
 * (core_ctl) does not look like a change.
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
    /** Samples a thread must show a mask nobody expected before it is treated as pinned by the game. */
    static final int FOREIGN_SAMPLES = 2;
    /** The process list is rescanned every Nth tick, the thread lists of known processes every tick. */
    static final int PID_RESCAN_EVERY = 3;
    static final int GIVE_UP_AFTER_FAILURES = 24;
    /** Masks handed out per process that are still recognised as "ours" (for inherited masks). */
    private static final int MAX_HANDED_OUT = 8;
    private static final int ESRCH = 3;

    private static final class ProcState {
        final int pid;
        /** The process's own allowed mask (what the player configured), 0 = not known yet. */
        long baseMask;
        /** Masks the controller set on threads of this process, newest last. */
        final ArrayList<Long> handedOut = new ArrayList<>();
        int seenTick;

        ProcState(int pid) {
            this.pid = pid;
        }

        void remember(long mask) {
            handedOut.remove(Long.valueOf(mask));
            handedOut.add(mask);
            while (handedOut.size() > MAX_HANDED_OUT) handedOut.remove(0);
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
        /** The mask the controller last asked for, 0 = thread is at its process's base. */
        long requested;
        /** The mask read back after our last change (the kernel may narrow it, e.g. by cpuset). */
        long effective;
        /** The mask read this tick, 0 = unknown. */
        long current;
        /** Carries a mask the controller handed out to another thread (inherited at creation). */
        boolean inherited;
        /** Consecutive samples with a mask nobody expected. */
        int foreignStreak;
        /** Pinned by the game: never touched until it is back at the base or the process is reset. */
        boolean foreign;
        /**
         * Factor from the measured load to the load on the slowest core outside the fast set,
         * 1 when the thread is not on the fast set.
         */
        double exitScale = 1.0;
        int seenTick;

        ThreadState(int pid, int tid) {
            this.pid = pid;
            this.tid = tid;
        }

        /** Left alone this tick: pinned by the game, or maybe about to be found so. */
        boolean handsOff() {
            return foreign || foreignStreak > 0;
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
    /** CPUs seen in any thread mask this tick (the active ones inside the processes' bases). */
    private long seenCpus;
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
        seenCpus = 0;
        for (int pid : cachedPids) sampleProcess(pid, baseline ? 0 : dt);

        Iterator<ThreadState> threadIt = threads.values().iterator();
        while (threadIt.hasNext()) {
            if (threadIt.next().seenTick != tickCount) threadIt.remove();
        }
        Iterator<ProcState> procIt = procs.values().iterator();
        while (procIt.hasNext()) {
            if (procIt.next().seenTick != tickCount) procIt.remove();
        }

        // Masks are only judged once every thread of the tick has been read (seenCpus).
        Map<Integer, List<ThreadState>> byPid = new HashMap<>();
        for (ThreadState thread : threads.values()) {
            List<ThreadState> list = byPid.get(thread.pid);
            if (list == null) {
                list = new ArrayList<>();
                byPid.put(thread.pid, list);
            }
            list.add(thread);
        }
        for (ProcState proc : procs.values()) {
            List<ThreadState> list = byPid.get(proc.pid);
            if (list != null) classify(proc, list);
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

            long mask = api.get(tid);
            thread.current = mask > 0 ? mask & onlineMask : 0;
            seenCpus |= thread.current;
        }
    }

    private static void updateLoad(ThreadState thread, double measured) {
        // On the fast set the thread is judged by the load it would have elsewhere, so that
        // leaving the fast set does not make it hot again right away.
        double inst = measured * thread.exitScale;
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

    /** The mask as sched_getaffinity would report it now (only CPUs that are up). */
    private long visible(long mask) {
        long v = mask & seenCpus;
        return v != 0 ? v : mask;
    }

    private boolean isHandedOut(ProcState proc, long current) {
        for (int i = proc.handedOut.size() - 1; i >= 0; i--) {
            if (visible(proc.handedOut.get(i)) == current) return true;
        }
        return false;
    }

    /** Works out the base of the process and who owns each thread's mask (see the class doc). */
    private void classify(ProcState proc, List<ThreadState> list) {
        if (proc.baseMask == 0) {
            long base = 0;
            for (ThreadState thread : list) base |= thread.current;
            proc.baseMask = base;
            return;
        }

        long base = visible(proc.baseMask);

        // Process reset (SetProcessAffinityMask rewrites every thread): one mask M on most of the
        // threads. Support counts every thread showing M, also where M is what we expected for it:
        // a reset to a mask equal to one we handed out (the player picks "all but the prime core"
        // in the task manager) leaves the threads already on it unchanged, and they still count.
        // M must also be unexpected somewhere in a way inheritance cannot explain:
        //  - on a thread we moved that no longer has our mask, or
        //  - on an unmoved thread off the base, with a mask we never handed out, or
        //  - a mask of ours on unmoved threads that are most of the process (a few threads
        //    inherit a mask of ours from their creator; most of them never do).
        Map<Long, int[]> candidates = new HashMap<>(); // M -> {support, strong, unmoved on M}
        int known = 0;
        for (ThreadState thread : list) {
            if (thread.current == 0) continue;
            known++;
            boolean strong;
            if (thread.requested != 0) {
                strong = thread.current != visible(thread.effective);
            } else {
                strong = thread.current != base && !isHandedOut(proc, thread.current);
            }
            boolean inheritedLike = thread.requested == 0 && thread.current != base && !strong;
            if (!strong && !inheritedLike) continue;
            int[] c = candidates.get(thread.current);
            if (c == null) {
                c = new int[3];
                candidates.put(thread.current, c);
            }
            if (strong) c[1] = 1;
        }
        for (ThreadState thread : list) {
            int[] c = candidates.get(thread.current);
            if (c == null) continue;
            c[0]++;
            if (thread.requested == 0) c[2]++;
        }
        long resetMask = 0;
        int resetCount = 0;
        for (Map.Entry<Long, int[]> entry : candidates.entrySet()) {
            int[] c = entry.getValue();
            boolean seeded = c[1] != 0 || c[2] * 2 > known;
            if (seeded && c[0] > resetCount) {
                resetCount = c[0];
                resetMask = entry.getKey();
            }
        }
        if (resetCount * 2 > known && (resetCount >= 2 || known == 1)) {
            // handedOut is kept: a thread read just before Wine got to it still carries an old
            // mask of ours and must be taken as inherited, not as pinned by the game.
            proc.baseMask = resetMask;
            for (ThreadState thread : list) {
                thread.requested = 0;
                thread.effective = 0;
                thread.inherited = false;
                thread.foreign = false;
                thread.foreignStreak = 0;
                thread.exitScale = 1.0;
            }
            return;
        }

        for (ThreadState thread : list) {
            thread.inherited = false;
            if (thread.current == 0) continue;
            if (thread.foreign) {
                // The game's until it puts the thread back to the base.
                if (thread.current == base) thread.foreign = false;
                continue;
            }
            if (thread.requested != 0) {
                if (thread.current == visible(thread.effective)) {
                    thread.foreignStreak = 0;
                } else if (++thread.foreignStreak >= FOREIGN_SAMPLES) {
                    // The game set its own mask on a thread we had moved: hand it over.
                    markForeign(thread);
                }
            } else if (thread.current == base) {
                thread.foreignStreak = 0;
            } else if (isHandedOut(proc, thread.current)) {
                thread.inherited = true;
                thread.foreignStreak = 0;
            } else if (++thread.foreignStreak >= FOREIGN_SAMPLES) {
                markForeign(thread);
            }
        }
    }

    private static void markForeign(ThreadState thread) {
        thread.foreign = true;
        thread.foreignStreak = 0;
        thread.requested = 0;
        thread.effective = 0;
        thread.exitScale = 1.0;
        thread.placed = false;
    }

    // ---------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------

    private void reconcile() {
        List<ThreadState> hot = new ArrayList<>();
        for (ThreadState thread : threads.values()) {
            thread.placed = false;
            if (thread.hot && !thread.handsOff()) hot.add(thread);
        }
        Collections.sort(hot, (a, b) -> Double.compare(b.avg, a.avg));
        int placedCount = Math.min(MAX_HOT, hot.size());
        for (int i = 0; i < placedCount; i++) hot.get(i).placed = true;

        Map<Long, long[]> setsByBase = new HashMap<>();

        for (ThreadState thread : threads.values()) {
            if (thread.handsOff()) continue;
            ProcState proc = procs.get(thread.pid);
            if (proc == null || proc.baseMask == 0) continue;

            long base = proc.baseMask;
            long[] sets = setsByBase.get(base);
            if (sets == null) {
                long perf = performanceMask(visible(base), placedCount, capacity);
                long others = base & ~perf;
                if (others == 0) others = base;
                sets = new long[] {perf, others};
                setsByBase.put(base, sets);
            }
            long perf = sets[0];
            long others = sets[1];
            long want = placedCount == 0 ? base : (thread.placed ? perf : others);

            if (thread.requested == 0) {
                // At the process's base, or carrying an inherited mask of ours.
                if (want != base || thread.inherited) apply(proc, thread, want, base);
            } else if (thread.requested != want) {
                apply(proc, thread, want, base);
            }

            thread.exitScale = thread.requested != 0 && thread.requested == perf && perf != others
                    ? exitScale(visible(perf), visible(others), capacity) : 1.0;
        }

        logPlacement(hot, placedCount);
    }

    private void apply(ProcState proc, ThreadState thread, long want, long base) {
        int result = api.set(thread.tid, want);
        if (result == 0) {
            consecutiveFailures = 0;
            thread.inherited = false;
            if (want == base) {
                thread.requested = 0;
                thread.effective = 0;
            } else {
                proc.remember(want);
                thread.requested = want;
                long current = api.get(thread.tid);
                thread.effective = current > 0 ? current & onlineMask : want;
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
     * How much heavier a thread on {@code perf} would be on the slowest core of {@code others}:
     * capacity of the fastest perf core / capacity of the slowest other core, at least 1, and 1
     * when the capacities are unknown.
     */
    static double exitScale(long perf, long others, int[] capacity) {
        int fast = 0;
        int slow = Integer.MAX_VALUE;
        for (int cpu = 0; cpu < capacity.length && cpu < 63; cpu++) {
            long bit = 1L << cpu;
            if ((perf & bit) != 0) fast = Math.max(fast, capacity[cpu]);
            if ((others & bit) != 0) slow = Math.min(slow, capacity[cpu]);
        }
        if (fast <= 0 || slow <= 0 || slow == Integer.MAX_VALUE) return 1.0;
        return Math.max(1.0, (double) fast / slow);
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
