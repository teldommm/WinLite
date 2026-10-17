package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.ui.PreloaderOverlayHost;
import com.winlator.cmod.ui.PreloaderOverlayState;
import com.winlator.cmod.ui.library.LibraryImageCache;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.winlator.cmod.steamgrid.ArtworkRepository;

public class PreloaderDialog {
    private static final String TAG = "PreloaderDialog";
    private static final Pattern THEGAMESDB_GAME_ID = Pattern.compile("game\\.php\\?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final ExecutorService ARTWORK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

    private final Activity activity;
    // The Compose overlay (see ui/PreloaderOverlayHost). State is what it draws; the view is only
    // held so close() can remove it.
    private final PreloaderOverlayState state = new PreloaderOverlayState();
    private View overlay;
    private Bitmap artworkBitmap;
    private volatile String theGamesDbRequestKey;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    public synchronized void show(int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (isShowing()) return;
        close();

        if (textResId == R.string.starting_up) {
            configureLaunchScreen(textResId);
        } else {
            configureStandardPreloader(textResId);
        }
        overlay = PreloaderOverlayHost.show(activity, state);
    }

    private void configureLaunchScreen(int textResId) {
        String launchTitleText = resolveLaunchTitle();
        state.setLaunchMode(true);
        state.setTitle(launchTitleText);
        state.setMessage(activity.getString(textResId));

        releaseArtwork();
        // Order: the user's own background, the banner, the user's own cover, the downloaded cover.
        File userBanner = getLaunchUserBannerFile();
        File banner = getLaunchBannerFile();
        boolean hasUserBanner = isUsableImageFile(userBanner);
        boolean hasBanner = hasUserBanner || isUsableImageFile(banner);
        File artwork = hasUserBanner ? userBanner
                : (isUsableImageFile(banner) ? banner : getLaunchCoverFile());
        if (isUsableImageFile(artwork)) artworkBitmap = cachedOrDecodeArtwork(artwork);

        state.setMotion(ArtworkRepository.isMotionEnabled(activity));
        state.setArtwork(artworkBitmap);

        // Honors Settings > Experimental > "Auto-download artwork" and a per-game "Remove artwork".
        if (!hasBanner && banner != null && ArtworkRepository.isAutoAllowed(activity, resolveLaunchShortcutFile())) {
            requestTheGamesDbBanner(launchTitleText, banner);
        }
    }

    private void configureStandardPreloader(int textResId) {
        releaseArtwork();
        state.setLaunchMode(false);
        state.setTitle("");
        state.setMessage(activity.getString(textResId));
    }

    private String resolveLaunchTitle() {
        Intent intent = activity.getIntent();
        String title = intent != null ? intent.getStringExtra("shortcut_name") : null;
        String shortcutPath = intent != null ? intent.getStringExtra("shortcut_path") : null;

        if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(shortcutPath)) {
            title = FileUtils.getBasename(shortcutPath);
        }

        if (TextUtils.isEmpty(title) && intent != null) {
            int containerId = intent.getIntExtra("container_id", 0);
            if (containerId > 0) {
                try {
                    Container container = new ContainerManager(activity).getContainerById(containerId);
                    if (container != null && !TextUtils.isEmpty(container.getName())) title = container.getName();
                } catch (Exception ignored) {}
            }
        }

        return TextUtils.isEmpty(title) ? activity.getString(R.string.app_name) : title;
    }

    private String resolveLaunchBaseName() {
        Intent intent = activity.getIntent();
        if (intent == null) return null;
        String shortcutPath = intent.getStringExtra("shortcut_path");
        if (TextUtils.isEmpty(shortcutPath)) return null;
        String baseName = FileUtils.getBasename(shortcutPath);
        return TextUtils.isEmpty(baseName) ? null : baseName;
    }

    private File resolveLaunchShortcutFile() {
        Intent intent = activity.getIntent();
        String shortcutPath = intent != null ? intent.getStringExtra("shortcut_path") : null;
        return TextUtils.isEmpty(shortcutPath) ? null : new File(shortcutPath);
    }

