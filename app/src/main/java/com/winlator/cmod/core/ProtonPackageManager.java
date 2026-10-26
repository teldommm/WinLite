package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public abstract class ProtonPackageManager {
    public static final String DEFAULT_IDENTIFIER = "proton-9.0-arm64ec";
    // Where the bundled runtime comes from at build time (see downloadProton in app/build.gradle).
    private static final String BUNDLED_URL = "https://github.com/teldommm/WinLite/releases/download/Assest/proton-9.0-arm64ec.tar.zst";

    public static class PackageInfo {
        public final String identifier;
        public final String title;
        public final String fileName;
        public final long[] partSizes;
        public final String directUrl;
        public final String sha256;

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes) {
            this(identifier, title, fileName, partSizes, null, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes, String directUrl) {
            this(identifier, title, fileName, partSizes, directUrl, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes,
                           String directUrl, String sha256) {
            this.identifier = identifier;
            this.title = title;
            this.fileName = fileName;
            this.partSizes = partSizes;
            this.directUrl = directUrl;
            this.sha256 = sha256;
        }
    }

    // The only package the app itself knows about: the Proton that ships inside the APK. Every other
    // Proton (and Wine) comes from the component catalog (contents.json, Winlator servers) and is
    // handled like any other catalog component - nothing else is hard-coded here.
    private static final List<PackageInfo> PACKAGES = Arrays.asList(
            new PackageInfo(DEFAULT_IDENTIFIER, "Proton 9 arm64ec", "proton-9.0-arm64ec.tar.zst",
                    new long[]{65421756L}, BUNDLED_URL,
                    "2483eed26069968f5221295b7975a65bb1320d30fced8d0cd4458eeb93dd7b8b")
    );

    public static List<PackageInfo> getPackages() {
        return new ArrayList<>(PACKAGES);
    }

    public static PackageInfo getPackage(String identifier) {
        for (PackageInfo packageInfo : PACKAGES)
            if (packageInfo.identifier.equals(identifier)) return packageInfo;
        return null;
    }

    public static boolean isKnownPackage(String identifier) {
        return getPackage(identifier) != null;
    }

    public static File getInstallDir(Context context, String identifier) {
        return new File(ImageFs.find(context).getRootDir(), "opt/" + identifier);
    }

    public static boolean isInstalled(Context context, String identifier) {
        File installDir = getInstallDir(context, identifier);
        File[] files = installDir.listFiles();
        return installDir.isDirectory() && files != null && files.length > 0;
    }

    public static List<String> getInstalledIdentifiers(Context context) {
        ArrayList<String> identifiers = new ArrayList<>();
        for (PackageInfo packageInfo : getPackages())
            if (isInstalled(context, packageInfo.identifier)) identifiers.add(packageInfo.identifier);
        identifiers.addAll(getInstalledUnlisted(context));
        return identifiers;
    }

    /**
     * Proton runtimes that are installed (a non-empty {@code opt/proton-*} folder) but that no list
     * mentions: installed from a package that used to be built into the app. They stay usable and
     * removable instead of silently disappearing.
     */
    public static List<String> getInstalledUnlisted(Context context) {
        ArrayList<String> found = new ArrayList<>();
        File[] folders = new File(ImageFs.find(context).getRootDir(), "opt").listFiles();
        if (folders == null) return found;
        for (File folder : folders) {
            String name = folder.getName();
            if (!folder.isDirectory() || !name.startsWith("proton-") || isKnownPackage(name)) continue;
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) found.add(name);
        }
        Collections.sort(found);
        return found;
    }

    /**
     * Downloads a package into {@code output}, verifying its size (when known) and, when the
     * package has one, its SHA-256. The reason for a failure is in the exception.
     */
    public static void downloadPackageOrThrow(PackageInfo packageInfo, File output, Callback<Integer> progressCallback)
            throws Downloader.DownloadException {
        if (packageInfo == null || output == null || packageInfo.directUrl == null) {
            throw new Downloader.DownloadException(Downloader.Reason.INVALID_URL, "This package has no download address");
        }
        long size = packageInfo.partSizes != null && packageInfo.partSizes.length > 0 ? packageInfo.partSizes[0] : 0L;
        Downloader.Options options = new Downloader.Options().expectedSize(size).sha256(packageInfo.sha256);
        if (progressCallback != null) {
            options.progress(percent -> {
                if (percent >= 0) progressCallback.call(percent);
            });
        }
        Downloader.downloadToFile(packageInfo.directUrl, output, options);
    }

    public static boolean installPackage(Context context, String identifier, File archiveFile) {
        boolean installed = ImageFsInstaller.installWineArchive(context, identifier, archiveFile);
        if (!installed || !isInstalled(context, identifier)) {
            deletePackage(context, identifier);
            return false;
        }
        return true;
    }

    public static void deletePackage(Context context, String identifier) {
        FileUtils.delete(getInstallDir(context, identifier));
    }
}
