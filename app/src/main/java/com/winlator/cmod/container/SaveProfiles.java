package com.winlator.cmod.container;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Save profiles: the user-profile folders games write their saves into live in shared storage
 * (Winlator/saves/&lt;Game&gt;/) instead of inside the container, and are linked into whichever
 * container the game is started on. Saves therefore survive switching or recreating containers
 * and can be reached from any Android file manager.
 *
 * Per shortcut ("saveProfileMode" extra), off unless the person turns it on:
 *   off    - saves stay in the container, as without this feature (also every launch without a
 *            shortcut: the container desktop, installers, tools);
 *   own    - the game's own folder Winlator/saves/&lt;Game&gt;/;
 *   shared - one folder, Winlator/saves/_Common/, for every shortcut set to it.
 *
 * Layout:
 *   Winlator/saves/.nomedia
 *   Winlator/saves/_Common/             shortcuts set to "Shared"
 *   Winlator/saves/&lt;Game&gt;/.profile.json  {"id": saveProfileId of the shortcut, "name", "exe"}
 *   Winlator/saves/&lt;Game&gt;/AppData, Documents, Saved Games, Public Documents
 *   Winlator/saves/&lt;Game&gt;/registry.reg     the game's HKCU keys (see SaveRegistry)
 *
 * A profile is found by the id in .profile.json, never by its folder name, so renaming a shortcut
 * (or the folder) keeps the link. The id is the shortcut's "saveProfileId" extra; cloning a
 * shortcut into another container copies the whole .desktop file, so the copy shares the profile.
 * A profile whose shortcut is gone (removed with its saves kept, or the app reinstalled) is taken
 * over by the next shortcut of the same game (same exe, else same name) instead of starting empty.
 *
 * Links are made in the container's prefix (drive_c/users/...). Wine reaches shared storage
 * directly (native launch, all-files access). Desktop stays in the container: Winlator keeps its
 * shortcuts there.
 *
 * The container's own folders are never moved out of it: before linking, they are set aside in
 * &lt;container&gt;/.save-profile-local/ (a rename on the same file system) and put back by the next
 * launch in "off" mode. So games left off, and the desktop, keep their saves in the container
 * even when games with a profile run in the same container in between.
 */
public final class SaveProfiles {
    private static final String TAG = "SaveProfiles";

    public static final String EXTRA_ID = "saveProfileId";
    public static final String EXTRA_MODE = "saveProfileMode";
    public static final String MODE_OFF = "off";
    public static final String MODE_OWN = "own";
    public static final String MODE_SHARED = "shared";

    public static final String COMMON_DIR = "_Common";
    public static final String META_FILE = ".profile.json";
    private static final String COMMON_ID = "common";
    private static final String NOMEDIA = ".nomedia";
    /** In the container root (outside .wine): the container's own folders while a profile is linked. */
    private static final String LOCAL_STASH_DIR = ".save-profile-local";

    /** Link point under drive_c/users (container side) -> folder inside the profile. */
    private static final String[][] LINKS = {
            {ImageFs.USER + "/AppData", "AppData"},
            {ImageFs.USER + "/Documents", "Documents"},
            {ImageFs.USER + "/Saved Games", "Saved Games"},
            {"Public/Documents", "Public Documents"},
    };

    private static final String TEMP_DIR = "AppData/Local/Temp";

    /** Steam emulator identity (user name, Steam id): copied from _Common into a new profile. */
    private static final String[] IDENTITY_SEEDS = {
            "AppData/Roaming/GSE Saves/settings",
            "AppData/Roaming/Goldberg SteamEmu Saves/settings",
    };

    private static final int MIRROR_MAX_DEPTH = 8;

    private SaveProfiles() {}

    // ---------- Settings ----------

    /** off (default, also for no shortcut), own or shared. */
    public static String getMode(Shortcut shortcut) {
        if (shortcut == null) return MODE_OFF;
        String mode = shortcut.getExtra(EXTRA_MODE, MODE_OFF);
        return MODE_OWN.equals(mode) || MODE_SHARED.equals(mode) ? mode : MODE_OFF;
    }