    private File getLaunchBannerFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        File dir = new File(Environment.getExternalStorageDirectory(), "Winlator/banners");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, baseName + ".png");
    }

    private File getLaunchUserBannerFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        return ArtworkRepository.userBannerFile(baseName);
    }

    private File getLaunchCoverFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        File userCover = ArtworkRepository.userCoverFile(baseName);
        if (isUsableImageFile(userCover)) return userCover;
        return new File(new File(Environment.getExternalStorageDirectory(), "Winlator/covers"), baseName + ".png");
    }

    private boolean isUsableImageFile(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private void requestTheGamesDbBanner(String title, File destination) {
        if (TextUtils.isEmpty(title) || destination == null || isUsableImageFile(destination)) return;
        final String requestKey = destination.getAbsolutePath() + "|" + title;
        if (requestKey.equals(theGamesDbRequestKey)) return;
        theGamesDbRequestKey = requestKey;

        ARTWORK_EXECUTOR.execute(() -> {
            boolean saved = false;
            try {
                int gameId = findTheGamesDbGameId(title);
                if (gameId > 0) saved = downloadTheGamesDbHorizontalArtwork(gameId, destination);
            } catch (Throwable error) {
                Log.d(TAG, "TheGamesDB fallback failed: " + error.getMessage());
            }

            final boolean downloaded = saved;
            activity.runOnUiThread(() -> {
                if (requestKey.equals(theGamesDbRequestKey)) theGamesDbRequestKey = null;
                if (downloaded) applyDownloadedLaunchArtwork(destination);
            });
        });
    }

    private int findTheGamesDbGameId(String title) throws Exception {
        String encoded = URLEncoder.encode(title, "UTF-8");
        String searchUrl = RemoteSources.gamesDbSearchUrl(activity, encoded);
        String html = downloadText(searchUrl);
        Matcher matcher = THEGAMESDB_GAME_ID.matcher(html);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String downloadText(String urlString) throws Exception {
        return Downloader.fetchText(urlString, Downloader.Fetch.limit(MAX_PAGE_BYTES)
                .header("Accept", "text/html,application/xhtml+xml")
                .timeouts(6000, 8000, 0));
    }

    private boolean downloadTheGamesDbHorizontalArtwork(int gameId, File destination) {
        String[] categories = {"fanart", "screenshot", "screenshots"};
        for (String category : categories) {
            for (int index = 1; index <= 3; index++) {
                String url = RemoteSources.gamesDbCdn(activity) + category + "/" + gameId + "-" + index + ".jpg";
                if (downloadImageAsPng(url, destination)) {
                    Log.d(TAG, "TheGamesDB " + category + " saved for game " + gameId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean downloadImageAsPng(String urlString, File destination) {
        Bitmap bitmap = null;
        try {
            byte[] data = Downloader.fetchBytes(urlString, Downloader.Fetch.limit(MAX_IMAGE_BYTES).timeouts(6000, 10000, 0));
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
            if (bitmap.getWidth() < bitmap.getHeight()) return false;

            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return false;
                output.flush();
            }
            return destination.isFile() && destination.length() > 0;
        } catch (Throwable ignored) {
            if (destination.exists() && destination.length() == 0) destination.delete();
            return false;
        } finally {
            if (bitmap != null) {
                try { if (!bitmap.isRecycled()) bitmap.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private void applyDownloadedLaunchArtwork(File artworkFile) {
        if (!isShowing() || !isUsableImageFile(artworkFile)) return;
        Bitmap replacement = decodeArtwork(artworkFile);
        if (replacement == null) return;

        releaseArtwork();
        artworkBitmap = replacement;
        state.setMotion(ArtworkRepository.isMotionEnabled(activity));
        state.setArtwork(artworkBitmap);
    }

    // A game started from the Library usually has this exact picture decoded already (same
    // process, LibraryImageCache, keyed by path + last-modified): use it instead of decoding the
    // file again on the main thread in onCreate. Nothing here recycles bitmaps (see
    // releaseArtwork), so sharing the cached one is safe. Otherwise decode as before.
    private Bitmap cachedOrDecodeArtwork(File file) {
        Bitmap cached = LibraryImageCache.peek(LibraryImageCache.keyFor(file.getAbsolutePath()));
        return cached != null ? cached : decodeArtwork(file);
    }

    private Bitmap decodeArtwork(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int targetWidth = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels);
            int targetHeight = Math.max(1, activity.getResources().getDisplayMetrics().heightPixels);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth
                    && bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void showOnUiThread(final int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        final View view = overlay;
        overlay = null;
        theGamesDbRequestKey = null;
        if (view != null) {
            // View removal belongs on the UI thread; close() may be called from any thread.
            if (Looper.myLooper() == Looper.getMainLooper()) PreloaderOverlayHost.dismiss(view);
            else activity.runOnUiThread(() -> PreloaderOverlayHost.dismiss(view));
        }
        releaseArtwork();
    }

    // Compose may still be drawing the bitmap for a frame after we drop it, so it is not recycled
    // here - just unreferenced and left to the GC.
    private void releaseArtwork() {
        artworkBitmap = null;
        state.setArtwork(null);
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }
}
