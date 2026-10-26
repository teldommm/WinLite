package com.winlator.cmod.container;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry part of save profiles: games that keep progress or settings in HKCU (Unity's
 * PlayerPrefs live in HKCU\Software\&lt;Company&gt;\&lt;Product&gt;, older games too) would lose them when
 * the container changes, because user.reg belongs to the container.
 *
 * Each Wine key section in user.reg carries the time it was last modified ("[Key] 1746463196",
 * Unix seconds). So nothing has to be guessed:
 *  - before launch, the sections kept in &lt;profile&gt;/registry.reg are written into the container's
 *    user.reg (replacing the same keys), and a session marker records the launch time;
 *  - after the game (Wine stopped: wineserver flushes the registry on SIGTERM), every
 *    HKCU\Software section modified since the launch, except Wine/Windows' own keys, is merged
 *    into registry.reg;
 *  - if the session never finished (process killed, crash), the marker is still there and the
 *    export runs before the next launch.
 *
 * registry.reg holds raw sections in Wine's own registry format (not a regedit file); files are
 * handled as ISO-8859-1 so bytes go through unchanged (Wine escapes non-ASCII itself).
 * Only Wine is stopped while these run, so user.reg is never edited under a running wineserver.
 */
public final class SaveRegistry {
    private static final String TAG = "SaveRegistry";

    public static final String FILE = "registry.reg";
    private static final String SESSION_FILE = "save_registry_session.json";
    private static final String USER_REG = "user.reg";
    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;
    /** Seconds of tolerance between the app's clock read and Wine's key timestamps. */
    private static final long SLACK_SECONDS = 2;

    /** HKCU\Software keys that belong to Wine, Windows or runtimes, never to a game (lowercase). */
    private static final String[] EXCLUDED = {
            "software\\wine",
            "software\\microsoft",
            "software\\classes",
            "software\\policies",
            "software\\valve",
            "software\\khronos",
            "software\\winlator",
    };

    private static final Pattern HEADER = Pattern.compile("^\\[(.+)\\]\\s+(\\d+)\\s*$");

    private SaveRegistry() {}

    private static final class Section {
        final String key;
        final long time;
        final List<String> lines = new ArrayList<>();

        Section(String key, long time) {
            this.key = key;
            this.time = time;
        }
    }

    private static final class RegFile {
        final List<String> preamble = new ArrayList<>();
        /** By normalized (lowercase, single backslash) key, in file order. */
        final LinkedHashMap<String, Section> sections = new LinkedHashMap<>();
    }

    // ---------- Session ----------

    /**
     * Before launch, after the profile is linked: puts the game's saved keys into the container's
     * user.reg and opens a session. Call {@link #finishSession} first for any earlier session.
     */
    static void beforeLaunch(Context context, Container container, File profile) {
        File userReg = new File(new File(container.getRootDir(), ".wine"), USER_REG);
        if (!userReg.isFile()) {
            Log.w(TAG, "No user.reg in container " + container.id + ", registry not synced");
            return;
        }
        File saved = new File(profile, FILE);
        if (saved.isFile()) {
            int count = importInto(saved, userReg);
            if (count > 0) Log.i(TAG, "Imported " + count + " registry keys from " + profile.getName());
        }
        writeSession(context, profile, userReg, System.currentTimeMillis() / 1000L);
    }

