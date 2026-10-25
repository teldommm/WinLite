package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The one list of component versions that ship inside the APK (assets/dxwrapper, fexcore, box64,
 * wowbox64), by the type names the component manager uses. The version pickers start from it and
 * the component manager uses it to tell that a catalog row is already built in, so a version is
 * never offered for download next to the copy the app already has.
 *
 * To bundle another version: add its archive to the assets and its name here (or to the arrays it
 * is read from).
 */
public final class BundledComponents {
    private BundledComponents() {}

    /** Version names bundled for {@code displayType} ("DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64"), best first. */
    public static List<String> names(Context context, String displayType) {
        switch (displayType) {
            case "DXVK":
                return Arrays.asList(context.getResources().getStringArray(R.array.dxvk_version_entries));
            case "VKD3D": {
                ArrayList<String> out = new ArrayList<>();
                for (String name : context.getResources().getStringArray(R.array.vkd3d_version_entries)) {
                    if (!name.equalsIgnoreCase("None")) out.add(name);
                }
                return out;
            }
            case "FEXCore":
                return Arrays.asList(DefaultVersion.FEXCORE_BUNDLED);
            case "Box64":
                return Collections.singletonList(DefaultVersion.BOX64);
            case "WOWBox64":
                return Collections.singletonList(DefaultVersion.WOWBOX64);
            default:
                return Collections.emptyList();
        }
    }

    /** True when {@code versionName} of {@code displayType} is shipped inside the APK (names compared ignoring case). */
    public static boolean contains(Context context, String displayType, String versionName) {
        if (versionName == null) return false;
        for (String name : names(context, displayType)) {
            if (name.equalsIgnoreCase(versionName)) return true;
        }
        return false;
    }
}
