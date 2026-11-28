package com.winlator.cmod.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class OpenGLDriverLoader {
    private OpenGLDriverLoader() {}

    public static synchronized File resolve(Context context, boolean freedreno) throws IOException {
        String driver = freedreno ? "freedreno" : "zink";
        // Revise the directory name when its bundled archive changes.
        String revision = freedreno ? "dfa64af-r6" : "e9729774";
        String gl = freedreno ? "libGL.so.1" : "libGL.so.1.5.0";
        String gallium = freedreno ? "libgallium-26.3.0-devel.so" : "libgallium-24.3.0.so";
        File root = new File(context.getFilesDir(), "opengl");
        Files.createDirectories(root.toPath());
        File destination = new File(root, driver + "-" + revision);
        if (isComplete(destination, gallium, freedreno)) return destination;

        File stage = Files.createTempDirectory(root.toPath(), ".extract-").toFile();
        try {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                    "graphics_driver/opengl_" + driver + ".tzst", stage)) {
                throw new IOException("Cannot extract " + driver);
            }
            File libs = new File(stage, "usr/lib");
            for (String name : new String[]{"libGL.so", "libGL.so.1", "libGL.so.1.5.0"}) {
                if (!name.equals(gl)) link(libs, name, gl);
            }
            if (!freedreno) {
                link(libs, "libglapi.so", "libglapi.so.0.0.0");
                link(libs, "libglapi.so.0", "libglapi.so.0.0.0");
            }
            if (!isComplete(libs, gallium, freedreno)) {
                throw new IOException("Incomplete " + driver + " libraries");
            }
            if (destination.exists() && !FileUtils.delete(destination)) {
                throw new IOException("Cannot replace incomplete " + driver + " installation");
            }
            Files.move(libs.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            FileUtils.delete(stage);
        }
    }

    private static void link(File directory, String name, String target) throws IOException {
        Files.createSymbolicLink(new File(directory, name).toPath(), new File(target).toPath());
    }

    private static boolean isComplete(File directory, String gallium, boolean freedreno) {
        return hasLibrary(directory, gallium)
                && hasLibrary(directory, "libGL.so")
                && hasLibrary(directory, "libGL.so.1")
                && hasLibrary(directory, "libGL.so.1.5.0")
                && (freedreno || (hasLibrary(directory, "libglapi.so")
                    && hasLibrary(directory, "libglapi.so.0")));
    }

    private static boolean hasLibrary(File directory, String name) {
        File file = new File(directory, name);
        return file.isFile() && file.length() > 0;
    }
}
