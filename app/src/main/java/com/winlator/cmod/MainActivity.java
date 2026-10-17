package com.winlator.cmod;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.ui.shell.MainShellController;
import com.winlator.cmod.ui.shell.MainShellHost;
import com.winlator.cmod.ui.shell.ShellDetail;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import com.winlator.cmod.services.NotificationService;

import java.io.File;
import com.winlator.cmod.core.AppDefaults;

public class MainActivity extends AppCompatActivity {
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final int PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 500;
    public static final int PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE = 501;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public static final byte OPEN_IMAGE_REQUEST_CODE = 5;
    public static final String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static final int NOTIFICATION_ID = 100;
    private static final String ORIENTATION_MODE_AUTO = "auto";
    private static final String ORIENTATION_MODE_VERTICAL = "vertical";
    private static final String ORIENTATION_MODE_HORIZONTAL = "horizontal";
    private MainShellController mainShell;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Intent notificationService;
    private SharedPreferences sharedPreferences;
    private ContainerManager containerManager;
    private boolean isDarkMode;
    private boolean orientationLocked;
    private String orientationMode = ORIENTATION_MODE_AUTO;

    private void createNotificationChannel() {
        String name = "WinLite";
        String description = "WinLite XServer Messages";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        orientationMode = sharedPreferences.getString("orientation_mode", ORIENTATION_MODE_AUTO);
        orientationLocked = sharedPreferences.getBoolean("orientation_locked", false);

        // Persist the default value on first run so all other components
        // (dialogs, fragments) read the correct value instead of their own default
        if (!sharedPreferences.contains("dark_mode")) {
            sharedPreferences.edit().putBoolean("dark_mode", true).apply();
        }

        isDarkMode = sharedPreferences.getBoolean("dark_mode", true);

        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            setTheme(R.style.AppTheme_Dark);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            setTheme(R.style.AppTheme);
        }

        super.onCreate(savedInstanceState);
        applyOrientationMode();
        applyImmersiveMode();

        if (!sharedPreferences.getBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        notificationService = new Intent(this, NotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
            createNotificationChannel();

        setContentView(R.layout.main_activity);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        File winlatorDir = new File(AppDefaults.DEFAULT_WINLATOR_PATH);
        if (!winlatorDir.exists())
            winlatorDir.mkdirs();

        containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        int initialTab;
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            initialTab = R.id.main_menu_input_controls;
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            if (MainShellController.isTab(selectedMenuItemId)) {
                initialTab = selectedMenuItemId;
            } else {
                containerManager.loadShortcuts();
                initialTab = R.id.main_menu_shortcuts;
            }
        }

        ComposeView shellView = findViewById(R.id.MainShellCompose);
        mainShell = MainShellHost.attach(
                shellView,
                initialTab,
                selectedProfileId,
                editInputControls,
                this::navigateToMainDestination
        );

        if (!editInputControls) {
            if (!ImageFsInstaller.installIfNeeded(this, () -> requestAppPermissions())) {
                // Keep-alive doesn't depend on the notification permission: a foreground
                // service runs without it (its notification is just hidden on Android 13+).
                NotificationService.startLibrary(this);
            }
        }
    }

    private void showAllFilesAccessDialog() {
        ThemedAlertHost.confirm(
                this,
                "All Files Access Required",
                "In order to grant access to additional storage devices such as USB storage device, the All Files Access permission must be granted. Press Okay to grant All Files Access in your Android Settings.",
                "Okay",
                () -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                NotificationService.startLibrary(this);
        } else if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                requestAppPermissions();
            else
                finish();
        }
    }

    private void requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasManageStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        boolean hasPostNotificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
            return;
        }

        if (!hasPostNotificationPermission) {
            createNotificationChannel();
            String[] permissions = new String[]{Manifest.permission.POST_NOTIFICATIONS};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE);
        }

        if (!hasManageStoragePermission) {
            showAllFilesAccessDialog();
        }

        // Also reached after a first-run ImageFs install, where onCreate didn't start it.
        NotificationService.startLibrary(this);
    }

    public boolean isOrientationLocked() {
        return orientationLocked;
    }

    public boolean isVerticalModeEnabled() {
        return ORIENTATION_MODE_VERTICAL.equals(orientationMode);
    }

    public boolean isHorizontalModeEnabled() {
        return ORIENTATION_MODE_HORIZONTAL.equals(orientationMode);
    }

    public boolean toggleOrientationLock() {
        orientationLocked = !orientationLocked;
        sharedPreferences.edit().putBoolean("orientation_locked", orientationLocked).apply();
        applyOrientationMode();
        return orientationLocked;
    }

    public boolean toggleVerticalMode() {
        orientationMode = isVerticalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_VERTICAL;
        sharedPreferences.edit().putString("orientation_mode", orientationMode).apply();
        applyOrientationMode();
        return isVerticalModeEnabled();
    }

    public boolean toggleHorizontalMode() {
        orientationMode = isHorizontalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_HORIZONTAL;
        sharedPreferences.edit().putString("orientation_mode", orientationMode).apply();
        applyOrientationMode();
        return isHorizontalModeEnabled();
    }

    // Delegates to AppUtils so every other activity reachable from here (Components,
    // Containers, ...) can apply the same persisted preference via
    // AppUtils.applyOrientationMode(activity) instead of just inheriting manifest
    // screenOrientation="sensor" and potentially landing on a different orientation.
    private void applyOrientationMode() {
        AppUtils.applyOrientationMode(this);
        invalidateOptionsMenu();
    }

    // Single entry point for main destinations: bottom nav, landscape headers, Library's "Add"
    // (File Manager), and the About entry. Closes any open detail screen, then lets the shell
    // switch tabs (no-op if the tab is already selected — tabs persist, nothing to reload).
    public void navigateToMainDestination(int menuItemId) {
        if (menuItemId == R.id.main_menu_about) {
            showAboutDialog();
            return;
        }
        if (!MainShellController.isTab(menuItemId) || mainShell == null) return;
        mainShell.closeAllDetails();
        mainShell.select(menuItemId);
    }

    // Detail screens (drawn by MainShell above the tabs; system back pops them).
    public void openGameDetail(String shortcutPath) {
        openGameDetail(shortcutPath, null);
    }

    // preloaded: the Library's in-memory Shortcut for this game (lets the page skip a disk scan).
    public void openGameDetail(String shortcutPath, com.winlator.cmod.container.Shortcut preloaded) {
        if (mainShell != null) mainShell.pushDetail(new ShellDetail.GameDetail(shortcutPath, preloaded));
    }

    public void openContainersSettings() {
        if (mainShell != null) mainShell.pushDetail(ShellDetail.Containers.INSTANCE);
    }

    public void openComponentManager() {
        if (mainShell != null) mainShell.pushDetail(ShellDetail.ComponentManager.INSTANCE);
    }

    // Asks the Library tab to reload (e.g. after a detail screen changed a shortcut).
    public void refreshLibrary() {
        if (mainShell != null) mainShell.requestRefresh(R.id.main_menu_shortcuts);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    public void showAboutDialog() {
        com.winlator.cmod.ui.AboutDialogHost.show(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 1280);
            if (bitmap == null) return;
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(this);
            ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
        }
    }
}
