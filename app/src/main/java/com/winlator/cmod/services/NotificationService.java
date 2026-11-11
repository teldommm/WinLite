package com.winlator.cmod.services;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.ProcessHelper;

import java.util.concurrent.atomic.AtomicBoolean;

// Keep-alive foreground service: while it runs, Android treats the app as foreground, so a
// minimized WinLite (library or a paused game session) isn't reclaimed like an ordinary
// background app.
//
// One service, one notification, two modes:
//  - library: started by MainActivity; tapping the notification opens the app.
//  - session: switched to by XServerDisplayActivity when a game starts; tapping returns to the
//    game. A session is never downgraded back to library mode (e.g. by opening the library
//    while the game is minimized) — it ends with the process, which exit() restarts.
//
// Previously the service was *stopped* for game sessions and replaced by a plain notification,
// which protects nothing: a minimized game was an ordinary background app and could be killed
// with its session. It also refused to run without the notification permission, although a
// foreground service doesn't need it (on Android 13+ it just runs with its notification hidden).
public class NotificationService extends Service {
    private static final String EXTRA_SESSION = "session";
    private static final String ACTION_EXIT = "com.winlator.cmod.action.EXIT";

    private static volatile boolean isRunning = false;
    private static volatile boolean sessionMode = false;
    private final AtomicBoolean exiting = new AtomicBoolean(false);

    public static boolean isRunning() {
        return isRunning;
    }

    public static void startLibrary(Context context) {
        start(context, false);
    }

    public static void startSession(Context context) {
        start(context, true);
    }

    private static void start(Context context, boolean session) {
        Intent intent = new Intent(context, NotificationService.class).putExtra(EXTRA_SESSION, session);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
            // Keep-alive is best effort; never let it take the caller down.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The Exit action is handled before anything else: it must work whatever the state of the
        // notification (hidden, permission revoked) and must not re-post it while quitting.
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            exitApplication();
            return START_NOT_STICKY;
        }
        if (exiting.get()) return START_NOT_STICKY;

        if (intent != null && intent.getBooleanExtra(EXTRA_SESSION, false)) sessionMode = true;

        ensureChannel();
        startForeground(MainActivity.NOTIFICATION_ID, buildNotification(sessionMode));
        isRunning = true;
        return START_NOT_STICKY;
    }

    private Notification buildNotification(boolean session) {
        Intent open;
        if (session) {
            // singleTask activity: brings the running game back instead of starting a new one.
            open = new Intent(this, XServerDisplayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open == null) open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, session ? 1 : 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent exitIntent = new Intent(this, NotificationService.class).setAction(ACTION_EXIT);
        PendingIntent exitPendingIntent = PendingIntent.getService(this, 2, exitIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, MainActivity.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(session ? R.drawable.ic_stat_ab_gear_0011 : R.drawable.winlator_mark)
                .setContentTitle("WinLite")
                .setContentText("WinLite is running, do not kill or swipe this notification")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_sidebar_power, "Exit", exitPendingIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .build();
    }

    // Exit from the notification. In a game session the activity's own exit() runs, so the
    // shutdown is the usual one (Wine stopped, save registry and the automatic backup done) and
    // then the app quits instead of coming back to the library. Without a session (library mode,
    // or the activity is already gone) the Wine processes, if any, are stopped here.
    private void exitApplication() {
        if (!exiting.compareAndSet(false, true)) return;
        if (sessionMode && XServerDisplayActivity.requestQuit()) return;

        // Not on the service's main thread: stopping Wine waits for its processes.
        new Thread(() -> {
            try {
                ProcessHelper.resumeAllWineProcesses();
                ProcessHelper.terminateAllWineProcesses();
                long deadline = android.os.SystemClock.uptimeMillis() + 1500;
                while (android.os.SystemClock.uptimeMillis() < deadline
                        && !ProcessHelper.listRunningWineProcesses().isEmpty()) {
                    Thread.sleep(50);
                }
                for (String pid : ProcessHelper.listRunningWineProcesses()) {
                    ProcessHelper.killProcess(Integer.parseInt(pid));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Quitting anyway.
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                isRunning = false;
                ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (manager != null) {
                    for (ActivityManager.AppTask task : manager.getAppTasks()) task.finishAndRemoveTask();
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "WinLiteNotificationExit").start();
    }

    // The service can be the first thing to post (e.g. a game launched straight from a home
    // screen shortcut, where MainActivity never ran), so make sure the channel exists.
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(MainActivity.NOTIFICATION_CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                MainActivity.NOTIFICATION_CHANNEL_ID, "WinLite", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("WinLite XServer Messages");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        isRunning = false;
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
