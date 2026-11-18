package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** File helpers shared by the component engine. */
public abstract class OfflineTools {
    private OfflineTools() {
    }

    /** Every regular file under [root] (symbolic links left alone), sorted by path. */
    public static List<File> walkFiles(File root) {
        List<File> out = new ArrayList<>();
        Deque<File> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            File dir = todo.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File c : children) {
                if (Files.isSymbolicLink(c.toPath())) continue;
                if (c.isDirectory()) todo.push(c);
                else if (Files.isRegularFile(c.toPath(), LinkOption.NOFOLLOW_LINKS)) out.add(c);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Deletes [root] and everything under it, never following a symbolic link out of it. */
    public static void deleteTree(File root) {
        if (root == null) return;
        try {
            if (Files.isSymbolicLink(root.toPath())) {
                Files.deleteIfExists(root.toPath());
                return;
            }
        } catch (IOException ignored) {
        }
        File[] children = root.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        root.delete();
    }
}
