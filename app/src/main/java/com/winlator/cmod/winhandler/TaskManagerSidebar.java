package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.ui.ProcessRowData;
import com.winlator.cmod.ui.TaskManagerPanelState;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

// Pure data/polling engine now — no View references anywhere. Every visual concern
// (styling, layout, the process list, the CPU/memory cards) moved to the Compose panel
// in com.winlator.cmod.ui.TaskManagerSidebarPanel.kt; this class only talks to WinHandler,
// CPUStatus and ActivityManager, then pushes results into the shared TaskManagerPanelState.
public class TaskManagerSidebar implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final TaskManagerPanelState state;
    private final Object lock = new Object();
    private final List<ProcessRowData> pending = new ArrayList<>();
    private Timer timer;

    public TaskManagerSidebar(XServerDisplayActivity activity, TaskManagerPanelState state) {
        this.activity = activity;
        this.state = state;
    }

    public void start() {
        stop();
        activity.getWinHandler().setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.getWinHandler().listProcesses();
                activity.runOnUiThread(() -> {
                    updateCPUInfo();
                    updateMemoryInfo();
                });
            }
        }, 0, 1000);
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        activity.getWinHandler().setOnGetProcessInfoListener(null);
    }

    public void updateNow() {
        activity.getWinHandler().listProcesses();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                if (numProcesses == 0) {
                    pending.clear();
                    state.setProcesses(new ArrayList<>());
                    return;
                }

                XServer xServer = activity.getXServer();
                Window window;
                try (XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    window = xServer.windowManager.findWindowByProcessName(processInfo.name);
                }

                // Reads pixmap buffers: hold the locks FreePixmap holds, so the icon pixmaps
                // cannot be released underneath the copy.
                Bitmap icon = null;
                if (window != null) {
                    try (XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        icon = xServer.pixmapManager.getWindowIcon(window);
                    }
                }

                ProcessRowData row = new ProcessRowData(
                        processInfo.pid,
                        processInfo.name,
                        processInfo.name + (processInfo.wow64Process ? " *32" : ""),
                        "PID: " + processInfo.pid,
                        processInfo.getFormattedMemoryUsage(),
                        processInfo.affinityMask,
                        icon
                );

                if (index < pending.size()) pending.set(index, row);
                else pending.add(row);

                if (index == numProcesses - 1) {
                    while (pending.size() > numProcesses) pending.remove(pending.size() - 1);
                    state.setProcesses(new ArrayList<>(pending));
                }
            }
        });
    }

    private void updateCPUInfo() {
        short[] clockSpeeds = com.winlator.cmod.core.CPUStatus.getCurrentClockSpeeds();
        if (clockSpeeds.length == 0) return;

        int totalClockSpeed = 0;
        short maxClockSpeed = 0;
        for (int i = 0; i < clockSpeeds.length; i++) {
            short clockSpeed = com.winlator.cmod.core.CPUStatus.getMaxClockSpeed(i);
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max(maxClockSpeed, clockSpeed);
        }

        int avgClockSpeed = totalClockSpeed / clockSpeeds.length;
        byte cpuUsagePercent = maxClockSpeed == 0 ? 0 :
                (byte) (((float) avgClockSpeed / maxClockSpeed) * 100.0f);

        state.setCpuLabel(cpuUsagePercent + "%");
    }

    private String formatMemoryPair(long usedBytes, long totalBytes) {
        final double gib = 1024.0 * 1024.0 * 1024.0;
        if (totalBytes >= gib) {
            return String.format(Locale.US, "%.1f / %.1f GB", usedBytes / gib, totalBytes / gib);
        }

        final double mib = 1024.0 * 1024.0;
        return String.format(Locale.US, "%.0f / %.0f MB", usedBytes / mib, totalBytes / mib);
    }

    private void updateMemoryInfo() {
        ActivityManager activityManager =
                (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;

        state.setMemoryLabel(formatMemoryPair(usedMem, memoryInfo.totalMem));
    }
}