    /**
     * Exports the keys the game changed during the open session (if any) into its profile and
     * closes the session. Call only while Wine is stopped: after the game exits, and before the
     * next launch (for a session that never finished).
     */
    public static synchronized void finishSession(Context context) {
        File marker = sessionFile(context);
        if (marker == null || !marker.isFile()) return;
        try {
            JSONObject json = new JSONObject(FileUtils.readString(marker));
            File profile = new File(json.getString("profile"));
            File userReg = new File(json.getString("userReg"));
            long startedAt = json.getLong("startedAt");
            if (profile.isDirectory() && userReg.isFile()) {
                int count = export(userReg, new File(profile, FILE), startedAt);
                if (count > 0) Log.i(TAG, "Saved " + count + " registry keys to " + profile.getName());
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not finish registry session", e);
        }
        finally {
            marker.delete();
        }
    }

    // ---------- Export / import ----------

    /** Merges sections of userReg modified since startedAt into saved. Returns the count. */
    static int export(File userReg, File saved, long startedAt) throws IOException {
        RegFile current = parse(userReg);
        List<Section> changed = new ArrayList<>();
        for (Map.Entry<String, Section> entry : current.sections.entrySet()) {
            Section section = entry.getValue();
            if (section.time + SLACK_SECONDS >= startedAt && isGameKey(entry.getKey())) changed.add(section);
        }
        if (changed.isEmpty()) return 0;

        RegFile out = saved.isFile() ? parse(saved) : new RegFile();
        if (out.preamble.isEmpty()) {
            out.preamble.add("WINE REGISTRY Version 2");
            out.preamble.add(";; HKCU keys of this game, kept by Winlator save profiles.");
            out.preamble.add(";; Written into the container's user.reg before each launch.");
        }
        for (Section section : changed) out.sections.put(normalize(section.key), section);
        write(out, saved);
        return changed.size();
    }

    /** Writes every section of saved into userReg, replacing the same keys. Returns the count. */
    static int importInto(File saved, File userReg) {
        try {
            RegFile source = parse(saved);
            if (source.sections.isEmpty()) return 0;
            RegFile target = parse(userReg);
            int count = 0;
            for (Map.Entry<String, Section> entry : source.sections.entrySet()) {
                if (!isGameKey(entry.getKey())) continue;
                // put() on an existing key keeps its position in a LinkedHashMap.
                target.sections.put(entry.getKey(), entry.getValue());
                count++;
            }
            if (count > 0) write(target, userReg);
            return count;
        }
        catch (IOException e) {
            Log.w(TAG, "Could not import " + saved + " into " + userReg, e);
            return 0;
        }
    }

    static boolean isGameKey(String normalized) {
        String key = normalized;
        if (!key.startsWith("software\\")) return false;
        if (key.startsWith("software\\wow6432node\\")) key = "software\\" + key.substring("software\\wow6432node\\".length());
        if (key.length() <= "software\\".length()) return false;
        for (String excluded : EXCLUDED) {
            if (key.equals(excluded) || key.startsWith(excluded + "\\")) return false;
        }
        return true;
    }

    // ---------- File format ----------

    private static String normalize(String key) {
        return key.replace("\\\\", "\\").toLowerCase(Locale.ROOT);
    }

    private static RegFile parse(File file) throws IOException {
        RegFile reg = new RegFile();
        Section section = null;
        for (String line : Files.readAllLines(file.toPath(), CHARSET)) {
            if (line.startsWith("[")) {
                Matcher m = HEADER.matcher(line);
                if (m.matches()) {
                    section = new Section(m.group(1), parseTime(m.group(2)));
                    section.lines.add(line);
                    reg.sections.put(normalize(section.key), section);
                    continue;
                }
            }
            if (section != null) section.lines.add(line);
            else reg.preamble.add(line);
        }
        return reg;
    }

    private static long parseTime(String value) {
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Same layout as Wine: preamble, then sections separated by one blank line. Atomic replace. */
    private static void write(RegFile reg, File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<String> preamble = trimTrailingBlank(reg.preamble);
        for (String line : preamble) sb.append(line).append('\n');
        for (Section section : reg.sections.values()) {
            sb.append('\n');
            for (String line : trimTrailingBlank(section.lines)) sb.append(line).append('\n');
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(tmp.toPath(), sb.toString().getBytes(CHARSET));
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> trimTrailingBlank(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).trim().isEmpty()) end--;
        return lines.subList(0, end);
    }

    private static File sessionFile(Context context) {
        if (context == null || context.getFilesDir() == null) return null;
        return new File(context.getFilesDir(), SESSION_FILE);
    }

    private static void writeSession(Context context, File profile, File userReg, long startedAt) {
        File marker = sessionFile(context);
        if (marker == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("profile", profile.getAbsolutePath());
            json.put("userReg", userReg.getAbsolutePath());
            json.put("startedAt", startedAt);
            FileUtils.writeString(marker, json.toString());
        }
        catch (Exception e) {
            Log.w(TAG, "Could not write registry session marker", e);
        }
    }
}
