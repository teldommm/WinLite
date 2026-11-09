package com.winlator.cmod.steamgrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.RemoteSources;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Everything about game artwork (cover, banner, icon) that is not drawing:
 *  - where the files live (Winlator/covers, banners, icons),
 *  - finding the game on SteamGridDB (candidate queries + name matching, or an id the user gave),
 *  - picking the best image size and saving it,
 *  - remembering misses so a game without artwork is not searched again on every scroll.
 *
 * All network work runs on this class's own small executor and reports back through a Listener
 * on that worker thread (callers hop to the UI thread themselves).
 */
public final class ArtworkRepository {
    private static final String TAG = "ArtworkRepository";

    public static final String KIND_COVER = "cover";
    public static final String KIND_BANNER = "banner";
    public static final String KIND_ICON = "icon";

    /** Shortcut extra: absent = automatic, "none" = never download, otherwise a stored source. */
    public static final String EXTRA_SOURCE = "artworkSource";
    public static final String SOURCE_NONE = "none";

    /** Settings > COVER ART: download covers/banners automatically (default on). */
    public static final String PREF_AUTO_DOWNLOAD = "auto_download_artwork";

    /** Settings > COVER ART: slow zoom/drift on big artwork (default on). */
    public static final String PREF_ANIMATED_ARTWORK = "animated_artwork";

    // Vertical (portrait) sizes SteamGridDB knows, best first: 660x930 is 0.71, the closest
    // to the library tile (0.72). Horizontal: the larger one first so the backdrop stays sharp.
    private static final String COVER_DIMENSIONS = "660x930,600x900,342x482";
    private static final String BANNER_DIMENSIONS = "920x430,460x215";
    private static final List<String> COVER_RANK = java.util.Arrays.asList("660x930", "600x900", "342x482");
    private static final List<String> BANNER_RANK = java.util.Arrays.asList("920x430", "460x215");

    private static final int ICON_MAX_LONG_SIDE = 512;
    private static final int COVER_MAX_LONG_SIDE = 1400;
    private static final int BANNER_MAX_LONG_SIDE = 2000;
    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;

    private static final long MISS_TTL_MS = TimeUnit.DAYS.toMillis(7);
    private static final long TRANSIENT_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long BLOCK_MS = TimeUnit.MINUTES.toMillis(10);
    private static final double AUTO_MATCH_THRESHOLD = 0.6;
    private static final double EARLY_STOP_SCORE = 0.95;
    private static final int MAX_QUERIES = 5;

