package com.winlator.cmod;

import com.winlator.cmod.ui.UiStrings;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.winlator.cmod.ui.toast.WinToast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.inputcontrols.ControlElementBindingRow;
import com.winlator.cmod.ui.inputcontrols.ControlElementIcon;
import com.winlator.cmod.ui.inputcontrols.ControlElementIconTint;
import com.winlator.cmod.ui.inputcontrols.ControlElementSettingsCallbacks;
import com.winlator.cmod.ui.inputcontrols.ControlElementSettingsModel;
import com.winlator.cmod.ui.inputcontrols.ControlsEditorActions;
import com.winlator.cmod.ui.inputcontrols.ControlsEditorOverlay;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.InputControlsView;

public class ControlsEditorActivity extends AppCompatActivity {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private ControlElement pendingIconElement = null;
    private int pendingBuiltinOverrideId = -1;
    private static final int PICK_ICON_REQUEST = 7001;
    private static final String BUILTIN_ICON_PREFIX = "builtin:";
    // Built-in icon 0 is the "no icon" tile (assets/inputcontrols/icons/0.png): an element with
    // iconId 0 shows its text instead of a picture.
    private static final byte NO_ICON_ID = 0;
    private static final String CUSTOM_ICON_PREFIX = "path:";

    private ControlsEditorOverlay overlay;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Custom Text is saved this long after the last keystroke instead of on every letter
    // (profile.save() rewrites the whole profile JSON).
    private static final long TEXT_SAVE_DELAY_MS = 400;
    private boolean textSavePending = false;
    private final Runnable textSaveRunnable = () -> {
        textSavePending = false;
        profile.save();
        endEdit();
    };
    // One settings "edit" = one undo step: opened by the first change (snapshot taken), closed
    // when it is committed. A slider drag or a burst of typing is therefore undone as a whole.
    private boolean editOpen = false;
    // Decoded icon bitmaps, built once (see getIconCache()); rebuilt only after an icon file
    // is added or overridden.
    private List<CachedIcon> iconCache;

    private static File getCustomIconsDir() {
        return new File(Environment.getExternalStorageDirectory(), "winlator/custom_icons");
    }

