package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class LosslessDll {
    private static final String TAG = "LosslessDll";
    // Preserve imported DLLs from existing installations.
    private static final String DLL_RELATIVE_DIR = ".local/share/lsfg-vk";
    private static final String LOSSLESS_DLL_NAME = "Lossless.dll";

    // App-private LSFG storage: the imported Lossless.dll and the shader caches built from it.
    private static final String LSFG_DIR = "lsfg";
    // Older builds kept the DLL and the caches in two separate folders.
    private static final String LEGACY_DLL_DIR = "lsfg-vk";
    private static final String LEGACY_CACHE_DIR = "lsfg-native";
    private static volatile boolean legacyMigrated = false;

    private LosslessDll() {}

    /** The one folder LSFG keeps its files in (Lossless.dll and the shader caches). */
    public static File lsfgDir(Context context) {
        File dir = new File(context.getFilesDir(), LSFG_DIR);
        if (!legacyMigrated) migrateLegacy(context.getFilesDir(), dir);
        return dir;
    }

    /**
     * Moves what the two old folders held into the new one, once per process: the imported DLL
     * must survive an update (the user would have to import it again), the caches keep their
     * names and stay valid. Whatever cannot be moved stays where it is and is retried next time.
     */
    private static synchronized void migrateLegacy(File filesDir, File dir) {
        if (legacyMigrated) return;
        boolean done = true;
        for (String legacyName : new String[] {LEGACY_DLL_DIR, LEGACY_CACHE_DIR}) {
            File legacy = new File(filesDir, legacyName);
            File[] files = legacy.listFiles();
            if (files == null) continue;   // not there (anymore)
            if (!dir.isDirectory() && !dir.mkdirs()) { done = false; continue; }
            for (File f : files) {
                File dst = new File(dir, f.getName());
                if (dst.exists()) {
                    f.delete();   // the new folder already has it; that one wins
                } else if (!f.renameTo(dst)) {
                    Log.w(TAG, "could not move " + f + " to " + dst);
                    done = false;
                }
            }
            legacy.delete();   // only succeeds once it is empty
        }
        legacyMigrated = done;
    }

    public static boolean isGlobalDllAvailable(Context context) {
        File dllFile = globalDllFile(context);
        return dllFile != null && dllFile.isFile() && dllFile.length() > 0;
    }

    public static File globalDllFile(Context context) {
        if (context == null) return null;
        return new File(lsfgDir(context), LOSSLESS_DLL_NAME);
    }

    public static boolean importGlobalLosslessDll(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        File dst = globalDllFile(context);
        if (dst == null) return false;
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        return copyUriTo(context, uri, dst);
    }

    public static File containerDllFile(Container container) {
        if (container == null || container.getRootDir() == null) return null;
        return new File(container.getRootDir(), DLL_RELATIVE_DIR + "/" + LOSSLESS_DLL_NAME);
    }

    public static String containerDllPath(Container container) {
        File dllFile = containerDllFile(container);
        return dllFile != null && dllFile.isFile() ? dllFile.getAbsolutePath() : null;
    }

    public static File containerDllFile(Shortcut shortcut) {
        return shortcut == null ? null : containerDllFile(shortcut.container);
    }

    public static String containerDllPath(Shortcut shortcut) {
        File dllFile = containerDllFile(shortcut);
        return dllFile != null && dllFile.isFile() ? dllFile.getAbsolutePath() : null;
    }

    private static boolean copyUriTo(Context context, Uri uri, File dst) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[0x20000];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            FileUtils.chmod(dst, 0644);
            Log.i(TAG, "Imported Lossless.dll to " + dst.getAbsolutePath() + " size=" + dst.length());
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import Lossless.dll", t);
            return false;
        }
    }
}
