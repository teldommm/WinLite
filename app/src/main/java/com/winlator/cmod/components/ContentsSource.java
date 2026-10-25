package com.winlator.cmod.components;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.R;
import com.winlator.cmod.core.BundledComponents;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.RemoteSources;
import com.winlator.cmod.core.VersionOrder;
import com.winlator.cmod.core.WineRuntimeGuard;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Box64, WOWBox64, FEXCore, DXVK, VKD3D, Wine and Proton archives (.wcp) from the component catalog. */
public final class ContentsSource implements ComponentSource {
    private final ContentsManager manager;

    public ContentsSource(ContentsManager manager) {
        this.manager = manager;
    }

    @Override
    public String title() {
        return "Component list";
    }

    @Override
    public void refresh(Context context) throws ComponentException {
        try {
            manager.loadRemoteProfiles(RemoteSources.contentsUrl(context));
        } catch (Downloader.DownloadException e) {
            throw new ComponentException(e.userMessage(), e);
        } finally {
            manager.syncContents();
        }
    }

    @Override
    public List<ComponentEntry> entries(Context context) {
        ArrayList<ComponentEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, ContentEntry> newest = new HashMap<>();
        // A catalog row and the package it installed as are the same thing to the user, whatever
        // name / code each of them carries: installedFor maps a catalog profile to its package on
        // disk, claimed holds the entry names of the packages that are already shown that way.
        Map<ContentProfile, ContentProfile> installedFor = new IdentityHashMap<>();
        Set<String> claimed = new HashSet<>();
        ArrayList<ContentEntry> contentRows = new ArrayList<>();

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = manager.getProfiles(type);
            if (profiles == null) continue;
            for (ContentProfile profile : profiles) {
                if (profile.remoteUrl == null) continue;
                ContentProfile installed = findInstalledFor(profile);
                if (installed != null && claimed.add(ContentsManager.getEntryName(installed))) {
                    installedFor.put(profile, installed);
                }
            }
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = manager.getProfiles(type);
            if (profiles == null) continue;
            for (ContentProfile profile : profiles) {
                String typeName = displayType(type);
                if (typeName.isEmpty() || profile.verName == null || profile.verName.isEmpty()) continue;
                ContentProfile installed;
                if (profile.remoteUrl == null) {
                    if (claimed.contains(ContentsManager.getEntryName(profile))) continue;
                    installed = profile;
                } else {
                    installed = installedFor.get(profile);
                }
                // A catalog row for a version the APK already ships is that same component: one
                // row, marked as built in, instead of a download next to the copy the app has.
                boolean builtIn = installed == null && profile.remoteUrl != null
                        && BundledComponents.contains(context, typeName, profile.verName);
                ContentEntry entry = new ContentEntry(typeName, profile, installed, builtIn);
                if (!seen.add(entry.id)) continue;
                out.add(entry);
                contentRows.add(entry);
                ContentEntry current = newest.get(typeName);
                // The highest version code wins; rows with the same code are told apart by the
                // number in their name instead of by the order they happen to be listed in.
                if (current == null || entry.versionCode > current.versionCode
                        || (entry.versionCode == current.versionCode
                        && VersionOrder.compareNewestFirst(entry.name, current.name) < 0)) {
                    newest.put(typeName, entry);
                }
            }
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            for (ContentProfile profile : manager.getInstalledProfiles(type)) {
                String typeName = displayType(type);
                if (typeName.isEmpty()) continue;
                if (claimed.contains(ContentsManager.getEntryName(profile))) continue;
                ContentEntry entry = new ContentEntry(typeName, profile, profile, false);
                if (!seen.add(entry.id)) continue;
                out.add(entry);
                contentRows.add(entry);
            }
        }

