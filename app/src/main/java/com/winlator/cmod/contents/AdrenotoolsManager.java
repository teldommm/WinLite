package com.winlator.cmod.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.AppDefaults;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.contentdialog.GraphicsDriverConfig;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
    
    public String getAssetPath(String adrenotoolsDriverId) {
        return "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }
    
    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfig.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(GraphicsDriverConfig.toGraphicsDriverConfig(config));
                container.saveData();
            }     
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfig.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfig.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        
        File[] files = adrenotoolsContentDir.listFiles();
        if (files == null) return driversList;
        for (File f : files) {
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    public ArrayList<String> enumerateRendererDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        String[] bundledDrivers = mContext.getResources().getStringArray(
                com.winlator.cmod.R.array.wrapper_graphics_driver_version_entries);

        for (String id : bundledDrivers) {
            if (!id.equalsIgnoreCase("System") && isFromResources(id) && !driversList.contains(id))
                driversList.add(id);
        }
        // The folder listing has no defined order, so sort the installed drivers the same way the
        // Components screen does: newest first (R10 before R9), ties by id.
        ArrayList<String> installed = enumarateInstalledDrivers();
        final java.util.HashMap<String, String> labels = new java.util.HashMap<>();
        for (String id : installed) labels.put(id, (getDriverName(id) + " " + getDriverVersion(id)).trim());
        java.util.Collections.sort(installed, (left, right) -> {
            int byLabel = com.winlator.cmod.core.VersionOrder.compareNatural(labels.get(right), labels.get(left));
            return byLabel != 0 ? byLabel : left.compareTo(right);
        });
        for (String id : installed) {
            if (!driversList.contains(id)) driversList.add(id);
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public boolean extractDriverFromResources(String adrenotoolsDriverId, OnExtractFileListener listener) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst, listener);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public String installDriver(Uri driverUri) {
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) FileUtils.delete(tmpDir);
        tmpDir.mkdirs();
        String name = "";

        // Entries are resolved against tmpDir and rejected if they escape it ("../" names — the
        // zip-slip case, which used to be able to write anywhere in the app's storage).
        // Directory entries and nested paths are created instead of failing the whole install.
        try (InputStream is = mContext.getContentResolver().openInputStream(driverUri)) {
            if (is == null) throw new IOException("Unable to open " + driverUri);
            try (ZipInputStream zis = new ZipInputStream(is)) {
                String root = tmpDir.getCanonicalPath() + File.separator;
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File dstFile = new File(tmpDir, entry.getName());
                    if (!dstFile.getCanonicalPath().startsWith(root)) {
                        throw new IOException("Entry outside of the driver directory: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        dstFile.mkdirs();
                        continue;
                    }
                    File parent = dstFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        catch (Exception e) {
            Log.d("AdrenotoolsManager", "Failed to install driver: " + e.getMessage());
            FileUtils.delete(tmpDir);
            return "";
        }

        if (new File(tmpDir, "meta.json").exists()) {
            name = getDriverName(tmpDir.getName());
            File dst = new File(adrenotoolsContentDir, name);
            if (!dst.exists() && !name.equals(""))
                tmpDir.renameTo(dst);
            else {
                name = "";
                FileUtils.delete(tmpDir);
            }
        }
        else {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            FileUtils.delete(tmpDir);
        }

        return name;
    }
    
    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        boolean isFromResources = isFromResources(adrenotoolsDriverId);

        if (isFromResources || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = getDriverPath(adrenotoolsDriverId);

            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));

                File winlatorDir = new File(AppDefaults.DEFAULT_WINLATOR_PATH);
                File qglConfig = new File(winlatorDir, "qgl_config.txt");
                if (qglConfig.exists())
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
            }
        }
    }
 }
