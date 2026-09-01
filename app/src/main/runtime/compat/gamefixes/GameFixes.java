package com.winlator.cmod.runtime.compat.gamefixes;

import android.util.Log;
import com.winlator.cmod.runtime.compat.SteamBridge;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.wine.WineRegistryEditor;
import com.winlator.cmod.runtime.wine.WineUtils;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class GameFixes {
  private static final String TAG = "GameFixes";
  private static final String INSTALL_PATH_PLACEHOLDER = "<InstallPath>";
  private static final Map<String, Fix> STEAM_FIXES;

  static {
    HashMap<String, Fix> steamFixes = new HashMap<>();
    steamFixes.put(
        "22300",
        new RegistryKeyFix(
            "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
            Collections.singletonMap("Installed Path", INSTALL_PATH_PLACEHOLDER)));
    steamFixes.put(
        "22330",
        new RegistryKeyFix(
            "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
            Collections.singletonMap("Installed Path", INSTALL_PATH_PLACEHOLDER)));
    steamFixes.put(
        "22380",
        new RegistryKeyFix(
            "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
            Collections.singletonMap("Installed Path", INSTALL_PATH_PLACEHOLDER)));
    STEAM_FIXES = Collections.unmodifiableMap(steamFixes);
  }

  private GameFixes() {}

  public static void applyForLaunch(Container container, Shortcut shortcut) {
    if (container == null || shortcut == null) return;
    String gameSource = shortcut.getExtra("game_source");

    if ("STEAM".equals(gameSource)) {
      applySteamFixes(container, shortcut);
    }
  }

  private static void applySteamFixes(Container container, Shortcut shortcut) {
    String appId = shortcut.getExtra("app_id");
    if (appId == null || appId.isEmpty()) return;

    Fix fix = STEAM_FIXES.get(appId);
    if (fix == null) return;

    String installPath = SteamBridge.getAppDirPath(Integer.parseInt(appId));
    if (installPath == null || installPath.isEmpty() || !new File(installPath).exists()) {
      Log.d(TAG, "Skipping Steam fix for appId " + appId + " because install path is unavailable");
      return;
    }

    File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
    String installPathWindows = WineUtils.getDosPath(container, installPath);
    applyFix(
        fix,
        container,
        appId,
        installPath,
        installPathWindows != null ? installPathWindows : "D:\\",
        systemRegFile);
  }

  private static void applyFix(
      Fix fix,
      Container container,
      String gameId,
      String installPath,
      String installPathWindows,
      File systemRegFile) {
    if (fix.requiresSystemReg() && (systemRegFile == null || !systemRegFile.isFile())) {
      if (systemRegFile != null) {
        Log.w(
            TAG,
            "system.reg missing at " + systemRegFile.getAbsolutePath() + " for game " + gameId);
      }
      return;
    }
    try {
      fix.apply(container, gameId, installPath, installPathWindows, systemRegFile);
    } catch (Exception e) {
      Log.e(TAG, "Failed to apply fix for game " + gameId, e);
    }
  }



  private interface Fix {
    boolean requiresSystemReg();

    void apply(
        Container container,
        String gameId,
        String installPath,
        String installPathWindows,
        File systemRegFile)
        throws Exception;
  }

  private static final class RegistryKeyFix implements Fix {
    private final String registryKey;
    private final Map<String, String> defaultValues;

    private RegistryKeyFix(String registryKey, Map<String, String> defaultValues) {
      this.registryKey = registryKey;
      this.defaultValues = defaultValues;
    }

    @Override
    public boolean requiresSystemReg() {
      return true;
    }

    @Override
    public void apply(
        Container container,
        String gameId,
        String installPath,
        String installPathWindows,
        File systemRegFile) {
      try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
        registryEditor.setCreateKeyIfNotExist(true);
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
          String existingValue = registryEditor.getStringValue(registryKey, entry.getKey(), null);
          if (existingValue != null && !existingValue.isEmpty()) continue;

          String value =
              INSTALL_PATH_PLACEHOLDER.equals(entry.getValue())
                  ? installPathWindows
                  : entry.getValue();
          registryEditor.setStringValue(registryKey, entry.getKey(), value);
          Log.d(
              TAG,
              "Applied registry fix for game "
                  + gameId
                  + ": "
                  + registryKey
                  + " -> "
                  + entry.getKey());
        }
      } catch (Exception e) {
        Log.e(TAG, "Failed to apply registry fix for game " + gameId, e);
      }
    }
  }
}
