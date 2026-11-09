package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public abstract class AppUtils {

    public static void keepScreenOn(Activity activity) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Reads the same "orientation_mode"/"orientation_locked" prefs MainActivity's own
    // vertical/horizontal toggle writes, and applies them the same way it does. MainActivity
    // used to be the only place that ever called setRequestedOrientation for this — every
    // other screen just fell back on its manifest screenOrientation="sensor" declaration,
    // which (depending on the device's own rotation-lock setting) could mean an activity
    // opened from a landscape-forced MainActivity would revert to portrait. Called from
    // any activity that should honor the app's orientation preference, not just MainActivity.
    public static void applyOrientationMode(Activity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        String orientationMode = preferences.getString("orientation_mode", "auto");
        boolean orientationLocked = preferences.getBoolean("orientation_locked", false);
        switch (orientationMode) {
            case "vertical":
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case "horizontal":
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                activity.setRequestedOrientation(orientationLocked
                        ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
        }
    }

    public static String getArchName() {
        for (String arch : Build.SUPPORTED_ABIS) {
            switch (arch) {
                case "arm64-v8a": return "arm64";
                case "armeabi-v7a": return "armhf";
                case "x86_64": return "x86_64";
                case "x86": return "x86";
            }
        }
        return "armhf";
    }

    public static void restartApplication(Context context) {
        restartApplication(context, 0);
    }

    public static void restartApplication(Context context, int selectedMenuItemId) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Intent mainIntent = Intent.makeRestartActivityTask(intent.getComponent());
        if (selectedMenuItemId > 0) mainIntent.putExtra("selected_menu_item_id", selectedMenuItemId);
        context.startActivity(mainIntent);
        Runtime.getRuntime().exit(0);
    }

    public static void showKeyboard(AppCompatActivity activity) {
        final InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            activity.getWindow().getDecorView().postDelayed(() -> imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0), 500L);
        }
        else imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    public static void hideSystemUI(final Activity activity) {
        Window window = activity.getWindow();
        final View decorView = window.getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            final WindowInsetsController insetsController = decorView.getWindowInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else {
            final int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

            decorView.setSystemUiVisibility(flags);
            decorView.setOnSystemUiVisibilityChangeListener((visibility) -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) decorView.setSystemUiVisibility(flags);
            });
        }
    }

    public static int getScreenWidth() {
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    public static int getScreenHeight() {
        return Resources.getSystem().getDisplayMetrics().heightPixels;
    }

    public static int getVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionCode;
        }
        catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static void observeSoftKeyboardVisibility(View rootView, Callback<Boolean> callback) {
        final boolean[] visible = {false};
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect rect = new Rect();
            rootView.getWindowVisibleDisplayFrame(rect);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - rect.bottom;

            if (keypadHeight > screenHeight * 0.15f) {
                if (!visible[0]) {
                    visible[0] = true;
                    callback.call(true);
                }
            }
            else {
                if (visible[0]) {
                    visible[0] = false;
                    callback.call(false);
                }
            }
        });
    }

    public static void findViewsWithClass(ViewGroup parent, Class viewClass, ArrayList<View> outViews) {
        for (int i = 0, childCount = parent.getChildCount(); i < childCount; i++) {
            View child = parent.getChildAt(i);
            Class _class = child.getClass();
            if (_class == viewClass || _class.getSuperclass() == viewClass) {
                outViews.add(child);
            }
            else if (child instanceof ViewGroup) {
                findViewsWithClass((ViewGroup)child, viewClass, outViews);
            }
        }
    }

    public static String getNativeLibDir(Context context) {
        return context.getApplicationInfo().nativeLibraryDir;
    }

}
