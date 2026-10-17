package com.winlator.cmod;

import com.winlator.cmod.ui.UiStrings;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.Toast;
import com.winlator.cmod.ui.toast.WinToast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.OpenGLDriverDefaults;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.ui.onboarding.OnboardingComposeController;
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OnboardingActivity extends AppCompatActivity implements ComponentCatalogController.Host {
    public static final String PREF_ONBOARDING_COMPLETE = "winz_onboarding_complete";
    public static final String EXTRA_COMPONENT_MANAGER = "component_manager";
    public static final String EXTRA_AUTO_INSTALL_TYPE = "auto_install_type";
    public static final String EXTRA_AUTO_INSTALL_VERSION = "auto_install_version";
    public static final String EXTRA_AUTO_INSTALL_VERSION_CODE = "auto_install_version_code";

    private static final String PREF_INITIAL_WINE = "winz_initial_wine_version";
    private static final int REQUEST_STORAGE = 820;
    private static final int REQUEST_NOTIFICATIONS = 821;
    private static final int REQUEST_LOCAL_COMPONENT = 822;
    private static final int REQUEST_ALL_FILES = 823;

    private final ComponentCatalogController controller = new ComponentCatalogController(this);

    private SharedPreferences preferences;
    private ContentsManager contentsManager;
    private OnboardingComposeController composeController;
    private boolean componentManagerMode;
    private boolean finishing;
    private String selectedInitialWine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.applyOrientationMode(this);

        componentManagerMode = getIntent().getBooleanExtra(EXTRA_COMPONENT_MANAGER, false);
        String autoInstallType = getIntent().getStringExtra(EXTRA_AUTO_INSTALL_TYPE);
        String autoInstallVersion = getIntent().getStringExtra(EXTRA_AUTO_INSTALL_VERSION);
        int autoInstallVersionCode = getIntent().getIntExtra(EXTRA_AUTO_INSTALL_VERSION_CODE, Integer.MIN_VALUE);

        controller.initialize(autoInstallType, autoInstallVersion, autoInstallVersionCode);
        contentsManager = controller.getContentsManager();
        preferences = controller.getPreferences();
        selectedInitialWine = preferences.getString(PREF_INITIAL_WINE, "");

        composeController = OnboardingComposeHost.attach(
                this,
                controller.isCoreReady(),
                controller.getCoreProgress(),
                WineRuntimeGuard.isBundledMainInstalled(this),
                WineRuntimeGuard.isInUse(this, WineInfo.MAIN_WINE_VERSION.identifier()),
                componentManagerMode,
                controller.createCallbacks(new ComponentCatalogController.ExtraCallbacks() {
                    @Override
                    public void onBrowseLocal() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_LOCAL_COMPONENT);
                    }

                    @Override
                    public void onRuntimeSelected(String runtimeIdentifier) {
                        selectedInitialWine = runtimeIdentifier;
                        preferences.edit().putString(PREF_INITIAL_WINE, runtimeIdentifier).apply();
                    }

                    @Override
                    public void onRequestPermissions() {
                        requestStoragePermission();
                    }
                })
        );
        controller.attachComposeController(composeController);
        controller.start();
    }

    @Override
    protected void onDestroy() {
        controller.shutdown();
        super.onDestroy();
    }

    // ComponentCatalogController.Host
    @Override
    public Context context() {
        return this;
    }

    @Override
    public AppCompatActivity hostActivity() {
        return this;
    }

    @Override
    public boolean isAlive() {
        return !isFinishing();
    }

    @Override
    public void runOnUi(Runnable action) {
        runOnUiThread(action);
    }

    @Override
    public void close() {
        finish();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_COMPONENT && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            controller.handleLocalPackagePicked(data.getData());
        } else if (requestCode == REQUEST_ALL_FILES) {
            continuePermissionFlow();
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        continuePermissionFlow();
    }

    private void continuePermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_ALL_FILES);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        finishOnboarding();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) continuePermissionFlow();
            else WinToast.show(this, UiStrings.get(R.string.ui_storage_access_required), Toast.LENGTH_LONG);
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            finishOnboarding();
        }
    }

    private void finishOnboarding() {
        if (finishing) return;
        if (componentManagerMode) {
            finish();
            return;
        }
        finishing = true;
        contentsManager.syncContents();
        ContainerManager manager = new ContainerManager(this);
        if (!manager.getContainers().isEmpty()) {
            enterMainApp();
            return;
        }

        String runtime = resolveSelectedRuntime();
        if (runtime == null) {
            finishing = false;
            WinToast.show(this, UiStrings.get(R.string.ui_install_select_wine_first), Toast.LENGTH_LONG);
            return;
        }

        WineInfo wineInfo = WineInfo.fromIdentifier(this, contentsManager, runtime);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) {
            finishing = false;
            WinToast.show(this, UiStrings.get(R.string.ui_the_selected_wine_proton_layer_is_no_long), Toast.LENGTH_LONG);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", "Container-" + manager.getNextContainerId());
            data.put("screenSize", Container.defaultScreenSizeFor(this));
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            data.put("rendererPresentMode", "fifo");
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("emulator", wineInfo.isArm64EC() ? "FEXCore" : "Box64");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("box64Version", wineInfo.isArm64EC() ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
            data.put("box64Preset", Box64Preset.COMPATIBILITY);
            data.put("fexcoreVersion", DefaultVersion.FEXCORE);
            data.put("fexcorePreset", FEXCorePreset.INTERMEDIATE);
            data.put("wineVersion", runtime);
            OpenGLDriverDefaults.initialize(this, data);

            manager.createContainerAsync(data, contentsManager, created -> {
                if (created == null) {
                    finishing = false;
                    WinToast.show(this, UiStrings.get(R.string.ui_unable_to_create_the_first_container), Toast.LENGTH_LONG);
                } else {
                    enterMainApp();
                }
            });
        } catch (Exception error) {
            finishing = false;
            WinToast.show(this, UiStrings.get(R.string.ui_unable_to_prepare_the_first_container), Toast.LENGTH_LONG);
        }
    }

    private String resolveSelectedRuntime() {
        if (selectedInitialWine != null && !selectedInitialWine.isEmpty() && runtimeInstalled(selectedInitialWine)) {
            return selectedInitialWine;
        }
        if (WineRuntimeGuard.isBundledMainInstalled(this)) return WineInfo.MAIN_WINE_VERSION.identifier();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            String typeName = ComponentCatalogController.displayType(type);
            if (!typeName.equals("Wine") && !typeName.equals("Proton")) continue;
            for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
                return ContentsManager.getEntryName(profile);
            }
        }
        for (String identifier : ProtonPackageManager.getInstalledIdentifiers(this)) {
            if (!WineInfo.MAIN_WINE_VERSION.identifier().equals(identifier)) return identifier;
        }
        return null;
    }

    private boolean runtimeInstalled(String identifier) {
        if (WineInfo.MAIN_WINE_VERSION.identifier().equals(identifier)) {
            return WineRuntimeGuard.isBundledMainInstalled(this);
        }
        if (ProtonPackageManager.isInstalled(this, identifier)) return true;
        if (ProtonPackageManager.isKnownPackage(identifier)) return false;
        ContentProfile profile = contentsManager.getProfileByEntryName(identifier);
        return profile != null && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON);
    }

    private void enterMainApp() {
        preferences.edit()
                .putBoolean(PREF_ONBOARDING_COMPLETE, true)
                .putString(PREF_INITIAL_WINE, selectedInitialWine == null ? "" : selectedInitialWine)
                .apply();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }

}
