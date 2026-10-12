package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class PulseAudioComponent extends EnvironmentComponent {
    // Source names inside the APK's lib/ dir; copied next to the daemon under the plain names it expects.
    private static final String[] LIBRARY_NAMES = {
            "libltdl.so",
            "libpulseaudio.so",
            "libpulse.so",
            "libpulsecommon-13.0.so",
            "libpulsecore-13.0.so",
            "libsndfile.so"
    };
    private static final String[] SOURCE_NAMES = {
            "libgn_ltdl.so",
            "libgn_pulseaudio.so",
            "libgn_pulse.so",
            "libgn_pulsecommon-13.0.so",
            "libgn_pulsecore-13.0.so",
            "libgn_sndfile.so"
    };
    private static final String RUNTIME_NAME = "pulseaudio-gn";
    private static final String ASSET_NAME = "pulseaudio-gamenative-20260612.tzst";
    private static final String MARKER_NAME = ".gamenative-20260612";

    private static final String TAG = "PulseAudioComponent";
    private static final String DAEMON_NAME = "libpulseaudio.so";
    private static final long PACTL_TIMEOUT_MS = 2000;

    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();
    private boolean suspended = false;

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            // The static pid is lost when the app process dies, so a daemon from an earlier
            // session can outlive it and keep its AAudio stream open. Clear those out first.
            killStaleDaemons();
            pid = execPulseAudio();
            suspended = false;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
            suspended = false;
        }
    }

    // Activity went to the background: the Wine processes are SIGSTOPped, but the daemon is not
    // one of them and would keep its AAudio stream running. Suspending the sink makes
    // module-aaudio-sink close the device stream; resume reopens it. Runs pactl, so callers keep
    // this off the UI thread.
    public void suspend() {
        synchronized (lock) {
            if (suspended || !isDaemonAlive()) return;
            suspended = true;
            execPactl("suspend-sink", "@DEFAULT_SINK@", "1");
        }
    }

    public void resume() {
        synchronized (lock) {
            if (!suspended) return;
            suspended = false;
            if (isDaemonAlive()) {
                execPactl("suspend-sink", "@DEFAULT_SINK@", "0");
            }
            else {
                // Killed while in the background; default.pa recreates the sink.
                Log.w(TAG, "PulseAudio daemon died in background, restarting");
                start();
            }
        }
    }

    private static boolean isDaemonAlive() {
        return pid != -1 && new File("/proc/" + pid).exists();
    }

    private File getWorkingDir() {
        return new File(environment.getContext().getFilesDir(), RUNTIME_NAME);
    }

    private void killStaleDaemons() {
        String daemonPath = new File(getWorkingDir(), DAEMON_NAME).getAbsolutePath();
        String[] pids = new File("/proc").list((dir, name) -> name.matches("\\d+"));
        if (pids == null) return;

        int self = Process.myPid();
        boolean killed = false;
        for (String pidStr : pids) {
            int candidate = Integer.parseInt(pidStr);
            if (candidate == self) continue;
            String cmdline = readCmdline(pidStr);
            if (cmdline.startsWith(daemonPath)) {
                Log.w(TAG, "Killing stale PulseAudio daemon pid=" + candidate);
                Process.killProcess(candidate);
                killed = true;
            }
        }
        if (killed) {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String readCmdline(String pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] buffer = new byte[512];
            int length = in.read(buffer);
            if (length <= 0) return "";
            return new String(buffer, 0, length, StandardCharsets.UTF_8).replace('\0', ' ');
        }
        catch (IOException e) {
            return "";
        }
    }

    private void execPactl(String... args) {
        File workingDir = getWorkingDir();
        File pactl = new File(workingDir, "pactl");
        if (!pactl.isFile()) pactl = new File(workingDir, "bin/pactl");
        if (!pactl.isFile()) {
            Log.w(TAG, "pactl not found in " + workingDir);
            return;
        }
        if (!pactl.canExecute()) FileUtils.chmod(pactl, 0771);

        String[] command = new String[args.length + 1];
        command[0] = pactl.getAbsolutePath();
        System.arraycopy(args, 0, command, 1, args.length);

        String[] envp = {
                "LD_LIBRARY_PATH=" + getSystemLibPath() + ":" + new File(workingDir, "modules") + ":" + workingDir,
                "HOME=" + workingDir,
                "TMPDIR=" + environment.getTmpDir(),
                "PULSE_SERVER=unix:" + socketConfig.path
        };

        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(command, envp, workingDir);
            if (!process.waitFor(PACTL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "pactl timed out: " + String.join(" ", args));
            }
            else if (process.exitValue() != 0) {
                Log.w(TAG, "pactl " + String.join(" ", args) + " exited with " + process.exitValue());
            }
        }
        catch (IOException e) {
            Log.w(TAG, "pactl failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finally {
            if (process != null) process.destroy();
        }
    }

    private static String getSystemLibPath() {
        return AppUtils.getArchName().equals("arm64") ? "/system/lib64" : "/system/lib";
    }

    // The bundled libs only change with the APK, so copy them once per install/update instead of
    // on every container start.
    private void ensureLibrariesCopied(Context context, File workingDir) {
        File apk = new File(context.getApplicationInfo().sourceDir);
        File marker = new File(workingDir, ".libs-" + apk.lastModified());
        boolean present = marker.isFile();
        for (int i = 0; present && i < LIBRARY_NAMES.length; i++) {
            present = new File(workingDir, LIBRARY_NAMES[i]).isFile();
        }
        if (present) return;

        File[] oldMarkers = workingDir.listFiles((dir, name) -> name.startsWith(".libs-"));
        if (oldMarkers != null) for (File oldMarker : oldMarkers) oldMarker.delete();
        copyFromLibraryDir(workingDir);
        FileUtils.writeString(marker, apk.getName());
    }

    private void copyFromLibraryDir(File dst) {
        for (int i = 0; i < LIBRARY_NAMES.length; i++) {
            String path = "lib/arm64-v8a/" + SOURCE_NAMES[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            URL resource = loader != null ? loader.getResource(path) : null;
            Path destination = Paths.get(dst.getAbsolutePath(), LIBRARY_NAMES[i]);
            try (InputStream input = resource != null ? resource.openStream() : null) {
                if (input == null) {
                    throw new IllegalStateException("Missing PulseAudio library: " + SOURCE_NAMES[i]);
                }
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                FileUtils.chmod(destination.toFile(), 0771);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();

        // The old non-GN runtime is gone; drop its extracted copy so it stops taking up space.
        File legacyDir = new File(context.getFilesDir(), "pulseaudio");
        if (legacyDir.exists()) FileUtils.delete(legacyDir);

        File workingDir = getWorkingDir();
        File versionMarker = new File(workingDir, MARKER_NAME);
        if (!versionMarker.isFile()) {
            FileUtils.delete(workingDir);
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
            boolean extracted = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    ASSET_NAME,
                    workingDir);
            if (!extracted) {
                throw new IllegalStateException("Unable to extract " + ASSET_NAME);
            }
            FileUtils.writeString(versionMarker, ASSET_NAME);
        }

        File configDir = new File(workingDir, ".config");
        if (configDir.exists()) FileUtils.delete(configDir);

        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\"" + socketConfig.path + "\"",
                "load-module module-aaudio-sink volume=1.0 performance_mode=1 low_latency=true"
        ));

        File modulesDir = new File(workingDir, "modules");
        String systemLibPath = getSystemLibPath();

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH=" + systemLibPath + ":" + modulesDir + ":" + workingDir.getAbsolutePath());
        envVars.add("HOME=" + workingDir);
        envVars.add("TMPDIR=" + environment.getTmpDir());

        ensureLibrariesCopied(context, workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}
