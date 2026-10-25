package com.winlator.cmod.container;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class Shortcut {
    public final Container container;
    public final String name;
    public final String path;
    public Bitmap icon;
    public final File file;
    public File iconFile;
    public final String wmClass;
    private final JSONObject extraData = new JSONObject();
    private Bitmap coverArt;
    private String customCoverArtPath;

    private static final String COVER_ART_DIR = "app_data/cover_arts/";

    public Shortcut(Container container, File file) {
        this.container = container;
        this.file = file;

        String execArgs = "";
        Bitmap icon = null;
        File iconFile = null;
        String wmClass = "";

        File[] iconDirs = {container.getIconsDir(64), container.getIconsDir(48), container.getIconsDir(32), container.getIconsDir(16)};
        String section = "";

        int index;
        for (String line : FileUtils.readLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf("]"));
            }
            else {
                index = line.indexOf("=");
                if (index == -1) continue;
                String key = line.substring(0, index);
                String value = line.substring(index+1);

                if (section.equals("Desktop Entry")) {
                    if (key.equals("Exec")) execArgs = value;
                    if (key.equals("Icon")) {
                        for (File iconDir : iconDirs) {
                            File potentialIcon = new File(iconDir, value+".png");
                            if (!potentialIcon.exists()) potentialIcon = new File(iconDir, value+".ico");

                            if (potentialIcon.isFile()){
                                icon = BitmapFactory.decodeFile(potentialIcon.getPath());
                                iconFile = potentialIcon;
                                break;
                            }
                        }
                    }
                    if (key.equals("StartupWMClass")) wmClass = value;
                }
                else if (section.equals("Extra Data")) {
                    try {
                        extraData.put(key, value);
                    }
                    catch (JSONException e) {}
                }
            }
        }

        try {
            File externalStorage = Environment.getExternalStorageDirectory();
            File customIconsDir = new File(externalStorage, "Winlator/icons");
            String baseName = FileUtils.getBasename(file.getPath());
            File customIcon = new File(customIconsDir, baseName + ".png");

            if (customIcon.exists()) {
                Bitmap customBitmap = BitmapFactory.decodeFile(customIcon.getAbsolutePath());
                if (customBitmap != null) {
                    icon = customBitmap;
                    iconFile = customIcon;
                }
            }

            // The icon the user picked themselves (NAME.user.png) is the one shown; iconFile keeps
            // pointing at the extracted icon, which is what gets copied along with the shortcut.
            File userIcon = new File(customIconsDir, baseName + ".user.png");
            if (userIcon.isFile() && userIcon.length() > 0) {
                Bitmap userBitmap = BitmapFactory.decodeFile(userIcon.getAbsolutePath());
                if (userBitmap != null) icon = userBitmap;
            }
        } catch (Exception e) {}

        this.name = FileUtils.getBasename(file.getPath());

        this.icon = icon;
        this.iconFile = iconFile;

        if (execArgs.contains("wine ")) {
            this.path = StringUtils.unescape(execArgs.substring(execArgs.lastIndexOf("wine ") + 4));
        } else {
            this.path = execArgs;
        }

        this.wmClass = wmClass;

        this.customCoverArtPath = getExtra("customCoverArtPath");
        loadCoverArt();
        Container.checkObsoleteOrMissingProperties(extraData);
    }

    private void loadCoverArt() {
        if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(customCoverArtPath);
            if (customCoverArtFile.isFile()) {
                this.coverArt = BitmapFactory.decodeFile(customCoverArtFile.getPath());
                return;
            }
        }
        File defaultCoverArtFile = new File(COVER_ART_DIR, this.name + ".png");
        if (defaultCoverArtFile.isFile()) {
            this.coverArt = BitmapFactory.decodeFile(defaultCoverArtFile.getPath());
        }
    }

    public Bitmap getCoverArt() { return coverArt; }
    public void setCoverArt(Bitmap coverArt) { this.coverArt = coverArt; }
    public String getCustomCoverArtPath() { return customCoverArtPath; }

    public void setCustomCoverArtPath(String customCoverArtPath) {
        this.customCoverArtPath = customCoverArtPath;
        putExtra("customCoverArtPath", customCoverArtPath);
        saveData();
    }

    public String getExtra(String name) { return getExtra(name, ""); }

    public String getExtra(String name, String fallback) {
        try { return extraData.has(name) ? extraData.getString(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public void putExtra(String name, String value) {
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    // Environment variables of a shortcut: once the shortcut has its own list ("envVars" extra,
    // possibly empty), it fully replaces the container's one at launch. Without it the shortcut
    // simply uses the container's variables.
    public boolean hasOwnEnvVars() {
        return extraData.has("envVars");
    }

    public String getEffectiveEnvVars() {
        return getEffectiveEnvVars(container);
    }

    /** @param base the container the shortcut is actually launched with (may differ from {@link #container}). */
    public String getEffectiveEnvVars(Container base) {
        if (hasOwnEnvVars()) return getExtra("envVars");
        return (base != null ? base : container).getEnvVars();
    }

    public void setOwnEnvVars(String envVars) {
        putExtra("envVars", envVars != null ? envVars : "");
    }

    /** Drops the shortcut's own list: it follows the container's variables again. */
    public void resetEnvVarsToContainer() {
        putExtra("envVars", null);
    }

    public void saveData() {
        String content = "[Desktop Entry]\n";
        for (String line : FileUtils.readLines(file)) {
            if (line.contains("[Extra Data]")) break;
            if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content += line + "\n";
        }

        if (extraData.length() > 0) {
            content += "\n[Extra Data]\n";
            Iterator<String> keys = extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    content += key + "=" + extraData.getString(key) + "\n";
                } catch (JSONException e) {}
            }
        }

        if (!file.getName().endsWith(".desktop")) {
            return;
        }

        FileUtils.writeString(file, content);
    }

    public void genUUID() {
        if (getExtra("uuid").equals("")) {
            putExtra("uuid", UUID.randomUUID().toString());
            saveData();
        }
    }

    public void saveCustomCoverArt(Bitmap coverArt) {
        try {
            File coverArtDir = new File(container.getRootDir(), COVER_ART_DIR);
            if (!coverArtDir.exists()) {
                coverArtDir.mkdirs();
            }

            File coverFile = new File(coverArtDir, this.name + ".png");
            if (FileUtils.saveBitmapToFile(coverArt, coverFile)) {
                this.coverArt = coverArt;
                setCustomCoverArtPath(coverFile.getPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeCustomCoverArt() {
        if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(customCoverArtPath);
            if (customCoverArtFile.exists()) customCoverArtFile.delete();
        }

        this.customCoverArtPath = null;
        this.coverArt = null;
        putExtra("customCoverArtPath", null);
        saveData();
    }

    /**
     * Moves the shortcut to another container (the shortcut settings' container switch): the copy
     * is written first, the old .desktop and its .lnk/.bat go only once it is there. Everything in
     * the .desktop moves with it (settings, uuid, save profile id). Returns the new .desktop, or
     * null if target is this container, already has a shortcut with this name, or the copy failed.
     */
    public File moveToContainer(Container target) {
        if (target == null || target.id == container.id) return null;
        File dst = new File(target.getDesktopDir(), file.getName());
        if (dst.exists()) return null;
        File desktopDir = target.getDesktopDir();
        if (!desktopDir.isDirectory()) desktopDir.mkdirs();
        if (!cloneToContainer(target) || !dst.isFile()) return null;
        // The .lnk goes too: a .lnk without its .desktop is turned back into a shortcut on load.
        String path = file.getPath();
        int dot = path.lastIndexOf('.');
        if (dot > 0) {
            new File(path.substring(0, dot) + ".lnk").delete();
            new File(path.substring(0, dot) + ".bat").delete();
        }
        if (!file.delete()) Log.w("Shortcut", "Moved, but could not delete the old shortcut " + file);
        return dst;
    }

    public boolean cloneToContainer(Container newContainer) {
        try {
            // The copy carries the same save profile id, so the game keeps its saves in the other container.
            SaveProfiles.ensureId(this);
            File newShortcutFile = new File(newContainer.getDesktopDir(), this.file.getName());
            ArrayList<String> lines = FileUtils.readLines(this.file);
            StringBuilder updatedContent = new StringBuilder();
            boolean containerIdFound = false;

            for (String line : lines) {
                if (line.startsWith("container_id:")) {
                    updatedContent.append("container_id:").append(newContainer.id).append("\n");
                    containerIdFound = true;
                } else {
                    updatedContent.append(line).append("\n");
                }
            }

            if (!containerIdFound) {
                updatedContent.append("container_id:").append(newContainer.id).append("\n");
            }

            FileUtils.writeString(newShortcutFile, updatedContent.toString());

            if (this.iconFile != null && this.iconFile.isFile()) {
                File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                FileUtils.copy(this.iconFile, newIconFile);
            }

            return true;
        } catch (Exception e) {
            Log.e("Shortcut", "Failed to clone shortcut to new container", e);
            return false;
        }
    }

    public int getContainerId() {
        return container.id;
    }

    public String getExecutable() {
        String exe = "";
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            for (String line : lines) {
                if (line.startsWith("Exec")) {
                    exe = line.substring(line.lastIndexOf("\\") + 1, line.length()).replaceAll("\\s+$", "");
                    break;
                }
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        return exe;
    }

    /** The surface format the user chose for this shortcut (the container's when it has none). */
    public String getSurfaceFormatSetting() {
        String v = getExtra("surfaceFormat", null);
        if (v == null || v.isEmpty()) return container.getSurfaceFormatSetting();
        return "bgra8".equalsIgnoreCase(v) ? "bgra8" : "rgba8";
    }
    /** What the X server and the guest actually use: always rgba8 with the SurfaceFlinger renderer. */
    public String getSurfaceFormat() {
        return Container.RENDERER_SURFACEFLINGER.equals(getRenderer()) ? "rgba8" : getSurfaceFormatSetting();
    }
    public void setSurfaceFormat(String value) {
        putExtra("surfaceFormat", "bgra8".equalsIgnoreCase(value) ? "bgra8" : "rgba8");
    }

    public String getRenderer() {
        String v = getExtra("renderer", null);
        if (v == null || v.isEmpty()) return container.getRenderer();
        return Container.RENDERER_SURFACEFLINGER.equalsIgnoreCase(v)
                ? Container.RENDERER_SURFACEFLINGER : Container.RENDERER_VULKAN;
    }
    public void setRenderer(String value) {
        putExtra("renderer", value != null && value.equalsIgnoreCase(Container.RENDERER_SURFACEFLINGER)
                ? Container.RENDERER_SURFACEFLINGER : Container.RENDERER_VULKAN);
    }

    public String getRendererPresentMode() {
        String v = getExtra("rendererPresentMode", null);
        return v != null && !v.isEmpty() ? v : container.getRendererPresentMode();
    }
    public void setRendererPresentMode(String v) { putExtra("rendererPresentMode", v != null ? v : "fifo"); }

    public String getRendererDriverId() {
        String v = getExtra("rendererDriverId", null);
        return v != null ? v : container.getRendererDriverId();
    }
    public void setRendererDriverId(String v) { putExtra("rendererDriverId", v != null ? v : ""); }

    public int getRendererFilterMode() {
        String v = getExtra("rendererFilterMode", null);
        try { return v != null && !v.isEmpty() ? Integer.parseInt(v) : container.getRendererFilterMode(); }
        catch (NumberFormatException e) { return 0; }
    }
    public void setRendererFilterMode(int v) { putExtra("rendererFilterMode", String.valueOf(v)); }

    public int getLsfgMultiplier() {
        String value = getExtra("lsfgMultiplier", null);
        try {
            return value != null && !value.isEmpty()
                    ? (Integer.parseInt(value) < 2 ? 0 : Math.max(2, Math.min(4, Integer.parseInt(value))))
                    : container.getLsfgMultiplier();
        } catch (NumberFormatException e) {
            return container.getLsfgMultiplier();
        }
    }

    public void setLsfgMultiplier(int multiplier) {
        putExtra("lsfgMultiplier", String.valueOf(multiplier < 2 ? 0 : Math.max(2, Math.min(4, multiplier))));
    }

    public int getLsfgTargetRate() {
        String value = getExtra("lsfgTargetRate", null);
        try {
            return value != null && !value.isEmpty()
                    ? Math.max(0, Math.min(1000, Integer.parseInt(value)))
                    : container.getLsfgTargetRate();
        } catch (NumberFormatException e) {
            return container.getLsfgTargetRate();
        }
    }

    public void setLsfgTargetRate(int hz) {
        putExtra("lsfgTargetRate", String.valueOf(Math.max(0, Math.min(1000, hz))));
    }

    public int getLsfgTargetMemo() {
        String value = getExtra("lsfgTargetMemo", null);
        try {
            return value != null && !value.isEmpty()
                    ? Math.max(0, Math.min(1000, Integer.parseInt(value)))
                    : container.getLsfgTargetMemo();
        } catch (NumberFormatException e) {
            return container.getLsfgTargetMemo();
        }
    }

    public void setLsfgTargetMemo(int hz) {
        putExtra("lsfgTargetMemo", String.valueOf(Math.max(0, Math.min(1000, hz))));
    }

    public float getLsfgFlowScale() {
        String value = getExtra("lsfgFlowScale", null);
        try {
            return value != null && !value.isEmpty()
                    ? Math.max(0.25f, Math.min(1.0f, Float.parseFloat(value)))
                    : container.getLsfgFlowScale();
        } catch (NumberFormatException e) {
            return container.getLsfgFlowScale();
        }
    }

    public void setLsfgFlowScale(float flowScale) {
        float clamped = Math.max(0.25f, Math.min(1.0f, flowScale));
        putExtra("lsfgFlowScale", String.format(java.util.Locale.US, "%.2f", clamped));
    }
}