    private static final String CACHE_PREFS = "artwork_cache";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "artwork-worker");
        thread.setDaemon(true);
        return thread;
    });

    // Local, CPU/disk-only work (icon extraction, placeholder covers): its own thread so it never
    // waits behind, or delays, a network job.
    private static final ExecutorService LOCAL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "artwork-local");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile SteamGridDBApi api;
    // Base URL the cached Retrofit instance was built for; rebuilt when the address in Settings changes.
    private static volatile String apiBase;
    private static volatile OkHttpClient http;

    // Session state: repeated failures must not turn into a request per scroll.
    private static volatile long blockedUntil;
    private static volatile String blockedReason = "";
    private static final Map<String, Long> transientFailures = new ConcurrentHashMap<>();

    private ArtworkRepository() {}

    // ------------------------------------------------------------------ public types

    public static final class Job {
        public Context context;
        public String name;          // shortcut display name
        public String windowsPath;   // shortcut exe path, used for folder-name candidates
        public String baseName;      // file base name, key of every artwork file
        public String source;        // null = automatic; "sgdb:ID", "steam:ID", "query:TEXT"
        public File coverFile;       // null = do not fetch a cover
        public File bannerFile;      // null = do not fetch a banner
        public boolean force;        // explicit user action: ignore misses and cooldowns
        public boolean replaceStale; // when the game is found but has no image of a kind, delete the old one
    }

    public static final class Result {
        public boolean resolved;
        public boolean coverSaved;
        public boolean bannerSaved;
        public boolean authProblem;
        public boolean coverEmpty;   // game found, but SteamGridDB has no cover for it
        public boolean bannerEmpty;  // game found, but SteamGridDB has no banner for it
        public String message = "";

        public boolean anySaved() { return coverSaved || bannerSaved; }
    }

    public interface Listener {
        void onFinished(Result result);
    }

    private static final class Target {
        int sgdbId;
        long steamAppId;
    }

    private static final class Match {
        final SteamGridSearchResponse.GameData game;
        final double score;
        Match(SteamGridSearchResponse.GameData game, double score) { this.game = game; this.score = score; }
    }

    private static final class AuthException extends IOException {}
    private static final class RateLimitException extends IOException {}

    // ------------------------------------------------------------------ files

    public static File dir(String kind) {
        String sub = KIND_COVER.equals(kind) ? "covers" : KIND_BANNER.equals(kind) ? "banners" : "icons";
        File target = new File(Environment.getExternalStorageDirectory(), "Winlator/" + sub);
        if (!target.exists()) target.mkdirs();
        File nomedia = new File(target, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (IOException ignored) {}
        }
        return target;
    }

    public static File coverFile(String baseName) { return new File(dir(KIND_COVER), baseName + ".png"); }
    public static File bannerFile(String baseName) { return new File(dir(KIND_BANNER), baseName + ".png"); }
    public static File autoIconFile(String baseName) { return new File(dir(KIND_ICON), baseName + ".png"); }
    public static File userIconFile(String baseName) { return new File(dir(KIND_ICON), baseName + ".user.png"); }

    /**
     * Images the user picked themselves follow the icon convention (NAME.user.png): they win over
     * downloaded and generated ones, the downloader never writes or removes them, and they have
     * their own reset. Only an explicit Search / Re-download (or a reset) replaces them.
     */
    public static File userCoverFile(String baseName) { return new File(dir(KIND_COVER), baseName + ".user.png"); }
    public static File userBannerFile(String baseName) { return new File(dir(KIND_BANNER), baseName + ".user.png"); }

    public static boolean isUsable(File file) { return file != null && file.isFile() && file.length() > 0; }

    /** The user's own cover if there is one, else the downloaded cover; null when neither exists. */
    public static File effectiveCover(String baseName) {
        File user = userCoverFile(baseName);
        if (isUsable(user)) return user;
        File cover = coverFile(baseName);
        return cover.exists() ? cover : null;
    }

    /** The user's own background if there is one, else the downloaded banner; null when neither exists. */
    public static File effectiveBanner(String baseName) {
        File user = userBannerFile(baseName);
        if (isUsable(user)) return user;
        File banner = bannerFile(baseName);
        return banner.exists() ? banner : null;
    }

    /**
     * Offline placeholder cover built from the game's own icon. Deliberately a separate file from
     * coverFile(): a real cover has to stay "missing" for the downloader, and a generated one must
     * never be mistaken for it. Tiles show the real cover when there is one, else this.
     */
    public static File generatedCoverFile(String baseName) { return new File(dir(KIND_COVER), baseName + ".gen.png"); }

    /** Deletes cover, banner and icons of a game and forgets what was remembered about it. */
    public static void deleteArtwork(Context context, String baseName) {
        coverFile(baseName).delete();
        userCoverFile(baseName).delete();
        generatedCoverFile(baseName).delete();
        bannerFile(baseName).delete();
        userBannerFile(baseName).delete();
        autoIconFile(baseName).delete();
        userIconFile(baseName).delete();
        forget(context, baseName);
    }

    /**
     * Called after a shortcut was removed. Artwork is keyed by the shortcut's file name, so a clone
     * in another container shares it: the files only go when no other shortcut still uses them.
     */
    public static void deleteArtworkIfUnused(Context context, File removedShortcut) {
        final Context app = context.getApplicationContext();
        final String baseName = FileUtils.getBasename(removedShortcut.getPath());
        EXECUTOR.execute(() -> {
            try {
                ArrayList<Shortcut> all = new ContainerManager(app).loadShortcuts();
                if (all != null) {
                    for (Shortcut other : all) {
                        if (other == null || other.file == null) continue;
                        if (other.file.getPath().equals(removedShortcut.getPath())) continue;
                        if (!other.file.exists()) continue;
                        if (baseName.equals(FileUtils.getBasename(other.file.getPath()))) return;
                    }
                }
                deleteArtwork(app, baseName);
            } catch (Throwable error) {
                Log.w(TAG, "Artwork cleanup failed: " + error.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ offline placeholder cover

    public static void runLocal(Runnable task) {
        LOCAL_EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                Log.w(TAG, "Local artwork task failed", error);
            }
        });
    }

    /**
     * Writes covers/NAME.gen.png from the first usable icon among iconCandidates. Returns true only
     * when a new file was created. With no real icon nothing is generated (a solid colour square
     * would look worse than the plain icon tile). Call on a worker thread.
     */
    public static boolean generateCover(String baseName, File... iconCandidates) {
        File dest = generatedCoverFile(baseName);
        if (dest.isFile() && dest.length() > 0) return false;
        for (File candidate : iconCandidates) {
            if (candidate == null || !candidate.isFile() || candidate.length() == 0) continue;
            Bitmap icon = decodeScaled(candidate, 512);
            if (icon == null) continue;
            try {
                if (ExeIconExtractor.saveCoverFromIcon(icon, dest)) return true;
            } finally {
                icon.recycle();
            }
        }
        return false;
    }

    /** Decodes an image so its long side is at most about maxSide (a user-picked icon can be huge). */
    static Bitmap decodeScaled(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / maxSide);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    // ------------------------------------------------------------------ settings and state

    public static boolean isMotionEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ANIMATED_ARTWORK, true);
    }

    public static boolean isAutoDownloadEnabled(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_DOWNLOAD, true);
    }

    /** Automatic download switched on in Settings and not turned off for this shortcut. */
    public static boolean isAutoAllowed(Context context, File shortcutFile) {
        if (!isAutoDownloadEnabled(context)) return false;
        if (shortcutFile == null || !shortcutFile.isFile()) return true;
        String marker = EXTRA_SOURCE + "=" + SOURCE_NONE;
        for (String line : FileUtils.readLines(shortcutFile)) {
            if (line != null && line.trim().equals(marker)) return false;
        }
        return true;
    }

    /** True while a kind should not be requested again (recent miss / failure / API blocked). */
    public static boolean shouldSkip(Context context, String baseName, String kind) {
        long now = System.currentTimeMillis();
        if (blockedUntil > now) return true;
        Long failedAt = transientFailures.get(kind + ":" + baseName);
        if (failedAt != null && now - failedAt < TRANSIENT_COOLDOWN_MS) return true;
        long missAt = cachePrefs(context).getLong("miss:" + kind + ":" + baseName, 0L);
        return missAt != 0L && now - missAt < MISS_TTL_MS;
    }

    /** Clears remembered id, misses and cooldowns of one game (used by "Re-download"). */
    public static void forget(Context context, String baseName) {
        cachePrefs(context).edit()
                .remove("id:" + baseName)
                .remove("miss:" + KIND_COVER + ":" + baseName)
                .remove("miss:" + KIND_BANNER + ":" + baseName)
                .apply();
        transientFailures.remove(KIND_COVER + ":" + baseName);
        transientFailures.remove(KIND_BANNER + ":" + baseName);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static SharedPreferences cachePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
    }

    /** Read on every request (never cached in a static), so changing the key takes effect at once. */
    private static String apiKey(Context context) {
        return RemoteSources.steamGridApiKey(context);
    }

    /**
     * Forgets a "key rejected" / "rate limit" pause. Called when the person changes the key or an
     * address in Settings: the reason for the pause may be gone, so waiting it out makes no sense.
     */
    public static void clearBlock() {
        blockedUntil = 0L;
        blockedReason = "";
    }

    // ------------------------------------------------------------------ async entry points

    public static void enqueue(Job job, Listener listener) {
        EXECUTOR.execute(() -> {
            Result result;
            try {
                result = run(job);
            } catch (Throwable error) {
                Log.e(TAG, "Artwork job failed: " + error.getMessage(), error);
                result = new Result();
                result.message = "Artwork download failed";
            }
            if (listener != null) listener.onFinished(result);
        });
    }

    /** Downloads one image from an arbitrary URL into dest (used for "direct image URL"). */
    public static void enqueueDirect(String url, File dest, int maxLongSide, Listener listener) {
        EXECUTOR.execute(() -> {
            Result result = new Result();
            try {
                boolean saved = downloadTo(url, dest, maxLongSide);
                result.coverSaved = saved;
                if (!saved) result.message = "Could not download that image";
            } catch (Throwable error) {
                Log.w(TAG, "Direct download failed: " + error.getMessage());
                result.message = "Could not download that image";
            }
            if (listener != null) listener.onFinished(result);
        });
    }

    /**
     * Synchronous version for a worker thread: reads the picked image and stores it (images within
     * maxLongSide as they are, larger ones scaled down). The same Uri can be imported several times,
     * e.g. as cover and as background.
     */
    public static boolean importFromUri(Context context, Uri uri, File dest, int maxLongSide) {
        try (InputStream in = context.getApplicationContext().getContentResolver().openInputStream(uri)) {
            byte[] data = in != null ? readLimited(in, 32 * 1024 * 1024) : null;
            return data != null && saveBytes(data, dest, maxLongSide);
        } catch (Throwable error) {
            Log.w(TAG, "Import from gallery failed: " + error.getMessage());
            return false;
        }
    }

    /** Width / height of a picked image from its header only (no decode); 0 when unreadable. */
    public static float imageAspect(Context context, Uri uri) {
        try (InputStream in = context.getApplicationContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return 0f;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0 ? (float) bounds.outWidth / bounds.outHeight : 0f;
        } catch (Throwable error) {
            return 0f;
        }
    }

    public static int iconMaxLongSide() { return ICON_MAX_LONG_SIDE; }
    public static int coverMaxLongSide() { return COVER_MAX_LONG_SIDE; }
    public static int bannerMaxLongSide() { return BANNER_MAX_LONG_SIDE; }

    // ------------------------------------------------------------------ user input

    /**
     * Turns what the user typed into a stored source:
     * null (empty) = automatic, "sgdb:ID", "steam:ID", "url:..." (direct image, not stored),
     * otherwise "query:TEXT".
     */
    public static String parseUserInput(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        Matcher m = Pattern.compile("(?i)steamgriddb\\.com/game/(\\d+)").matcher(text);
        if (m.find()) return "sgdb:" + m.group(1);
        m = Pattern.compile("(?i)store\\.steampowered\\.com/app/(\\d+)").matcher(text);
        if (m.find()) return "steam:" + m.group(1);
        m = Pattern.compile("(?i)^steam:\\s*(\\d+)$").matcher(text);
        if (m.find()) return "steam:" + m.group(1);
        m = Pattern.compile("(?i)^(?:sgdb:\\s*)?(\\d+)$").matcher(text);
        if (m.find()) return "sgdb:" + m.group(1);
        if (text.regionMatches(true, 0, "http://", 0, 7) || text.regionMatches(true, 0, "https://", 0, 8)) {
            return "url:" + text;
        }
        return "query:" + text;
    }

    /** What to show in the input field for the source currently stored on a shortcut. */
    public static String sourceToInput(String source, String fallbackName) {
        if (source == null || source.isEmpty() || SOURCE_NONE.equals(source)) return fallbackName;
        if (source.startsWith("query:")) return source.substring(6);
        if (source.startsWith("sgdb:")) return "https://www.steamgriddb.com/game/" + source.substring(5);
        if (source.startsWith("steam:")) return "steam:" + source.substring(6);
        return fallbackName;
    }

    // ------------------------------------------------------------------ the job

    private static Result run(Job job) {
        Result result = new Result();
        Context context = job.context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!job.force && blockedUntil > now) {
            result.message = blockedReason;
            return result;
        }

        String auth = "Bearer " + apiKey(context);
        try {
            Target target = resolve(context, auth, job);
            if (target == null) {
                result.message = "No matching game found on SteamGridDB";
                if (job.coverFile != null) markMiss(context, KIND_COVER, job.baseName);
                if (job.bannerFile != null) markMiss(context, KIND_BANNER, job.baseName);
                return result;
            }
            result.resolved = true;
            if (job.coverFile != null) {
                result.coverSaved = fetchKind(context, auth, target, KIND_COVER, job.coverFile, job, result);
            }
            if (job.bannerFile != null) {
                result.bannerSaved = fetchKind(context, auth, target, KIND_BANNER, job.bannerFile, job, result);
            }
            if (result.anySaved()) {
                // The new game has artwork, so an older image of a kind it lacks would be another
                // game's. When nothing at all was found the existing files are left untouched.
                if (job.replaceStale) {
                    if (result.coverEmpty && job.coverFile != null) job.coverFile.delete();
                    if (result.bannerEmpty && job.bannerFile != null) job.bannerFile.delete();
                }
            } else {
                result.message = "SteamGridDB has no artwork for this game";
            }
        } catch (AuthException e) {
            blockedUntil = System.currentTimeMillis() + BLOCK_MS;
            blockedReason = "SteamGridDB rejected the API key";
            result.authProblem = true;
            result.message = blockedReason + " - check it in Settings";
        } catch (RateLimitException e) {
            blockedUntil = System.currentTimeMillis() + BLOCK_MS;
            blockedReason = "SteamGridDB rate limit reached, try again later";
            result.message = blockedReason;
        } catch (IOException e) {
            Log.w(TAG, "Network error: " + e.getMessage());
            markTransient(job);
            result.message = "Network error - check your connection";
        } catch (RuntimeException e) {
            // Cool down instead of retrying on every scroll; keep the stack trace for the log.
            Log.w(TAG, "Artwork lookup failed", e);
            markTransient(job);
            result.message = e instanceof JsonParseException
                    ? "Unexpected response from SteamGridDB"
                    : "Artwork lookup failed (" + e.getClass().getSimpleName() + ")";
        }
        return result;
    }

    private static void markTransient(Job job) {
        long stamp = System.currentTimeMillis();
        if (job.coverFile != null) transientFailures.put(KIND_COVER + ":" + job.baseName, stamp);
        if (job.bannerFile != null) transientFailures.put(KIND_BANNER + ":" + job.baseName, stamp);
    }

    private static Target resolve(Context context, String auth, Job job) throws IOException {
        String source = job.source;
        if (source != null && source.startsWith("sgdb:")) {
            Target t = new Target();
            t.sgdbId = parseInt(source.substring(5));
            return t.sgdbId > 0 ? t : null;
        }
        if (source != null && source.startsWith("steam:")) {
            Target t = new Target();
            t.steamAppId = parseLong(source.substring(6));
            return t.steamAppId > 0 ? t : null;
        }

        List<String> queries;
        double minScore;
        boolean automatic;
        if (source != null && source.startsWith("query:")) {
            queries = Collections.singletonList(source.substring(6).trim());
            minScore = 0.0; // the user typed it on purpose: take the closest result
            automatic = false;
        } else {
            int cached = cachePrefs(context).getInt("id:" + job.baseName, 0);
            if (cached > 0 && !job.force) {
                Target t = new Target();
                t.sgdbId = cached;
                return t;
            }
            List<String> built;
            try {
                built = buildQueries(job.name, job.windowsPath);
            } catch (RuntimeException error) {
                Log.w(TAG, "buildQueries failed, using the plain name", error);
                built = Collections.singletonList(job.name == null ? "" : job.name.trim());
            }
            queries = built;
            minScore = AUTO_MATCH_THRESHOLD;
            automatic = true;
        }

        // Try every candidate query and keep the best title, instead of taking the first one that
        // clears the threshold: "Baldurs Gate 3" alone finds "Baldur's Gate 3 Toolkit" (0.85), while
        // the next candidate finds the game itself (1.0). A near-exact match ends the search early.
        Match best = null;
        for (String query : queries) {
            if (query.isEmpty()) continue;
            Match found = search(context, auth, query);
            if (found != null && (best == null || found.score > best.score)) best = found;
            if (best != null && best.score >= EARLY_STOP_SCORE) break;
        }
        if (best == null || best.score < minScore) return null;
        if (automatic) cachePrefs(context).edit().putInt("id:" + job.baseName, best.game.id).apply();
        Target t = new Target();
        t.sgdbId = best.game.id;
        return t;
    }

    private static Match search(Context context, String auth, String query) throws IOException {
        Response<SteamGridSearchResponse> response = api(context).searchGame(auth, query).execute();
        checkHttp(response.code());
        if (!response.isSuccessful() || response.body() == null || response.body().data == null) return null;

        SteamGridSearchResponse.GameData best = null;
        double bestScore = -1;
        for (SteamGridSearchResponse.GameData game : response.body().data) {
            if (game == null || game.id <= 0) continue;
            double score = similarity(query, game.name == null ? "" : game.name);
            if (score > bestScore) {
                bestScore = score;
                best = game;
            }
        }
        return best != null ? new Match(best, bestScore) : null;
    }

    private static boolean fetchKind(Context context, String auth, Target target, String kind,
                                     File dest, Job job, Result result) throws IOException {
        boolean cover = KIND_COVER.equals(kind);
        String dimensions = cover ? COVER_DIMENSIONS : BANNER_DIMENSIONS;
        Response<SteamGridGridsResponse> response = target.steamAppId > 0
                ? api(context).getGridsBySteamAppId(auth, target.steamAppId, null, dimensions, "static").execute()
                : api(context).getGridsByGameId(auth, target.sgdbId, null, dimensions, "static").execute();
        checkHttp(response.code());

        List<SteamGridGridsResponse.Grid> ranked = new ArrayList<>();
        if (response.isSuccessful() && response.body() != null && response.body().data != null) {
            for (SteamGridGridsResponse.Grid grid : response.body().data) {
                if (grid != null && grid.url != null && !grid.url.isEmpty()) ranked.add(grid);
            }
        }
        final List<String> rank = cover ? COVER_RANK : BANNER_RANK;
        Collections.sort(ranked, new Comparator<SteamGridGridsResponse.Grid>() {
            @Override
            public int compare(SteamGridGridsResponse.Grid a, SteamGridGridsResponse.Grid b) {
                int byStyle = Integer.compare(styleRank(a), styleRank(b));
                return byStyle != 0 ? byStyle : Integer.compare(dimensionRank(a, rank), dimensionRank(b, rank));
            }
        });

        if (ranked.isEmpty()) {
            markMiss(context, kind, job.baseName);
            if (cover) result.coverEmpty = true; else result.bannerEmpty = true;
            return false;
        }

        int maxSide = cover ? COVER_MAX_LONG_SIDE : BANNER_MAX_LONG_SIDE;
        for (int i = 0; i < ranked.size() && i < 3; i++) {
            if (downloadTo(ranked.get(i).url, dest, maxSide)) {
                cachePrefs(context).edit().remove("miss:" + kind + ":" + job.baseName).apply();
                transientFailures.remove(kind + ":" + job.baseName);
                return true;
            }
        }
        // Images listed but none could be fetched/decoded: treat as a temporary problem.
        transientFailures.put(kind + ":" + job.baseName, System.currentTimeMillis());
        return false;
    }

    private static int styleRank(SteamGridGridsResponse.Grid grid) {
        return "alternate".equalsIgnoreCase(grid.style) ? 0 : 1;
    }

    private static int dimensionRank(SteamGridGridsResponse.Grid grid, List<String> rank) {
        if (grid.width <= 0 || grid.height <= 0) return rank.size();
        int index = rank.indexOf(grid.width + "x" + grid.height);
        return index >= 0 ? index : rank.size();
    }

    private static void markMiss(Context context, String kind, String baseName) {
        cachePrefs(context).edit().putLong("miss:" + kind + ":" + baseName, System.currentTimeMillis()).apply();
    }

    private static void checkHttp(int code) throws IOException {
        if (code == 401 || code == 403) throw new AuthException();
        if (code == 429) throw new RateLimitException();
    }

    // ------------------------------------------------------------------ http + images

    private static SteamGridDBApi api(Context context) {
        String base = RemoteSources.steamGridBase(context);
        SteamGridDBApi local = api;
        if (local == null || !base.equals(apiBase)) {
            synchronized (ArtworkRepository.class) {
                local = api;
                if (local == null || !base.equals(apiBase)) {
                    Gson gson = new GsonBuilder()
                            .registerTypeAdapter(SteamGridGridsResponse.class, new SteamGridGridsResponseDeserializer())
                            .create();
                    try {
                        local = buildApi(base, gson);
                    } catch (IllegalArgumentException invalidBase) {
                        // A malformed address typed in Settings must not crash artwork loading:
                        // use the default for it. apiBase still remembers the address that was asked
                        // for, so the fallback is not rebuilt on every call.
                        local = buildApi(RemoteSources.DEFAULT_STEAMGRID, gson);
                    }
                    api = local;
                    apiBase = base;
                }
            }
        }
        return local;
    }

    private static SteamGridDBApi buildApi(String base, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(base)
                .client(client())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(SteamGridDBApi.class);
    }

    private static OkHttpClient client() {
        OkHttpClient local = http;
        if (local == null) {
            synchronized (ArtworkRepository.class) {
                local = http;
                if (local == null) {
                    // Derived from the app-wide client so connections and threads are shared;
                    // only the (shorter) artwork timeouts differ.
                    local = Downloader.client().newBuilder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .callTimeout(40, TimeUnit.SECONDS)
                            .build();
                    http = local;
                }
            }
        }
        return local;
    }

    private static boolean downloadTo(String url, File dest, int maxLongSide) {
        try {
            byte[] data = Downloader.fetchBytes(url, Downloader.Fetch.limit(MAX_DOWNLOAD_BYTES).timeouts(10_000, 20_000, 40_000));
            return saveBytes(data, dest, maxLongSide);
        } catch (Exception error) {
            Log.w(TAG, "Image download failed: " + error.getMessage());
            return false;
        }
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Validates the bytes as an image and stores them. Images within maxLongSide are written as they
     * came (no re-encode); larger ones are decoded with a sample size, scaled and saved as PNG.
     * The file appears atomically: a half-written cover is never picked up by a tile.
     */
    private static boolean saveBytes(byte[] data, File dest, int maxLongSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(dest.getPath() + ".tmp");
        try {
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            if (longSide <= maxLongSide) {
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(data);
                }
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = Math.max(1, longSide / (maxLongSide * 2));
                Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (decoded == null) return false;
                float scale = (float) maxLongSide / Math.max(decoded.getWidth(), decoded.getHeight());
                Bitmap scaled = scale < 1f
                        ? Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)),
                                Math.max(1, Math.round(decoded.getHeight() * scale)), true)
                        : decoded;
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
                } finally {
                    if (scaled != decoded) scaled.recycle();
                    decoded.recycle();
                }
            }
            if (temp.renameTo(dest)) return true;
            dest.delete();
            return temp.renameTo(dest);
        } catch (Exception error) {
            Log.w(TAG, "Saving image failed: " + error.getMessage());
            return false;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    // ------------------------------------------------------------------ query building + matching

    private static final Set<String> GENERIC_NAMES = new HashSet<>(java.util.Arrays.asList(
            "game", "games", "launcher", "setup", "installer", "start", "run", "update", "patch", "loader",
            "client", "app", "main", "boot", "play", "application", "shipping", "x64", "x86", "win64",
            "win32", "binaries", "bin", "bin32", "bin64", "system", "system32", "release", "retail",
            "common", "steamapps", "steam", "program files", "program files x86", "users", "storage",
            "sdcard", "emulated", "download", "downloads", "drive c", "windows", "data", "content",
            "engine", "exe", "wine", "z", "c", "d", "e", "f", "g"));

    /** Search terms for a shortcut, best first: its name, the folders above the exe, the exe name. */
    static List<String> buildQueries(String name, String windowsPath) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String cleanedName = cleanQuery(name);
        addQuery(out, cleanedName);
        addQuery(out, splitCamel(cleanedName));

        if (windowsPath != null && !windowsPath.isEmpty()) {
            String normalized = windowsPath.replace('"', ' ').trim().replace('\\', '/');
            String[] parts = normalized.split("/+");
            int taken = 0;
            for (int i = parts.length - 2; i >= 0 && taken < 4; i--) {
                String segment = parts[i].trim();
                if (segment.isEmpty()) continue;
                String cleaned = cleanQuery(segment);
                if (isGeneric(cleaned)) continue;
                taken++;
                addQuery(out, cleaned);
                addQuery(out, splitCamel(cleaned));
            }
            String exe = parts[parts.length - 1].trim();
            int dot = exe.lastIndexOf('.');
            if (dot > 0) exe = exe.substring(0, dot);
            addQuery(out, splitCamel(cleanQuery(exe)));
        }

        List<String> list = new ArrayList<>(out.values());
        return list.size() > MAX_QUERIES ? new ArrayList<>(list.subList(0, MAX_QUERIES)) : list;
    }

    private static void addQuery(Map<String, String> out, String query) {
        if (query == null) return;
        String trimmed = query.trim();
        if (trimmed.length() < 2 || isGeneric(trimmed)) return;
        if (trimmed.matches("^[\\p{N} ]+$")) return;
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (!out.containsKey(key)) out.put(key, trimmed);
    }

    private static boolean isGeneric(String text) {
        if (text == null) return true;
        String key = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return key.isEmpty() || GENERIC_NAMES.contains(key);
    }

    /**
     * Unicode-safe clean-up: keeps every letter/digit (Cyrillic, accents, ...), drops release tags.
     * Android's regex engine is ICU, not java.util.regex: JVM-only inline flags such as (?U) make
     * Pattern.compile throw there (it did: every automatic lookup failed), so only (?i) is used.
     */
    static String cleanQuery(String raw) {
        if (raw == null) return "";
        String n = raw;
        n = n.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ");
        n = n.replaceAll("(?i)\\bv?\\d+(?:\\.\\d+)+\\b", " ");
        n = n.replaceAll("(?i)\\bv\\d+\\b", " ");
        n = n.replace('_', ' ').replace('-', ' ').replace('.', ' ');
        n = n.replaceAll("(?i)\\b(repack|setup|installer|portable|goty|gog|fitgirl|dodi|codex|skidrow|win64|win32|x64|x86|shipping)\\b", " ");
        n = n.replaceAll("[^\\p{L}\\p{N}' ]+", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** "HogwartsLegacy" -> "Hogwarts Legacy", "Cyberpunk2077" -> "Cyberpunk 2077", "ACOdyssey" -> "AC Odyssey". */
    static String splitCamel(String s) {
        if (s == null) return "";
        return s.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ")
                .replaceAll("(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})", " ")
                .replaceAll("(?<=\\p{L})(?=\\p{N})", " ")
                .replaceAll("(?<=\\p{N})(?=\\p{L})", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static final Set<String> STOP_TOKENS = new HashSet<>(java.util.Arrays.asList("the", "a", "an", "of", "and"));

    /** 0..1 how well a SteamGridDB game title matches what we searched for. */
    static double similarity(String query, String title) {
        List<String> a = tokens(query);
        List<String> b = tokens(title);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        String compactA = join(a);
        String compactB = join(b);
        if (compactA.equals(compactB)) return 1.0;
        int shorter = Math.min(compactA.length(), compactB.length());
        int longer = Math.max(compactA.length(), compactB.length());
        if (shorter >= 4 && (compactA.contains(compactB) || compactB.contains(compactA))) {
            // One title inside the other: the more extra text ("Toolkit", "Mod Manager"), the weaker.
            return 0.6 + 0.3 * shorter / longer;
        }

        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        setA.removeAll(STOP_TOKENS);
        setB.removeAll(STOP_TOKENS);
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    private static List<String> tokens(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>();
        for (String token : n.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) list.add(token);
        }
        // "Baldur's Gate III" (SteamGridDB) vs "Baldurs Gate 3" (file name): compare sequel numbers as digits.
        for (int i = 0; i < list.size(); i++) {
            String number = romanToDigits(list.get(i), i == list.size() - 1 && list.size() >= 2);
            if (number != null) list.set(i, number);
        }
        return list;
    }

    // ii..xx always; single i / v / x only as the last word of a multi-word title ("Mega Man X").
    private static String romanToDigits(String token, boolean lastWord) {
        if (token.length() == 1) {
            if (!lastWord) return null;
            switch (token.charAt(0)) {
                case 'i': return "1";
                case 'v': return "5";
                case 'x': return "10";
                default: return null;
            }
        }
        if (token.length() > 4 || !token.matches("[ivx]+")) return null;
        int total = 0;
        int previous = 0;
        for (int i = token.length() - 1; i >= 0; i--) {
            int value = token.charAt(i) == 'i' ? 1 : token.charAt(i) == 'v' ? 5 : 10;
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total >= 2 && total <= 20 ? String.valueOf(total) : null;
    }

    private static String join(List<String> tokens) {
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) builder.append(token);
        return builder.toString();
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
