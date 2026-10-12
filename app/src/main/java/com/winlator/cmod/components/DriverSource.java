package com.winlator.cmod.components;

import android.content.Context;

import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.RemoteSources;
import com.winlator.cmod.core.VersionOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** AdrenoTools GPU drivers: the ones already installed plus those listed by the driver repositories. */
public final class DriverSource implements ComponentSource {
    private static final String TYPE = "AdrenoTools";

    private final AdrenotoolsManager adrenotools;
    // What each repository (by its address) listed the last time it could be read. Replaced as a
    // whole after a refresh, never edited in place, so entries() can read it from any thread.
    private volatile Map<String, List<RemoteDriverCatalog.Entry>> remoteByRepo = new LinkedHashMap<>();

    public DriverSource(AdrenotoolsManager adrenotools) {
        this.adrenotools = adrenotools;
    }

    @Override
    public String title() {
        return "Driver repositories";
    }

    @Override
    public void refresh(Context context) throws ComponentException {
        ArrayList<String> errors = new ArrayList<>();
        Map<String, List<RemoteDriverCatalog.Entry>> previous = remoteByRepo;
        Map<String, List<RemoteDriverCatalog.Entry>> next = new LinkedHashMap<>();
        for (DriverRepo repo : RemoteSources.driverRepos(context)) {
            if (repo.apiUrl == null || repo.apiUrl.trim().isEmpty()) continue;
            try {
                RemoteDriverCatalog.RepoResult result = RemoteDriverCatalog.loadRepo(context, repo);
                next.put(repo.apiUrl, result.entries);
                if (result.warning != null) errors.add(repo.name + ": " + result.warning);
            } catch (Downloader.DownloadException e) {
                errors.add(repo.name + ": " + e.userMessage());
                // A repository that fails keeps what it listed before, so one bad refresh (no
                // connection, rate limit) does not empty its part of the list. Repositories the
                // person removed are not in the configured list and drop out here.
                List<RemoteDriverCatalog.Entry> before = previous.get(repo.apiUrl);
                if (before != null) next.put(repo.apiUrl, before);
            }
        }
        remoteByRepo = next;
        if (!errors.isEmpty()) {
            String more = errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : "";
            throw new ComponentException(errors.get(0) + more);
        }
    }

    /**
     * Installed drivers first, newest first (by name and version, numbers compared as numbers, so
     * R10 comes before R9); then the drivers the repositories offer, most recently published first
     * across all repositories. The order is final here: the catalog sort keeps it as is, because
     * every driver row has the same version code.
     */
    @Override
    public List<ComponentEntry> entries(Context context) {
        ArrayList<ComponentEntry> out = new ArrayList<>();
        Set<String> existing = new HashSet<>();

        ArrayList<InstalledDriverEntry> installed = new ArrayList<>();
        for (String id : adrenotools.enumarateInstalledDrivers()) {
            String label = (adrenotools.getDriverName(id) + " " + adrenotools.getDriverVersion(id)).trim();
            installed.add(new InstalledDriverEntry(id, label));
            existing.add(label.toLowerCase(Locale.ENGLISH));
        }
        Collections.sort(installed, (left, right) -> {
            int byLabel = compareNatural(right.label, left.label);
            return byLabel != 0 ? byLabel : left.driverId.compareTo(right.driverId);
        });
        out.addAll(installed);

        ArrayList<RemoteDriverCatalog.Entry> available = new ArrayList<>();
        for (List<RemoteDriverCatalog.Entry> repoDrivers : remoteByRepo.values()) available.addAll(repoDrivers);
        // Stable: drivers of one release (and of releases without a date, which end up last) keep
        // the order the server listed them in.
        Collections.sort(available, (left, right) -> Long.compare(right.publishedAt, left.publishedAt));
        for (RemoteDriverCatalog.Entry driver : available) {
            if (existing.contains(driver.name.toLowerCase(Locale.ENGLISH))) continue;
            out.add(new RemoteDriverEntry(driver));
        }
        return out;
    }

    // Text compared case-insensitively, runs of digits by their value: "R9" < "R10".
    static int compareNatural(String a, String b) {
        return VersionOrder.compareNatural(a, b);
    }

    private final class InstalledDriverEntry extends ComponentEntry {
        private final String driverId;
        private final String label;

        InstalledDriverEntry(String driverId, String label) {
            super("adrenotools:" + driverId, TYPE, label, 0);
            this.driverId = driverId;
            this.label = label;
            this.installed = true;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean isDriver() {
            return true;
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            throw new ComponentException(label + " is already installed");
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            return RemovePlan.confirm("Delete driver?", "The installed driver files will be removed.");
        }

        @Override
        public void remove(Context context) {
            adrenotools.removeDriver(driverId);
        }

        @Override
        public String removedMessage() {
            return "Driver removed";
        }
    }

    private static final class RemoteDriverEntry extends ComponentEntry {
        private final RemoteDriverCatalog.Entry driver;

        RemoteDriverEntry(RemoteDriverCatalog.Entry driver) {
            super("remote-driver:" + driver.name + ":" + Integer.toHexString(driver.url.hashCode()), TYPE, driver.name, 0);
            this.driver = driver;
        }

        @Override
        public String label() {
            return driver.name + " \u2022 " + driver.repository;
        }

        @Override
        public String downloadUrl() {
            return driver.url;
        }

        @Override
        public String sha256() {
            return driver.sha256;
        }

        @Override
        public boolean removable() {
            return false;
        }

        @Override
        public boolean isDriver() {
            return true;
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            sink.progress("Installing " + name, -1);
            String installedId;
            try {
                installedId = RemoteDriverCatalog.installOrThrow(context, driver.url, driver.sha256,
                        percent -> sink.progress("Downloading " + name, percent));
            } catch (Downloader.DownloadException e) {
                throw new ComponentException("Unable to install " + name + ": " + e.userMessage(), e);
            }
            if (installedId.isEmpty()) {
                throw new ComponentException("Unable to install " + name + ": not a valid AdrenoTools driver package");
            }
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            return null;
        }

        @Override
        public void remove(Context context) {
        }
    }
}