    static File getBuiltinOverridePath(int iconId) {
        return new File(getCustomIconsDir(), "override_" + iconId + ".png");
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(1.0f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        if (profile == null) {
            Log.e("ControlsEditor", "Profile not found for id=" + getIntent().getIntExtra("profile_id", 0));
            WinToast.show(this, UiStrings.get(R.string.ui_no_profile_selected), Toast.LENGTH_SHORT);
            finish();
            return;
        }
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        ArrayList<Integer> schemeColors = new ArrayList<>();
        for (int color : PALETTE_COLORS) schemeColors.add(color);

        // Same list (and order) as the Input Controls screen shows.
        ArrayList<Integer> profileIds = new ArrayList<>();
        ArrayList<String> profileNames = new ArrayList<>();
        for (ControlsProfile p : new InputControlsManager(this).getProfiles()) {
            profileIds.add(p.id);
            profileNames.add(p.getName());
        }
        if (!profileIds.contains(profile.id)) {
            profileIds.add(0, profile.id);
            profileNames.add(0, profile.getName());
        }

        overlay = new ControlsEditorOverlay(
                container,
                inputControlsView,
                profileIds,
                profileNames,
                profile.id,
                schemeColors,
                profile.getThemeColor(),
                new ControlsEditorActions() {
                    @Override
                    public void onAddElement() {
                        flushPendingTextSave();
                        if (!inputControlsView.addElement()) {
                            WinToast.show(ControlsEditorActivity.this, UiStrings.get(R.string.ui_no_profile_selected), Toast.LENGTH_SHORT);
                        }
                    }

                    @Override
                    public void onRemoveElement() {
                        flushPendingTextSave();
                        if (!inputControlsView.removeElement()) {
                            WinToast.show(ControlsEditorActivity.this, UiStrings.get(R.string.ui_no_control_element_selected), Toast.LENGTH_SHORT);
                        }
                    }

                    @Override
                    public void onDuplicateElement() {
                        flushPendingTextSave();
                        if (!inputControlsView.duplicateElement()) {
                            WinToast.show(ControlsEditorActivity.this, UiStrings.get(R.string.ui_no_control_element_selected), Toast.LENGTH_SHORT);
                        }
                    }

                    @Override
                    public void onUndo() {
                        flushPendingTextSave();
                        inputControlsView.undo();
                    }

                    @Override
                    public void onOpenSettings() {
                        ControlElement element = inputControlsView.getSelectedElement();
                        if (element != null) {
                            overlay.showSettings(buildElementSettingsModel(element));
                        } else {
                            WinToast.show(ControlsEditorActivity.this, UiStrings.get(R.string.ui_no_control_element_selected), Toast.LENGTH_SHORT);
                        }
                    }

                    @Override
                    public void onSelectProfile(int profileId) {
                        switchProfile(profileId);
                    }

                    @Override
                    public void onSchemeColorSelected(int color) {
                        profile.setThemeColor(color);
                        profile.save();
                        inputControlsView.invalidate();
                    }
                },
                createSettingsCallbacks()
        );

        inputControlsView.setEditorListener(new InputControlsView.EditorListener() {
            @Override
            public void onSelectionChanged(ControlElement element) {
                flushPendingTextSave();
                overlay.setHasSelection(element != null);
                if (!overlay.isSettingsOpen()) return;
                // The panel has no scrim: picking another element re-targets it, tapping empty
                // canvas (nothing selected) closes it.
                if (element != null) {
                    overlay.updateSettings(buildElementSettingsModel(element));
                    overlay.onCanvasChanged();
                } else {
                    overlay.hideSettings();
                }
            }

            @Override
            public void onElementsChanged() {
                overlay.onCanvasChanged();
                // Pinch-scale and undo change what the panel shows.
                ControlElement selected = inputControlsView.getSelectedElement();
                if (selected != null) refreshElementSettings(selected);
            }

            @Override
            public void onElementGestureChanged(boolean active) {
                if (active) flushPendingTextSave();
                overlay.setDimmed(active);
            }

            @Override
            public void onUndoAvailabilityChanged(boolean available) {
                overlay.setUndoAvailable(available);
            }
        });
    }

    @Override
    protected void onPause() {
        flushPendingTextSave();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("mix_warning_shown_v4", false)) {
                prefs.edit().putBoolean("mix_warning_shown_v4", true).apply();
                ThemedAlertHost.info(
                        this,
                        "Gamepad + Mouse",
                        "Mixing gamepad and mouse bindings on the same controller may cause unexpected behavior in games. It is recommended to use only gamepad bindings or only mouse/keyboard bindings."
                );
            }
        }, 500);
    }

    // Every settings edit applies to whatever element is selected *now*: the floating panel
    // stays open while the person taps other elements on the canvas.
    private ControlElement target() {
        return inputControlsView.getSelectedElement();
    }

    // Switches the editor to another profile in place. Every edit here is already saved as it
    // happens (Custom Text is flushed first), so nothing is lost from the one being left.
    private void switchProfile(int profileId) {
        if (profile != null && profile.id == profileId) return;
        flushPendingTextSave();
        endEdit();
        ControlsProfile next = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        if (next == null) {
            WinToast.show(this, UiStrings.get(R.string.ui_profile_not_found), Toast.LENGTH_SHORT);
            return;
        }
        pendingIconElement = null;
        profile = next;
        inputControlsView.setProfile(profile);
        overlay.setProfile(profile.id, profile.getThemeColor());
        getIntent().putExtra("profile_id", profile.id);
        inputControlsView.invalidate();
    }

    private void beginEdit(ControlElement element) {
        if (editOpen) return;
        inputControlsView.recordElementSnapshot(element);
        editOpen = true;
    }

    // Any edit other than typing first closes a pending text burst, so the two end up as
    // separate undo steps.
    private void startEdit(ControlElement element) {
        flushPendingTextSave();
        beginEdit(element);
    }

    private void endEdit() {
        editOpen = false;
    }

    private void commit(ControlElement element) {
        profile.save();
        inputControlsView.invalidate();
        refreshElementSettings(element);
        endEdit();
    }

    private void flushPendingTextSave() {
        if (!textSavePending) return;
        handler.removeCallbacks(textSaveRunnable);
        textSaveRunnable.run();
    }

    private ControlElementSettingsCallbacks createSettingsCallbacks() {
        return new ControlElementSettingsCallbacks() {
            @Override
            public void onTypeChanged(int index) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setType(ControlElement.Type.values()[index]);
                inputControlsView.keepInside(e);
                commit(e);
            }

            @Override
            public void onShapeChanged(int index) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setShape(ControlElement.Shape.values()[index]);
                inputControlsView.keepInside(e);
                commit(e);
            }

            @Override
            public void onRangeChanged(int index) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setRange(ControlElement.Range.values()[index]);
                inputControlsView.keepInside(e);
                commit(e);
            }

            @Override
            public void onOrientationChanged(boolean vertical) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setOrientation((byte) (vertical ? 1 : 0));
                inputControlsView.keepInside(e);
                commit(e);
            }

            @Override
            public void onColumnsChanged(int columns) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setBindingCount(columns);
                inputControlsView.keepInside(e);
                commit(e);
            }

            @Override
            public void onScalePreview(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setScale(percent / 100f);
                inputControlsView.keepInside(e);
                inputControlsView.invalidate();
            }

            @Override
            public void onScaleChanged(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setScale(percent / 100f);
                inputControlsView.keepInside(e);
                commit(e);
                overlay.onCanvasChanged();
            }

            @Override
            public void onOpacityPreview(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setOpacity(percent / 100f);
                inputControlsView.invalidate();
            }

            @Override
            public void onOpacityChanged(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setOpacity(percent / 100f);
                commit(e);
            }

            @Override
            public void onColorSelected(int color) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setCustomColor(color);
                commit(e);
            }

            @Override
            public void onToggleSwitchChanged(boolean enabled) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setToggleSwitch(enabled);
                commit(e);
            }

            @Override
            public void onMouseMoveModeChanged(boolean enabled) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setMouseMoveMode(enabled);
                commit(e);
            }

            @Override
            public void onDynamicStickChanged(boolean enabled) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setDynamicStick(enabled);
                commit(e);
            }

            @Override
            public void onZoneScalePreview(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setZoneScale(percent / 100f);
                inputControlsView.invalidate();
            }

            @Override
            public void onZoneScaleChanged(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setZoneScale(percent / 100f);
                commit(e);
            }

            @Override
            public void onFollowSpeedChanged(int percent) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setFollowSpeed(percent / 100f);
                commit(e);
            }

            @Override
            public void onSwipeableChanged(boolean enabled) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setSwipeable(enabled);
                commit(e);
            }

            @Override
            public void onTextChanged(String text) {
                ControlElement e = target();
                if (e == null) return;
                // Not startEdit(): consecutive keystrokes belong to the same edit.
                beginEdit(e);
                e.setText(text);
                inputControlsView.invalidate();
                refreshElementSettings(e);
                handler.removeCallbacks(textSaveRunnable);
                textSavePending = true;
                handler.postDelayed(textSaveRunnable, TEXT_SAVE_DELAY_MS);
            }

            @Override
            public void onIconSelected(String iconKey) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                applyIconSelection(e, iconKey);
                refreshElementSettings(e);
                endEdit();
            }

            @Override
            public void onIconLongPress(String iconKey) {
                ControlElement e = target();
                if (e == null) return;
                if (iconKey.startsWith(BUILTIN_ICON_PREFIX)
                        && !iconKey.equals(BUILTIN_ICON_PREFIX + NO_ICON_ID)) {
                    flushPendingTextSave();
                    pendingIconElement = e;
                    pendingBuiltinOverrideId = Byte.parseByte(iconKey.substring(BUILTIN_ICON_PREFIX.length()));
                    launchIconPicker();
                }
            }

            @Override
            public void onRemoveIcon() {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                e.setCustomIconPath(null);
                commit(e);
            }

            @Override
            public void onBrowseIcon() {
                ControlElement e = target();
                if (e == null) return;
                flushPendingTextSave();
                pendingIconElement = e;
                pendingBuiltinOverrideId = -1;
                launchIconPicker();
            }

            @Override
            public void onBindingSourceTypeChanged(int bindingIndex, int sourceTypeIndex) {
                ControlElement e = target();
                if (e == null) return;
                startEdit(e);
                Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
                e.setBindingAt(bindingIndex, values.length > 0 ? values[0] : Binding.NONE);
                commit(e);
            }

            @Override
            public void onBindingValueChanged(int bindingIndex, int optionIndex) {
                ControlElement e = target();
                if (e == null) return;
                int sourceTypeIndex = sourceTypeIndexForBinding(e, bindingIndex);
                Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
                Binding binding = (optionIndex >= 0 && optionIndex < values.length) ? values[optionIndex] : Binding.NONE;
                if (binding != e.getBindingAt(bindingIndex)) {
                    startEdit(e);
                    e.setBindingAt(bindingIndex, binding);
                    commit(e);
                } else {
                    refreshElementSettings(e);
                }
            }

            @Override
            public void onDone() {
                // Panel closed: write out any Custom Text still waiting for its debounce.
                flushPendingTextSave();
            }
        };
    }

    private void refreshElementSettings(ControlElement element) {
        if (overlay != null && element != null && element == inputControlsView.getSelectedElement()) {
            overlay.updateSettings(buildElementSettingsModel(element));
        }
    }

    private void launchIconPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_ICON_REQUEST);
    }

    private ControlElementSettingsModel buildElementSettingsModel(ControlElement element) {
        ControlElement.Type type = element.getType();
        boolean showShape = type == ControlElement.Type.BUTTON;
        boolean showRange = type == ControlElement.Type.RANGE_BUTTON;
        boolean showToggleSwitch = type == ControlElement.Type.BUTTON;
        boolean showTextAndIcon = type == ControlElement.Type.BUTTON;
        boolean showMouseMoveMode = type == ControlElement.Type.BUTTON;
        boolean showOrientation = type == ControlElement.Type.RANGE_BUTTON;
        boolean showColumns = type == ControlElement.Type.RANGE_BUTTON;
        boolean showSwipeable = type == ControlElement.Type.BUTTON || type == ControlElement.Type.D_PAD;
        boolean showDynamicStick = type == ControlElement.Type.STICK;
        boolean swipeableBlocked = type == ControlElement.Type.BUTTON && (element.isToggleSwitch() || element.isMouseMoveMode());

        ArrayList<Integer> colorList = new ArrayList<>();
        for (int color : PALETTE_COLORS) colorList.add(color);

        String text = element.getText();

        return new ControlElementSettingsModel(
                type.ordinal(),
                Arrays.asList(ControlElement.Type.names()),
                showShape,
                element.getShape().ordinal(),
                Arrays.asList(ControlElement.Shape.names()),
                showRange,
                element.getRange().ordinal(),
                Arrays.asList(ControlElement.Range.names()),
                showOrientation,
                element.getOrientation() == 1,
                showColumns,
                element.getBindingCount(),
                3,
                8,
                Math.round(element.getScale() * 100),
                Math.round(element.getOpacity() * 100),
                colorList,
                element.getCustomColor(),
                showToggleSwitch,
                element.isToggleSwitch(),
                showMouseMoveMode,
                element.isMouseMoveMode(),
                showDynamicStick,
                element.getDynamicStickFlag(),
                Math.round(element.getZoneScale() * 100),
                Math.round(element.getFollowSpeed() * 100),
                showSwipeable,
                element.isSwipeable(),
                swipeableBlocked,
                showTextAndIcon,
                text != null ? text : "",
                buildIconList(element),
                element.getCustomIconPath() != null,
                buildBindingRows(element)
        );
    }

    private List<ControlElementBindingRow> buildBindingRows(ControlElement element) {
        ArrayList<ControlElementBindingRow> rows = new ArrayList<>();
        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            rows.add(buildBindingRow(element, 0, "Binding"));
            rows.add(buildBindingRow(element, 1, "Secondary Binding"));
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            rows.add(buildBindingRow(element, 0, "Up"));
            rows.add(buildBindingRow(element, 1, "Right"));
            rows.add(buildBindingRow(element, 2, "Down"));
            rows.add(buildBindingRow(element, 3, "Left"));
        }
        return rows;
    }

    private ControlElementBindingRow buildBindingRow(ControlElement element, int index, String label) {
        int sourceTypeIndex = sourceTypeIndexForBinding(element, index);
        String[] labels = bindingLabelsForSourceType(sourceTypeIndex);
        Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
        int selectedIndex = Arrays.asList(values).indexOf(element.getBindingAt(index));
        if (selectedIndex < 0) selectedIndex = 0;
        return new ControlElementBindingRow(label, sourceTypeIndex, Arrays.asList(labels), selectedIndex);
    }

    private int sourceTypeIndexForBinding(ControlElement element, int bindingIndex) {
        Binding binding = element.getBindingAt(bindingIndex);
        if (binding.isKeyboard()) return 0;
        if (binding.isMouse()) return 1;
        return 2;
    }

    private Binding[] bindingValuesForSourceType(int sourceTypeIndex) {
        switch (sourceTypeIndex) {
            case 0: return Binding.keyboardBindingValues();
            case 1: return Binding.mouseBindingValues();
            default: return Binding.gamepadBindingValues();
        }
    }

    private String[] bindingLabelsForSourceType(int sourceTypeIndex) {
        switch (sourceTypeIndex) {
            case 0: return Binding.keyboardBindingLabels();
            case 1: return Binding.mouseBindingLabels();
            default: return Binding.gamepadBindingLabels();
        }
    }

    private void applyIconSelection(ControlElement element, String iconKey) {
        if (iconKey.startsWith(CUSTOM_ICON_PREFIX)) {
            element.setCustomIconPath(iconKey.substring(CUSTOM_ICON_PREFIX.length()));
            element.setIconId(0);
        }
        else if (iconKey.startsWith(BUILTIN_ICON_PREFIX)) {
            element.setCustomIconPath(null);
            element.setIconId(Byte.parseByte(iconKey.substring(BUILTIN_ICON_PREFIX.length())));
        }
        profile.save();
        inputControlsView.invalidate();
    }

    private static final class CachedIcon {
        final String key;
        final Bitmap bitmap;
        final ControlElementIconTint tint;
        final boolean dimmed;
        final boolean longPressable;
        final String customPath;  // non-null for custom icons
        final byte builtinId;     // for built-ins

        CachedIcon(String key, Bitmap bitmap, ControlElementIconTint tint, boolean dimmed, boolean longPressable, String customPath, byte builtinId) {
            this.key = key;
            this.bitmap = bitmap;
            this.tint = tint;
            this.dimmed = dimmed;
            this.longPressable = longPressable;
            this.customPath = customPath;
            this.builtinId = builtinId;
        }
    }

    private List<CachedIcon> getIconCache() {
        if (iconCache == null) iconCache = loadIconCache();
        return iconCache;
    }

    // Only the per-element "selected" flag is computed here; bitmaps come from the cache, so
    // refreshing the panel (every edit, every re-target) no longer decodes every PNG again.
    private List<ControlElementIcon> buildIconList(ControlElement element) {
        ArrayList<ControlElementIcon> icons = new ArrayList<>();
        String currentPath = element.getCustomIconPath();
        byte selectedId = element.getIconId();
        for (CachedIcon icon : getIconCache()) {
            boolean selected = icon.customPath != null
                    ? icon.customPath.equals(currentPath)
                    : icon.builtinId == selectedId && currentPath == null;
            icons.add(new ControlElementIcon(icon.key, icon.bitmap, selected, icon.tint, icon.dimmed, icon.longPressable));
        }
        return icons;
    }

    private List<CachedIcon> loadIconCache() {
        ArrayList<CachedIcon> icons = new ArrayList<>();

        File iconsDir = getCustomIconsDir();
        if (iconsDir.exists()) {
            File[] files = iconsDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png") && !name.startsWith("override_"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File file : files) {
                    Bitmap bmp = loadAndScaleBitmap(file.getAbsolutePath());
                    if (bmp == null) continue;
                    String filePath = file.getAbsolutePath();
                    ControlElementIconTint tint = isDarkBitmap(bmp) ? ControlElementIconTint.INVERT : ControlElementIconTint.NONE;
                    icons.add(new CachedIcon(CUSTOM_ICON_PREFIX + filePath, bmp, tint, false, false, filePath, (byte) 0));
                }
            }
        }

        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        } catch (IOException e) {}

        Arrays.sort(iconIds);

        for (final byte id : iconIds) {
            File overrideFile = getBuiltinOverridePath(id);
            Bitmap bmp = null;
            if (id != NO_ICON_ID && overrideFile.exists()) {
                bmp = loadAndScaleBitmap(overrideFile.getAbsolutePath());
                if (bmp == null) Log.w("Icons", "Override exists but failed to decode: " + overrideFile.getName());
            }
            if (bmp == null) {
                try (InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                    bmp = BitmapFactory.decodeStream(is);
                    if (bmp == null) {
                        Log.w("Icons", "Built-in icon " + id + " failed to decode (empty or invalid PNG)");
                        continue;
                    }
                } catch (IOException e) {
                    Log.w("Icons", "Built-in icon " + id + " not found in assets: " + e.getMessage());
                    continue;
                }
            }

            if (id == NO_ICON_ID) {
                // "No icon": the button shows its text label. First tile of the strip, and not
                // replaceable with a picture (it is never drawn, so a replacement would do nothing).
                icons.add(0, new CachedIcon(BUILTIN_ICON_PREFIX + id, bmp, ControlElementIconTint.BLUE, false, false, null, id));
                continue;
            }
            boolean hasOverride = overrideFile.exists();
            icons.add(new CachedIcon(BUILTIN_ICON_PREFIX + id, bmp, ControlElementIconTint.BLUE, hasOverride, true, null, id));
        }

        return icons;
    }

    private static final int[] PALETTE_COLORS = {
        0, 0xffffffff, 0xffdddddd, 0xffaaaaaa, 0xff777777, 0xff444444, 0xff222222, 0xff000000,
        0xffff3b30, 0xffff6961, 0xffff453a, 0xffcc0000, 0xff800000, 0xff4d0000, 0xffff8080, 0xffffb3b3,
        0xffff9500, 0xffff6000, 0xfffe9f0d, 0xffffcc00, 0xffffd60a, 0xffffea00, 0xffffb347, 0xffffdca5,
        0xff34c759, 0xff30d158, 0xff4cd964, 0xff00b050, 0xff2e8b57, 0xff006400, 0xffa8e6cf, 0xffd4edda,
        0xff5ac8fa, 0xff32ade6, 0xff00b4d8, 0xff0096c7, 0xff00758a, 0xff004c5a, 0xffb2ebf2, 0xffe0f7fa,
        0xff007aff, 0xff0a84ff, 0xff2184ff, 0xff1a56db, 0xff003f8f, 0xff001a66, 0xffbed6f8, 0xffdce9ff,
        0xffaf52de, 0xffbf5af2, 0xff9b59b6, 0xff6e3fa3, 0xff4a0e8f, 0xff2d0066, 0xffd7b4f3, 0xffede0f8,
        0xffff2d92, 0xffff375f, 0xffff6ab0, 0xffe91e8c, 0xffad1457, 0xff6a0032, 0xffffb3d9, 0xffffdcef,
    };

    private Bitmap loadAndScaleBitmap(String path) {
        final int MAX_DIM = 256;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w("Icons", "Cannot decode dimensions of " + path + " — file may be empty, corrupt, or not a supported image format");
                return null;
            }
            if (opts.outWidth <= MAX_DIM && opts.outHeight <= MAX_DIM) {
                bmp = BitmapFactory.decodeFile(path);
            } else {
                int sampleSize = 1;
                int w = opts.outWidth, h = opts.outHeight;
                while (w / 2 >= MAX_DIM || h / 2 >= MAX_DIM) { sampleSize *= 2; w /= 2; h /= 2; }
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                Bitmap sampled = BitmapFactory.decodeFile(path, opts);
                if (sampled == null) {
                    Log.w("Icons", "Failed to decode " + path + " even with sample size " + sampleSize);
                    return null;
                }
                float scale = Math.min((float) MAX_DIM / sampled.getWidth(), (float) MAX_DIM / sampled.getHeight());
                bmp = Bitmap.createScaledBitmap(sampled, (int)(sampled.getWidth() * scale), (int)(sampled.getHeight() * scale), true);
                if (!sampled.isRecycled()) sampled.recycle();
                Log.i("Icons", "Auto-scaled " + new File(path).getName() +
                    " from " + opts.outWidth + "x" + opts.outHeight + " to " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Exception e) {
            Log.e("Icons", "Exception loading " + path + ": " + e.getMessage());
        }
        if (bmp == null) Log.w("Icons", "Final decode returned null for " + path);
        return bmp;
    }

    private boolean isDarkBitmap(Bitmap bmp) {
        int step = Math.max(1, Math.min(bmp.getWidth(), bmp.getHeight()) / 16);
        long totalLuminance = 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y += step) {
            for (int x = 0; x < bmp.getWidth(); x += step) {
                int pixel = bmp.getPixel(x, y);
                int alpha = (pixel >> 24) & 0xff;
                if (alpha > 32) {
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;
                    totalLuminance += (r * 299L + g * 587L + b * 114L) / 1000L;
                    count++;
                }
            }
        }
        return count > 0 && (totalLuminance / count) < 50;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ICON_REQUEST && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri uri = data.getData();
            try {
                File iconsDir = getCustomIconsDir();
                if (!iconsDir.exists()) iconsDir.mkdirs();

                File dest;
                if (pendingBuiltinOverrideId >= 0) {
                    dest = getBuiltinOverridePath(pendingBuiltinOverrideId);
                    pendingBuiltinOverrideId = -1;
                } else {
                    dest = new File(iconsDir, "icon_" + System.currentTimeMillis() + ".png");
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }

                Bitmap loaded = loadAndScaleBitmap(dest.getAbsolutePath());
                if (loaded == null) {
                    dest.delete();
                    Log.w("Icons", "Picked file is not a valid image or could not be decoded: " + uri);
                    WinToast.show(this, UiStrings.get(R.string.ui_unable_to_set_icon), Toast.LENGTH_SHORT);
                    pendingIconElement = null;
                    return;
                }
                if (loaded.getWidth() != -1) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        loaded.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                }
                loaded.recycle();

                inputControlsView.invalidateIconCache();
                iconCache = null;

                if (pendingIconElement != null) {
                    startEdit(pendingIconElement);
                    pendingIconElement.setCustomIconPath(dest.getAbsolutePath());
                    pendingIconElement.setIconId(0);
                    profile.save();
                    inputControlsView.invalidate();
                    refreshElementSettings(pendingIconElement);
                    endEdit();
                    pendingIconElement = null;
                }
            } catch (Exception e) {
                Log.e("Icons", "Error saving picked icon: " + e.getMessage());
                pendingIconElement = null;
                pendingBuiltinOverrideId = -1;
                WinToast.show(this, UiStrings.get(R.string.ui_unable_to_set_icon), Toast.LENGTH_SHORT);
            }
        }
    }

    // Every way out (system back, the editor's own close/save) ends in finish(), so the return
    // motion is applied here: the reverse of the shared-axis enter used to open this screen.
    // The result carries the profile the editor ended on (it can switch profiles in place), so
    // the in-game sidebar can keep that layout active instead of dropping to Disabled.
    @Override
    public void finish() {
        Intent result = new Intent();
        result.putExtra("profile_id", profile != null ? profile.id : -1);
        setResult(Activity.RESULT_OK, result);
        super.finish();
        overridePendingTransition(R.anim.shared_axis_pop_enter, R.anim.shared_axis_pop_exit);
    }
}