        // What is recommended is decided by the catalog: every row it marks with
        // "recommended": true. Only a type the catalog marks nothing in falls back to a guess
        // (the newest row of that type).
        Set<String> typesWithMarks = new HashSet<>();
        for (ContentEntry entry : contentRows) {
            if (entry.markedInCatalog()) {
                entry.recommended = true;
                entry.pinnedRecommended = true;
                typesWithMarks.add(entry.type);
            }
        }
        for (Map.Entry<String, ContentEntry> guess : newest.entrySet()) {
            if (!typesWithMarks.contains(guess.getKey())) guess.getValue().recommended = true;
        }
        return out;
    }

    public static String displayType(ContentProfile.ContentType type) {
        String raw = type.toString();
        String key = raw.toLowerCase(Locale.ENGLISH).replace("content_type_", "").replace("_", "");
        if (key.contains("wowbox64")) return "WOWBox64";
        if (key.contains("box64")) return "Box64";
        if (key.contains("fexcore")) return "FEXCore";
        if (key.contains("vkd3d")) return "VKD3D";
        if (key.contains("dxvk")) return "DXVK";
        if (key.contains("proton")) return "Proton";
        if (key.contains("wine")) return "Wine";
        return "";
    }

    private ContentProfile findInstalled(ContentProfile.ContentType type, int verCode, String verName) {
        for (ContentProfile installed : manager.getInstalledProfiles(type)) {
            if (installed.verCode == verCode && installed.verName.equals(verName)) return installed;
        }
        return null;
    }

    /**
     * The package on disk that {@code candidate} stands for, or null. A local profile is only ever
     * itself. A catalog profile matches, in this order: a package with the same name and code; the
     * package this entry was installed as (remembered at install time, which is the only thing
     * that works when the package names itself differently from the catalog); a package with the
     * same name (covers what was installed before that was remembered, or from a file).
     */
    private ContentProfile findInstalledFor(ContentProfile candidate) {
        ContentProfile exact = findInstalled(candidate.type, candidate.verCode, candidate.verName);
        if (exact != null || candidate.remoteUrl == null) return exact;

        String remembered = manager.getRememberedInstall(candidate);
        if (remembered != null) {
            for (ContentProfile installed : manager.getInstalledProfiles(candidate.type)) {
                if (remembered.equals(ContentsManager.getEntryName(installed))) return installed;
            }
        }

        ContentProfile sameName = null;
        for (ContentProfile installed : manager.getInstalledProfiles(candidate.type)) {
            if (!installed.verName.equals(candidate.verName)) continue;
            if (sameName == null || installed.verCode > sameName.verCode) sameName = installed;
        }
        return sameName;
    }

    /** The catalog entry of {@code type} named {@code verName} that can be downloaded, or null. */
    public ContentProfile findRemote(ContentProfile.ContentType type, String verName) {
        List<ContentProfile> profiles = manager.getProfiles(type);
        if (profiles == null) return null;
        for (ContentProfile profile : profiles) {
            if (verName.equals(profile.verName) && profile.remoteUrl != null) return profile;
        }
        return null;
    }

    /**
     * Downloads (verifying the catalog's SHA-256 when it has one) and installs a catalog entry.
     * Blocking. This is the one implementation behind every "install a component" button.
     */
    public ContentProfile installRemote(Context context, ContentProfile profile, InstallSink sink)
            throws ComponentException {
        final String name = profile.verName;
        sink.progress("Preparing " + name, 0);
        File archive = new File(context.getCacheDir(), "winlite-component-" + System.nanoTime());
        try {
            Downloader.Options options = new Downloader.Options()
                    .sha256(profile.remoteSha256)
                    .progress(percent -> sink.progress("Downloading " + name, percent < 0 ? -1 : Math.min(70, percent * 70 / 100)));
            try {
                Downloader.downloadToFile(profile.remoteUrl, archive, options);
            } catch (Downloader.DownloadException e) {
                throw new ComponentException("Unable to install " + name + ": " + e.userMessage(), e);
            }
            ContentProfile installed = installArchive(Uri.fromFile(archive), name, 72, sink);
            manager.rememberRemoteInstall(profile, installed);
            return installed;
        } finally {
            archive.delete();
        }
    }

    /**
     * Unpacks and installs a .wcp archive that is already on disk (downloaded, or picked by the
     * user). Blocking. Progress runs from {@code startProgress} to 92 while unpacking, then 100.
     */
    public ContentProfile installArchive(Uri uri, String displayName, int startProgress, InstallSink sink)
            throws ComponentException {
        sink.progress("Installing " + displayName, startProgress);
        final ContentProfile[] extracted = new ContentProfile[1];
        final ComponentException[] failure = new ComponentException[1];

        manager.extraContentFile(uri, archiveProgress -> {
            int progress = archiveProgress < 0
                    ? -1
                    : startProgress + ((92 - startProgress) * archiveProgress / 100);
            sink.progress("Installing " + displayName, progress);
        }, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                failure[0] = new ComponentException("Package validation failed: " + reason);
            }

            @Override
            public void onSucceed(ContentProfile profile) {
                extracted[0] = profile;
            }
        });
        if (failure[0] != null) throw failure[0];
        if (extracted[0] == null) throw new ComponentException("Package validation failed");

        final ContentProfile unpacked = extracted[0];
        final String installedName = unpacked.verName != null && !unpacked.verName.isEmpty()
                ? unpacked.verName : displayName;
        sink.progress("Validating " + installedName, 92);

        final ContentProfile[] result = new ContentProfile[1];
        manager.finishInstallContent(unpacked, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                // Already installed counts as success: the user asked for it and it is there.
                if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) result[0] = unpacked;
                else failure[0] = new ComponentException("Installation failed: " + reason);
            }

            @Override
            public void onSucceed(ContentProfile installed) {
                result[0] = installed != null ? installed : unpacked;
            }
        });
        if (failure[0] != null) throw failure[0];
        if (result[0] == null) throw new ComponentException("Installation failed");
        sink.progress("Installed " + installedName, 100);
        return result[0];
    }

    private final class ContentEntry extends ComponentEntry {
        /** What the row is listed as: a catalog entry, or a package that is only on the device. */
        final ContentProfile profile;
        /** The package on disk behind this row, or null while it is only in the catalog. */
        final ContentProfile installedProfile;
        String installedName;
        /** Shipped inside the APK and not installed from the catalog: nothing to download or delete. */
        final boolean builtIn;

        ContentEntry(String typeName, ContentProfile profile, ContentProfile installedProfile, boolean builtIn) {
            super(typeName + ":" + profile.verCode + ":" + profile.verName, typeName, profile.verName, profile.verCode);
            this.profile = profile;
            this.installedProfile = installedProfile;
            this.builtIn = builtIn;
            this.installed = installedProfile != null || builtIn;
            this.installedName = installedProfile != null ? ContentsManager.getEntryName(installedProfile) : "";
        }

        @Override
        public boolean removable() {
            return !builtIn && installed;
        }

        /** The catalog marks this version (or the catalog version this package was installed as). */
        boolean markedInCatalog() {
            if (manager.isRecommendedInCatalog(profile.type, profile.verName)) return true;
            return installedProfile != null && installedProfile != profile
                    && manager.isRecommendedInCatalog(installedProfile.type, installedProfile.verName);
        }

        @Override
        public String runtimeName() {
            return (type.equals("Wine") || type.equals("Proton")) && installed ? installedName : null;
        }

        @Override
        public String runtimeId() {
            if (!type.equals("Wine") && !type.equals("Proton")) return null;
            // What a container stores is the installed package's name, not the catalog's.
            return ContentsManager.getEntryName(installedProfile != null ? installedProfile : profile);
        }

        @Override
        public String downloadUrl() {
            return profile.remoteUrl;
        }

        @Override
        public String sha256() {
            return profile.remoteSha256;
        }

        @Override
        public boolean canInstall() {
            return !installed && profile.remoteUrl != null && !profile.remoteUrl.isEmpty();
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            installRemote(context, profile, sink);
        }

        // Looked up again instead of using the field: it has to be what is on disk right now.
        private ContentProfile installedProfile() {
            return findInstalledFor(profile);
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            ContentProfile installedProfile = installedProfile();
            if (installedProfile == null) return null;
            if (!WineRuntimeGuard.canRemove(context, installedProfile)) {
                String using = WineRuntimeGuard.getContainerUsing(context, ContentsManager.getEntryName(installedProfile));
                return RemovePlan.blocked("Runtime is in use",
                        installedProfile.verName + " cannot be deleted because it is used by " + using + ".");
            }
            return RemovePlan.confirm("Delete component?",
                    "The installed files will be removed from " + context.getString(R.string.app_name) + ".");
        }

        @Override
        public void remove(Context context) throws ComponentException {
            ContentProfile installedProfile = installedProfile();
            if (installedProfile == null) return;
            if (!WineRuntimeGuard.canRemove(context, installedProfile)) {
                throw new ComponentException(name + " is in use and cannot be deleted");
            }
            manager.removeContent(installedProfile);
            manager.syncContents();
        }
    }
}
