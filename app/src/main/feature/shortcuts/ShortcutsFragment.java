package com.winlator.cmod.feature.shortcuts;

import static androidx.core.content.ContextCompat.getSystemService;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.Nullable;
import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Home-screen "pinned shortcut" utilities (Android's {@link ShortcutManager}), used when a
 * game is pinned to the device's home screen. Not a UI screen — the in-app shortcuts list
 * this class used to host (Fragment + ComposeView) was superseded by the library grid in
 * UnifiedActivityHub and has been removed; only these static helpers are still called from
 * ShortcutSettingsComposeDialog and the library screens.
 */
public class ShortcutsFragment {
  public enum PinShortcutResult {
    FAILED,
    REQUESTED_NEW,
    REUSED_EXISTING
  }

  private ShortcutsFragment() {}

  public static ArrayList<String> buildPinnedShortcutIds(
      int containerId, String uuid, String shortcutPath) {
    LinkedHashSet<String> shortcutIds = new LinkedHashSet<>();
    if (uuid != null && !uuid.isEmpty()) {
      shortcutIds.add(uuid); // Legacy pinned shortcut id.
      if (shortcutPath != null && !shortcutPath.isEmpty() && containerId > 0) {
        int shortcutPathHash = shortcutPath.hashCode();
        shortcutIds.add(
            "shortcut_"
                + containerId
                + "_"
                + uuid
                + "_"
                + Integer.toUnsignedString(shortcutPathHash, 16));
      }
    }
    return new ArrayList<>(shortcutIds);
  }

  public static Intent buildShortcutLaunchIntent(
      Context context, int containerId, String shortcutPath, String shortcutName, String uuid) {
    Intent intent = new Intent(context, XServerDisplayActivity.class);
    intent.setAction(Intent.ACTION_VIEW);
    if (shortcutPath != null && !shortcutPath.isEmpty()) {
      int shortcutPathHash = shortcutPath.hashCode();
      Uri launchData =
          new Uri.Builder()
              .scheme("winlite")
              .authority(BuildConfig.APPLICATION_ID)
              .appendPath("shortcut")
              .appendQueryParameter("uuid", uuid)
              .appendQueryParameter("container", String.valueOf(containerId))
              .appendQueryParameter("hash", String.valueOf(shortcutPathHash))
              .build();
      intent.setData(launchData);
      intent.putExtra("shortcut_path_hash", shortcutPathHash);
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.putExtra("container_id", containerId);
    intent.putExtra("shortcut_path", shortcutPath);
    intent.putExtra("shortcut_name", shortcutName);
    intent.putExtra("shortcut_uuid", uuid);
    intent.putExtra(XServerDisplayActivity.EXTRA_LAUNCHED_FROM_PINNED_SHORTCUT, true);
    return intent;
  }

  public static PinShortcutResult pinOrUpdateShortcut(
      ShortcutManager shortcutManager,
      ShortcutInfo shortcutInfo,
      List<String> shortcutIds,
      @Nullable IntentSender callback) {
    if (shortcutManager == null
        || shortcutInfo == null
        || shortcutIds == null
        || shortcutIds.isEmpty()) return PinShortcutResult.FAILED;

    try {
      for (ShortcutInfo pinnedShortcut : shortcutManager.getPinnedShortcuts()) {
        if (!shortcutIds.contains(pinnedShortcut.getId())) continue;

        shortcutManager.updateShortcuts(Collections.singletonList(shortcutInfo));
        try {
          shortcutManager.enableShortcuts(Collections.singletonList(pinnedShortcut.getId()));
        } catch (Exception ignored) {
        }
        return PinShortcutResult.REUSED_EXISTING;
      }
    } catch (Exception ignored) {
    }

    try {
      return shortcutManager.requestPinShortcut(shortcutInfo, callback)
          ? PinShortcutResult.REQUESTED_NEW
          : PinShortcutResult.FAILED;
    } catch (IllegalArgumentException e) {
      try {
        shortcutManager.updateShortcuts(Collections.singletonList(shortcutInfo));
        shortcutManager.enableShortcuts(Collections.singletonList(shortcutInfo.getId()));
        return PinShortcutResult.REUSED_EXISTING;
      } catch (Exception ignored) {
      }
    } catch (Exception ignored) {
    }

    return PinShortcutResult.FAILED;
  }

  public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
    ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
    if (shortcutManager == null
        || shortcut == null
        || shortcut.container == null
        || shortcut.file == null) return;

    ArrayList<String> shortcutIds =
        buildPinnedShortcutIds(
            shortcut.container.id, shortcut.getExtra("uuid"), shortcut.file.getAbsolutePath());
    if (shortcutIds.isEmpty()) return;

    try {
      shortcutManager.disableShortcuts(
          shortcutIds, context.getString(com.winlator.cmod.R.string.shortcuts_list_not_available));
    } catch (Exception ignored) {
    }

    try {
      shortcutManager.removeDynamicShortcuts(shortcutIds);
    } catch (Exception ignored) {
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        shortcutManager.removeLongLivedShortcuts(shortcutIds);
      } catch (Exception ignored) {
      }
    }
  }
}
