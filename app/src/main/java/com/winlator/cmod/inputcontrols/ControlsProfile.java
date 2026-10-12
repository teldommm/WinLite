package com.winlator.cmod.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public final int id;
    private String name;
    public static final float MIN_CURSOR_SPEED = 0.1f;
    // Same 10..200 % range as Cursor Speed; stored values above it (older
    // profiles) are clamped on load.
    public static final float MAX_CURSOR_SPEED = 2.0f;
    private float cursorSpeed = 1.0f;
    private int themeColor = 0;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    /**
     * Legacy "cursorSpeed" from the .icp. Nothing reads it at runtime any more: stick / D-pad /
     * button / external-gamepad MOUSE_MOVE bindings follow the global Cursor Speed (in-game
     * sidebar / Settings). Still loaded and saved so profiles round-trip unchanged for other
     * Winlator builds that do use it.
     *
     * Sanitized because the loader passes NaN when the .icp has no "cursorSpeed" field (and
     * JSONObject.put() rejects NaN, which made save() fail), and to drop float noise such as
     * 1.0000001 that some shared profiles carry.
     */
    public void setCursorSpeed(float cursorSpeed) {
        if (Float.isNaN(cursorSpeed) || Float.isInfinite(cursorSpeed) || cursorSpeed <= 0f) cursorSpeed = 1.0f;
        cursorSpeed = Math.max(MIN_CURSOR_SPEED, Math.min(MAX_CURSOR_SPEED, cursorSpeed));
        this.cursorSpeed = Math.round(cursorSpeed * 100f) / 100f;
    }

    public int getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(int themeColor) {
        this.themeColor = themeColor;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) controllers.add(controller = ExternalController.getController(id));
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();
        
        // First try direct deviceId match
        for (ExternalController controller : controllers) {
            if (controller.getDeviceId() == deviceId) return controller;
        }
        
        // If no match, try to find by descriptor
        android.view.InputDevice device = android.view.InputDevice.getDevice(deviceId);
        if (device != null) {
            String descriptor = device.getDescriptor();
            for (ExternalController controller : controllers) {
                if (controller.getId().equals(descriptor)) {
                    // Same physical controller under a new device id (reconnected): remember the
                    // new id, or its slot would be keyed by the old one and the game would see a
                    // second gamepad.
                    controller.setDeviceId(deviceId);
                    return controller;
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));
            if (themeColor != 0) data.put("themeColor", themeColor);

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            FileUtils.writeString(file, data.toString());
        }
        catch (JSONException e) {}
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void addElementAt(int index, ControlElement element) {
        if (index < 0 || index > elements.size()) elements.add(element);
        else elements.add(index, element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return controllers;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) return controllers;
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) return;

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (profileJSONObject.has("themeColor")) themeColor = profileJSONObject.getInt("themeColor");
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                ControlElement element = elementFromJSON(elementJSONObject, inputControlsView);
                // Same as before the refactor: one broken element aborts the load.
                if (element == null) throw new JSONException("Invalid control element at index " + i);

                boolean hasGamepadBinding = true;
                for (int j = 0; j < element.getBindingCount(); j++) {
                    if (!element.getBindingAt(j).isGamepad()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                elements.add(element);
            }
            elementsLoaded = true;
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // One element from its saved JSON (the same shape ControlElement.toJSONObject() writes).
    // Shared by loadElements() and the editor's Duplicate action, so a copy carries every field
    // exactly the way a reload would.
    public static ControlElement elementFromJSON(JSONObject elementJSONObject, InputControlsView inputControlsView) {
        if (elementJSONObject == null) return null;
        try {
            ControlElement element = new ControlElement(inputControlsView);
            element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
            element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
            element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
            element.setX((int)(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
            element.setY((int)(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
            element.setScale((float)elementJSONObject.getDouble("scale"));
            element.setText(elementJSONObject.getString("text"));
            element.setIconId(elementJSONObject.getInt("iconId"));
            if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
            if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
            if (elementJSONObject.has("opacity")) element.setOpacity((float)elementJSONObject.getDouble("opacity"));
            if (elementJSONObject.has("customColor")) element.setCustomColor(elementJSONObject.getInt("customColor"));
            if (elementJSONObject.has("mouseMoveMode")) element.setMouseMoveMode(elementJSONObject.getBoolean("mouseMoveMode"));
            element.setSwipeable(elementJSONObject.optBoolean("swipeable", false));
            element.setDynamicStick(elementJSONObject.optBoolean("dynamicStick", false));
            element.setZoneScale((float) elementJSONObject.optDouble("zoneScale", ControlElement.DEFAULT_ZONE_SCALE));
            element.setFollowSpeed((float) elementJSONObject.optDouble("followSpeed", ControlElement.DEFAULT_FOLLOW_SPEED));
            if (elementJSONObject.has("customIconPath")) element.setCustomIconPath(elementJSONObject.getString("customIconPath"));
            JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
            for (int j = 0; j < bindingsJSONArray.length(); j++) {
                element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
            }
            return element;
        }
        catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }
}