    /** True when the shortcut keeps its saves in Winlator/saves (own or shared). */
    public static boolean isEnabled(Shortcut shortcut) {
        return !MODE_OFF.equals(getMode(shortcut));
    }

    public static boolean isOwn(Shortcut shortcut) {
        return MODE_OWN.equals(getMode(shortcut));
    }

    public static File getRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Winlator/saves");
    }

    public static boolean isShared(Shortcut shortcut) {
        return MODE_SHARED.equals(getMode(shortcut));
    }

    public static void setMode(Shortcut shortcut, String mode) {
        shortcut.putExtra(EXTRA_MODE, MODE_OWN.equals(mode) || MODE_SHARED.equals(mode) ? mode : null);
        shortcut.saveData();
    }

    /** Gives the shortcut a profile id if it has none yet. Call before cloning it. */
    public static String ensureId(Shortcut shortcut) {
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            shortcut.putExtra(EXTRA_ID, id);
            shortcut.saveData();
        }
        return id;
    }

    // ---------- Lookup ----------

    /** The profile folder of the shortcut (or _Common), or null if off or never created. */
    public static synchronized File findProfileDir(Shortcut shortcut) {
        if (!isEnabled(shortcut)) return null;
        if (isShared(shortcut)) {
            File common = new File(getRoot(), COMMON_DIR);
            return common.isDirectory() ? common : null;
        }
        String id = shortcut.getExtra(EXTRA_ID, "");
        return id.isEmpty() ? null : findById(id);
    }

    /** The profile folder of the shortcut (or _Common), created when missing. Null if off or on failure. */
    public static synchronized File obtainProfileDir(Shortcut shortcut) {
        if (!isEnabled(shortcut)) return null;
        if (!ensureRoot()) return null;
        if (isShared(shortcut)) return obtainCommon();

        String id = ensureId(shortcut);
        File found = findById(id);
        if (found != null) return found;

        File orphan = findOrphan(shortcut);
        if (orphan != null) {
            writeMeta(orphan, id, shortcut.name, shortcut.path);
            Log.i(TAG, "Save profile " + orphan.getName() + " taken over by " + shortcut.name);
            return orphan;
        }

        File dir = new File(getRoot(), uniqueName(shortcut.name, null));
        if (!dir.mkdirs() && !dir.isDirectory()) {
            Log.e(TAG, "Could not create profile " + dir);
            return null;
        }
        writeMeta(dir, id, shortcut.name, shortcut.path);
        seedIdentity(dir);
        Log.i(TAG, "Created save profile " + dir.getName() + " for " + shortcut.name);
        return dir;
    }

    /** Shown path, e.g. "Winlator/saves/Hollow Knight/". */
    public static String displayPath(File profileDir) {
        return "Winlator/saves/" + profileDir.getName() + "/";
    }

    /** True if another shortcut (in any container) uses the same own profile. */
    public static boolean isUsedByOtherShortcut(Context context, Shortcut shortcut) {
        if (!isOwn(shortcut)) return true;
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) return false;
        try {
            for (Shortcut other : new ContainerManager(context).loadShortcuts()) {
                if (other == null || other.file.getPath().equals(shortcut.file.getPath())) continue;
                if (isOwn(other) && id.equals(other.getExtra(EXTRA_ID, ""))) return true;
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not check other shortcuts", e);
            return true;
        }
        return false;
    }

    // ---------- Launch ----------

    /**
     * Links the profile of the game being started into the container, or (mode off, no shortcut)
     * gives the container its own folders back. Runs before Wine starts, after the container
     * overlay is prepared. Never deletes save data: a folder that cannot be set aside is left in
     * place and not linked.
     */
    public static void apply(Context context, Container container, Shortcut shortcut) {
        // A game session that never finished (killed, crashed): save its registry keys first.
        SaveRegistry.finishSession(context);
        File users = new File(container.getRootDir(), ".wine/drive_c/users");
        File stash = new File(container.getRootDir(), LOCAL_STASH_DIR);
        if (!isEnabled(shortcut)) {
            restoreLocal(users, stash);
            return;
        }
        File profile = obtainProfileDir(shortcut);
        if (profile == null) {
            Log.e(TAG, "No save profile, container " + container.id + " keeps its own folders");
            restoreLocal(users, stash);
            return;
        }

        for (String[] link : LINKS) {
            File point = new File(users, link[0]);
            File target = new File(profile, link[1]);
            if (!target.isDirectory() && !target.mkdirs()) {
                Log.e(TAG, "Could not create " + target);
                continue;
            }
            // Keep the container's own folder skeleton (My Games, ...) in the profile: it is still in
            // the container's folder, or already in the stash when an earlier launch linked it.
            File own = Files.isSymbolicLink(point.toPath()) ? new File(stash, link[0]) : point;
            mirrorDirs(own, target, 0);
            linkPoint(point, target, new File(stash, link[0]));
        }
        File temp = new File(profile, TEMP_DIR);
        if (temp.isDirectory()) FileUtils.clear(temp);
        SaveRegistry.beforeLaunch(context, container, profile);
        Log.i(TAG, "Container " + container.id + " uses save profile " + profile.getName());
    }

    /**
     * Gives the container its own folders back (as a launch in "off" mode would), so code in the
     * app can read the saves of a game without a profile. Only while Wine is not running.
     */
    public static synchronized void prepareLocal(Container container) {
        restoreLocal(new File(container.getRootDir(), ".wine/drive_c/users"),
                new File(container.getRootDir(), LOCAL_STASH_DIR));
    }

    // ---------- Shortcut lifecycle ----------

    /** Renames the profile folder after the shortcut was renamed. Safe to call off the main thread. */
    public static synchronized void onShortcutRenamed(Shortcut shortcut, String newName) {
        if (!isOwn(shortcut)) return;
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) return;
        File dir = findById(id);
        if (dir == null) return;
        String wanted = uniqueName(newName, dir);
        if (!wanted.equals(dir.getName())) {
            File renamed = new File(getRoot(), wanted);
            if (dir.renameTo(renamed)) dir = renamed;
            else Log.w(TAG, "Could not rename profile " + dir + " to " + wanted);
        }
        writeMeta(dir, id, newName);
    }

    /** Deletes the shortcut's own profile folder. Call off the main thread. */
    public static synchronized boolean deleteProfile(Shortcut shortcut) {
        if (!isOwn(shortcut)) return false;
        File dir = findProfileDir(shortcut);
        return dir == null || FileUtils.delete(dir);
    }

    // ---------- Internals ----------

    private static boolean ensureRoot() {
        File root = getRoot();
        if (!root.isDirectory() && !root.mkdirs()) {
            Log.e(TAG, "Could not create " + root);
            return false;
        }
        File nomedia = new File(root, NOMEDIA);
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            }
            catch (IOException ignored) {}
        }
        return true;
    }

    private static File obtainCommon() {
        File common = new File(getRoot(), COMMON_DIR);
        if (!common.isDirectory() && !common.mkdirs()) return null;
        if (!new File(common, META_FILE).isFile()) writeMeta(common, COMMON_ID, COMMON_DIR);
        return common;
    }

    /**
     * A profile left behind by a shortcut that no longer exists (removed with "keep saves", or the
     * app was reinstalled: shared storage outlives the app's own data) that belongs to this game:
     * same exe path (strongest), else same name. Profiles still owned by a live shortcut are never
     * taken. If the shortcuts cannot be listed, nothing is taken.
     */
    private static File findOrphan(Shortcut shortcut) {
        File[] dirs = getRoot().listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        ContainerManager manager = shortcut.container != null ? shortcut.container.getManager() : null;
        if (manager == null) return null;

        Set<String> liveIds = new HashSet<>();
        try {
            for (Shortcut other : manager.loadShortcuts()) {
                if (other == null || !isOwn(other)) continue;
                if (shortcut.file != null && other.file.getPath().equals(shortcut.file.getPath())) continue;
                String otherId = other.getExtra(EXTRA_ID, "");
                if (!otherId.isEmpty()) liveIds.add(otherId);
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not list shortcuts, not looking for a left-behind profile", e);
            return null;
        }

        String exe = normalizeExe(shortcut.path);
        String name = shortcut.name != null ? shortcut.name : "";
        String folderName = sanitize(name);
        File best = null;
        int bestScore = 0;
        long bestTime = 0L;
        for (File dir : dirs) {
            if (COMMON_DIR.equals(dir.getName())) continue;
            JSONObject meta = readMeta(dir);
            if (meta == null) continue;
            String metaId = meta.optString("id", "");
            if (metaId.isEmpty() || liveIds.contains(metaId)) continue;

            int score = 0;
            String metaExe = normalizeExe(meta.optString("exe", ""));
            if (!exe.isEmpty() && exe.equals(metaExe)) score = 2;
            else if (name.equalsIgnoreCase(meta.optString("name", "")) || folderName.equalsIgnoreCase(dir.getName())) score = 1;
            if (score == 0) continue;

            long time = meta.optLong("updatedAt", dir.lastModified());
            if (score > bestScore || (score == bestScore && time > bestTime)) {
                best = dir;
                bestScore = score;
                bestTime = time;
            }
        }
        return best;
    }

    private static String normalizeExe(String path) {
        if (path == null) return "";
        return path.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    private static File findById(String id) {
        File[] dirs = getRoot().listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (COMMON_DIR.equals(dir.getName())) continue;
            if (id.equals(readMetaId(dir))) return dir;
        }
        return null;
    }

    private static String readMetaId(File dir) {
        JSONObject meta = readMeta(dir);
        return meta != null ? meta.optString("id", null) : null;
    }

    private static JSONObject readMeta(File dir) {
        File meta = new File(dir, META_FILE);
        if (!meta.isFile()) return null;
        try {
            return new JSONObject(FileUtils.readString(meta));
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void writeMeta(File dir, String id, String name) {
        writeMeta(dir, id, name, null);
    }

    /** exe: the shortcut's Windows path, kept so a left-behind profile can be matched to its game. */
    private static void writeMeta(File dir, String id, String name, String exe) {
        try {
            JSONObject json = new JSONObject();
            File meta = new File(dir, META_FILE);
            if (meta.isFile()) {
                try {
                    json = new JSONObject(FileUtils.readString(meta));
                }
                catch (Exception ignored) {}
            }
            if (!json.has("createdAt")) json.put("createdAt", System.currentTimeMillis());
            json.put("id", id);
            json.put("name", name);
            if (exe != null && !exe.isEmpty()) json.put("exe", exe);
            json.put("updatedAt", System.currentTimeMillis());
            FileUtils.writeString(meta, json.toString(2));
        }
        catch (Exception e) {
            Log.w(TAG, "Could not write " + META_FILE + " in " + dir, e);
        }
    }

    /** A free folder name for a game; "self" is the folder being renamed (its own name stays free). */
    private static String uniqueName(String name, File self) {
        String base = sanitize(name);
        if (base.equalsIgnoreCase(COMMON_DIR)) base = base + " (game)";
        String candidate = base;
        int n = 2;
        // Shared storage is case-insensitive: compare names the same way.
        while (true) {
            File f = new File(getRoot(), candidate);
            boolean taken = f.exists() && (self == null || !candidate.equalsIgnoreCase(self.getName()));
            if (!taken) return candidate;
            candidate = base + " (" + n++ + ")";
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "Game";
        String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1).trim();
        while (safe.startsWith(".")) safe = safe.substring(1).trim();
        return safe.isEmpty() ? "Game" : safe;
    }

    private static void seedIdentity(File profile) {
        File common = new File(getRoot(), COMMON_DIR);
        for (String rel : IDENTITY_SEEDS) {
            File src = new File(common, rel);
            if (src.isDirectory()) copyMerge(src, new File(profile, rel));
        }
    }

    /**
     * Makes the container's folder at "point" a link to "target". A real folder found there is
     * the container's own: it is set aside at "stashed" (rename, same file system) for restoreLocal.
     */
    private static void linkPoint(File point, File target, File stashed) {
        Path p = point.toPath();
        Path t = Paths.get(target.getAbsolutePath());
        try {
            if (Files.isSymbolicLink(p)) {
                if (Files.readSymbolicLink(p).equals(t)) return;
                Files.delete(p);
            }
            else if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (!setAside(point, stashed)) {
                        Log.e(TAG, "Could not set " + point + " aside, not linking it");
                        return;
                    }
                }
                else {
                    Files.delete(p);
                }
            }
            File parent = point.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            Files.createSymbolicLink(p, t);
        }
        catch (IOException e) {
            Log.e(TAG, "Could not link " + point + " -> " + target, e);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) point.mkdirs();
        }
    }

    /** Moves the container's folder to the stash (merging, newer wins, if one is there already). */
    private static boolean setAside(File point, File stashed) {
        if (!stashed.exists()) {
            File parent = stashed.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try {
                Files.move(point.toPath(), stashed.toPath());
                return true;
            }
            catch (IOException e) {
                Log.w(TAG, "Rename " + point + " -> " + stashed + " failed, copying", e);
            }
        }
        return copyMerge(point, stashed) && FileUtils.delete(point);
    }

    /** Mode off: the links go away and the container gets its own folders back from the stash. */
    private static void restoreLocal(File users, File stash) {
        for (String[] link : LINKS) {
            File point = new File(users, link[0]);
            File stashed = new File(stash, link[0]);
            Path p = point.toPath();
            if (Files.isSymbolicLink(p)) {
                try {
                    Files.delete(p);
                }
                catch (IOException e) {
                    Log.w(TAG, "Could not remove link " + point, e);
                    continue;
                }
            }
            if (!Files.exists(stashed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!point.isDirectory()) point.mkdirs();
                continue;
            }
            boolean restored = false;
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(stashed.toPath(), p);
                    restored = true;
                }
                catch (IOException e) {
                    Log.w(TAG, "Rename " + stashed + " -> " + point + " failed, copying", e);
                }
            }
            if (!restored && copyMerge(stashed, point)) FileUtils.delete(stashed);
        }
    }

    /**
     * Copies src into dst, keeping whichever copy of a file is newer. Links inside src are not
     * followed. Returns false if any file could not be copied.
     */
    private static boolean copyMerge(File src, File dst) {
        if (!dst.isDirectory() && !dst.mkdirs()) return false;
        File[] children = src.listFiles();
        if (children == null) return src.isDirectory();
        boolean ok = true;
        for (File child : children) {
            Path cp = child.toPath();
            if (Files.isSymbolicLink(cp)) continue;
            File out = new File(dst, child.getName());
            if (child.isDirectory()) {
                ok &= copyMerge(child, out);
            }
            else if (child.isFile()) {
                if (out.isFile() && out.lastModified() >= child.lastModified()) continue;
                try {
                    Files.copy(cp, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    out.setLastModified(child.lastModified());
                }
                catch (IOException e) {
                    Log.w(TAG, "Could not copy " + child + " -> " + out, e);
                    ok = false;
                }
            }
        }
        return ok;
    }

    /** Recreates the folder skeleton of the container's own folder (folders only) inside the profile. */
    private static void mirrorDirs(File src, File dst, int depth) {
        if (depth > MIRROR_MAX_DEPTH) return;
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath()) || !child.isDirectory()) continue;
            File out = new File(dst, child.getName());
            if (!out.isDirectory() && !out.mkdirs()) continue;
            mirrorDirs(child, out, depth + 1);
        }
    }
}
