package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.contentdialog.DriverRepo;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single place for every remote address the app talks to. Defaults live here; the installed app
 * can override the component / driver / controls addresses through "Winlator servers" (Components
 * screen) and the artwork ones (SteamGridDB key and address, TheGamesDB addresses) in Settings,
 * COVER ART. A blank stored value means "use the default", so resetting a field is just removing
 * its preference.
 *
 * Existing preference keys ({@link #KEY_CONTENTS_URL}, {@link #KEY_DRIVER_REPOS}) are kept as they
 * were, so values users already saved keep working.
 */
public final class RemoteSources {
    private RemoteSources() {}

    public static final String KEY_CONTENTS_URL = "downloadable_contents_url";
    public static final String KEY_DRIVER_REPOS = "custom_driver_repos";
    public static final String KEY_INPUT_CONTROLS = "svc_input_controls_url";
    public static final String KEY_STEAMGRID = "svc_steamgriddb_url";
    public static final String KEY_GAMESDB_SEARCH = "svc_thegamesdb_search_url";
    public static final String KEY_GAMESDB_CDN = "svc_thegamesdb_cdn_url";
    /** Kept from when this was the only artwork setting, so keys people already saved still work. */
    public static final String KEY_STEAMGRID_API_KEY = "custom_api_key";

    public static final String DEFAULT_CONTENTS_URL = "https://raw.githubusercontent.com/teldommm/Winlator-Contents/main/contents.json";
    public static final String DEFAULT_INPUT_CONTROLS = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/";
    public static final String DEFAULT_STEAMGRID = "https://www.steamgriddb.com/api/v2/";
    public static final String DEFAULT_GAMESDB_SEARCH = "https://thegamesdb.net/search.php?name={query}&platform_id%5B%5D=1";
    public static final String DEFAULT_GAMESDB_CDN = "https://cdn.thegamesdb.net/images/original/";
    /** The key every install shares until the person enters their own. */
    public static final String DEFAULT_STEAMGRID_API_KEY = "0324c52513634547a7b32d6d323635d0";

    public static final String QUERY_PLACEHOLDER = "{query}";


    private static final Pattern GITHUB_REPO_URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_REPO = Pattern.compile("^([\\w.-]+)/([\\w.-]+)$");

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    // ------------------------------------------------------------------ generic string values

    /** Stored value (trimmed), or {@code fallback} when nothing / only whitespace is stored. */
    public static String get(Context context, String key, String fallback) {
        String stored = prefs(context).getString(key, null);
        if (stored == null) return fallback;
        stored = stored.trim();
        return stored.isEmpty() ? fallback : stored;
    }

    /** Stores {@code value}; a blank value or one equal to the default removes the override. */
    public static void set(Context context, String key, String value, String fallback) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.equals(fallback)) editor.remove(key);
        else editor.putString(key, clean);
        editor.apply();
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    // ------------------------------------------------------------------ typed accessors

    public static String contentsUrl(Context context) {
        return get(context, KEY_CONTENTS_URL, DEFAULT_CONTENTS_URL);
    }

    /** Full URL of a file inside the input-controls folder ({@code index.txt}, a profile, ...). */
    public static String inputControlsUrl(Context context, String fileName) {
        return withTrailingSlash(get(context, KEY_INPUT_CONTROLS, DEFAULT_INPUT_CONTROLS)) + fileName;
    }

    /** Always ends with '/', as Retrofit requires. */
    public static String steamGridBase(Context context) {
        return withTrailingSlash(get(context, KEY_STEAMGRID, DEFAULT_STEAMGRID));
    }

    /** {@code encodedQuery} must already be URL-encoded. */
    public static String gamesDbSearchUrl(Context context, String encodedQuery) {
        String template = get(context, KEY_GAMESDB_SEARCH, DEFAULT_GAMESDB_SEARCH);
        if (!template.contains(QUERY_PLACEHOLDER)) template = DEFAULT_GAMESDB_SEARCH;
        return template.replace(QUERY_PLACEHOLDER, encodedQuery);
    }

    public static String gamesDbCdn(Context context) {
        return withTrailingSlash(get(context, KEY_GAMESDB_CDN, DEFAULT_GAMESDB_CDN));
    }

    /** The SteamGridDB API key in use: the person's own, or the shared default. */
    public static String steamGridApiKey(Context context) {
        return get(context, KEY_STEAMGRID_API_KEY, DEFAULT_STEAMGRID_API_KEY);
    }

    private static final String[] ARTWORK_KEYS = {
            KEY_STEAMGRID_API_KEY, KEY_STEAMGRID, KEY_GAMESDB_SEARCH, KEY_GAMESDB_CDN};

    /** True when any artwork key or address differs from its default. */
    public static boolean hasArtworkOverrides(Context context) {
        SharedPreferences preferences = prefs(context);
        for (String key : ARTWORK_KEYS) {
            String stored = preferences.getString(key, null);
            if (stored != null && !stored.trim().isEmpty()) return true;
        }
        return false;
    }

    /** Puts the SteamGridDB key and the artwork addresses back to their defaults. */
    public static void resetArtwork(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String key : ARTWORK_KEYS) editor.remove(key);
        editor.apply();
    }

    // ------------------------------------------------------------------ driver repositories

    public static List<DriverRepo> defaultDriverRepos() {
        ArrayList<DriverRepo> list = new ArrayList<>();
        list.add(new DriverRepo("StevenMXZ Turnip Drivers", "https://api.github.com/repos/StevenMXZ/freedreno_turnip-CI/releases"));
        list.add(new DriverRepo("Whitebelyash Drivers", "https://api.github.com/repos/whitebelyash/AdrenoToolsDrivers/releases"));
        return list;
    }

    /** The saved list, or the defaults when nothing valid is saved. */
    public static List<DriverRepo> driverRepos(Context context) {
        String json = prefs(context).getString(KEY_DRIVER_REPOS, "");
        if (json == null || json.trim().isEmpty()) return defaultDriverRepos();
        ArrayList<DriverRepo> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                DriverRepo repo = DriverRepo.fromJson(array.getJSONObject(i));
                if (repo.apiUrl != null && !repo.apiUrl.trim().isEmpty()) result.add(repo);
            }
        } catch (Exception e) {
            return defaultDriverRepos();
        }
        return result;
    }

    /** Saving the untouched default list removes the override, so future default changes apply. */
    public static void saveDriverRepos(Context context, List<DriverRepo> repos) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (sameRepos(repos, defaultDriverRepos())) {
            editor.remove(KEY_DRIVER_REPOS);
        } else {
            try {
                JSONArray array = new JSONArray();
                for (DriverRepo repo : repos) array.put(repo.toJson());
                editor.putString(KEY_DRIVER_REPOS, array.toString());
            } catch (Exception ignored) {
                return;
            }
        }
        editor.apply();
    }

    private static boolean sameRepos(List<DriverRepo> a, List<DriverRepo> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name.equals(b.get(i).name) || !a.get(i).apiUrl.equals(b.get(i).apiUrl)) return false;
        }
        return true;
    }

    /**
     * Turns what a person is likely to paste into the GitHub Releases API address the catalog
     * needs: {@code owner/repo} or {@code https://github.com/owner/repo[/...]}. Anything else
     * (including an address that already is an API URL) is returned as typed.
     */
    public static String normalizeDriverRepoUrl(String input) {
        String text = input == null ? "" : input.trim();
        Matcher web = GITHUB_REPO_URL.matcher(text);
        if (web.matches()) return apiReleases(web.group(1), web.group(2));
        Matcher shortForm = OWNER_REPO.matcher(text);
        if (shortForm.matches()) return apiReleases(shortForm.group(1), shortForm.group(2));
        return text;
    }

    /** A readable default name for a repository address (the repo part of owner/repo). */
    public static String suggestRepoName(String apiUrl) {
        Matcher m = Pattern.compile("/repos/([\\w.-]+)/([\\w.-]+)").matcher(apiUrl == null ? "" : apiUrl);
        if (m.find()) return m.group(2);
        return "Custom repository";
    }

    private static String apiReleases(String owner, String repo) {
        return "https://api.github.com/repos/" + owner + "/" + repo + "/releases";
    }

    // ------------------------------------------------------------------ reset

    /**
     * Removes the settings of the Proton manifest, a feature that no longer exists (Protons come
     * from the component catalog like every other component). Harmless when they were never set.
     */
    public static void dropLegacyKeys(Context context) {
        prefs(context).edit()
                .remove("svc_proton_manifest_url")
                .remove("svc_proton_manifest_cache_url")
                .remove("svc_proton_manifest_cache_body")
                .apply();
    }

    public static void resetAll(Context context) {
        prefs(context).edit()
                .remove(KEY_CONTENTS_URL)
                .remove(KEY_DRIVER_REPOS)
                .remove(KEY_INPUT_CONTROLS)
                .remove(KEY_STEAMGRID)
                .remove(KEY_GAMESDB_SEARCH)
                .remove(KEY_GAMESDB_CDN)
                .remove(KEY_STEAMGRID_API_KEY)
                .apply();
        dropLegacyKeys(context);
    }
}
