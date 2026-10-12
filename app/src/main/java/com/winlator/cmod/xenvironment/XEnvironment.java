package com.winlator.cmod.xenvironment;

import android.content.Context;

import com.winlator.cmod.alsaserver.ALSAClient;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final ImageFs imageFs;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();
    // Audio suspend/resume can block (pactl round-trip, AAudio state waits), so it runs off the
    // UI thread; a single thread keeps pause/resume ordered.
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XEnvironment-audio");
        thread.setDaemon(true);
        return thread;
    });

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return context;
    }

    public ImageFs getImageFs() {
        return imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
    }

    public void stopEnvironmentComponents() {
        audioExecutor.shutdownNow();
        ALSAClient.setOutputSuspended(false);
        for (EnvironmentComponent environmentComponent : this) environmentComponent.stop();
    }

    public void onPause() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.suspendProcess();
        setAudioSuspended(true);
    }

    public void onResume() {
        setAudioSuspended(false);
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.resumeProcess();
    }

    private void setAudioSuspended(boolean suspend) {
        PulseAudioComponent pulseAudio = getComponent(PulseAudioComponent.class);
        try {
            audioExecutor.execute(() -> {
                ALSAClient.setOutputSuspended(suspend);
                if (pulseAudio != null) {
                    if (suspend) pulseAudio.suspend();
                    else pulseAudio.resume();
                }
            });
        }
        catch (RejectedExecutionException ignored) {
            // Environment already stopped.
        }
    }
}
