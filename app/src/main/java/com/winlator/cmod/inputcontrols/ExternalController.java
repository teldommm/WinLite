package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

public class ExternalController {
    public static final byte IDX_BUTTON_A = 0;
    public static final byte IDX_BUTTON_B = 1;
    public static final byte IDX_BUTTON_X = 2;
    public static final byte IDX_BUTTON_Y = 3;
    public static final byte IDX_BUTTON_L1 = 4;
    public static final byte IDX_BUTTON_R1 = 5;
    public static final byte IDX_BUTTON_SELECT = 6;
    public static final byte IDX_BUTTON_START = 7;
    public static final byte IDX_BUTTON_L3 = 8;
    public static final byte IDX_BUTTON_R3 = 9;
    public static final byte IDX_BUTTON_L2 = 10;
    public static final byte IDX_BUTTON_R2 = 11;
    private String name;
    private String id;
    private int deviceId = -1;
    private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
    /** Raw state of the physical controller (after the stick dead zone). */
    public final GamepadState state = new GamepadState();
    /** What the profile's gamepad bindings produce (written by InputControlsView). */
    public final GamepadState remappedState = new GamepadState();
    /** Merged state sent to the game, see {@link #buildOutputState()}. */
    private final GamepadState outputState = new GamepadState();

    // Device capabilities, probed once per device id: whether the D-pad comes as a HAT axis (else
    // as KEYCODE_DPAD_* keys) and whether the triggers are analog axes (else only BUTTON_L2/R2).
    private int capsDeviceId = Integer.MIN_VALUE;
    private boolean hasHat = false;
    private boolean hasAnalogTriggers = false;
    private float triggerLAxis = 0f, triggerRAxis = 0f;
    private boolean triggerLKey = false, triggerRKey = false;
    private XServerDisplayActivity activity;



    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }








    // Remove static keyword
    public static final HashMap<Byte, Byte> buttonMappings = new HashMap<>();


    private boolean triggerLPressedViaButton = false;
    private boolean triggerRPressedViaButton = false;


//    public ExternalController() {
//
//
//        // Initialize trigger mappings to themselves
//        buttonMappings.put(IDX_BUTTON_L2, IDX_BUTTON_L2);
//        buttonMappings.put(IDX_BUTTON_R2, IDX_BUTTON_R2);
//
//        // Ensure triggerType is set to TRIGGER_IS_AXIS
//        triggerType = TRIGGER_IS_AXIS;
//    }


    public int getDeviceId() {
        if (this.deviceId == -1) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null && device.getDescriptor().equals(id)) {
                    this.deviceId = deviceId;
                    break;
                }
            }
        }
        return this.deviceId;
    }

    /** Called when the controller was found again by descriptor under a new device id (reconnect). */
    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    private void probeCaps(InputDevice device) {
        if (device == null || device.getId() == capsDeviceId) return;
        capsDeviceId = device.getId();
        hasHat = device.getMotionRange(MotionEvent.AXIS_HAT_X) != null
                || device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null;
        hasAnalogTriggers = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null
                || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null
                || device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null
                || device.getMotionRange(MotionEvent.AXIS_GAS) != null;
    }

    /** True when L2/R2 are analog axes; false for digital-only triggers (Switch Pro, Joy-Con...). */
    public boolean hasAnalogTriggers() {
        // Probed here too: a key event can reach the bindings before any motion event did.
        int id = getDeviceId();
        if (id != capsDeviceId && id != -1) probeCaps(InputDevice.getDevice(id));
        return hasAnalogTriggers;
    }

    /** Clears every input, e.g. after the device was disconnected while something was held. */
    public void resetState() {
        state.copy(new GamepadState());
        remappedState.copy(new GamepadState());
        triggerLAxis = triggerRAxis = 0f;
        triggerLKey = triggerRKey = false;
    }

    /**
     * State for the game: the raw physical state with every bound input taken out, plus what the
     * bindings produce. Inputs without a binding pass straight through (they used to be dropped
     * as soon as the profile had any binding for this controller).
     */
    public GamepadState buildOutputState() {
        GamepadState out = outputState;
        out.copy(state);
        if (controllerBindings.isEmpty()) return out;

        for (ExternalControllerBinding binding : controllerBindings) {
            if (binding.getBinding() == Binding.NONE) continue;
            maskSource(out, binding.getKeyCode());
        }

        GamepadState r = remappedState;
        out.buttons |= r.buttons;
        for (int i = 0; i < 4; i++) out.dpad[i] |= r.dpad[i];
        if (r.thumbLX != 0) out.thumbLX = r.thumbLX;
        if (r.thumbLY != 0) out.thumbLY = r.thumbLY;
        if (r.thumbRX != 0) out.thumbRX = r.thumbRX;
        if (r.thumbRY != 0) out.thumbRY = r.thumbRY;
        out.triggerL = Math.max(out.triggerL, r.triggerL);
        out.triggerR = Math.max(out.triggerR, r.triggerR);
        return out;
    }

    private static void maskSource(GamepadState out, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: out.dpad[0] = false; return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: out.dpad[1] = false; return;
            case KeyEvent.KEYCODE_DPAD_DOWN: out.dpad[2] = false; return;
            case KeyEvent.KEYCODE_DPAD_LEFT: out.dpad[3] = false; return;
            case ExternalControllerBinding.AXIS_X_NEGATIVE:
            case ExternalControllerBinding.AXIS_X_POSITIVE: out.thumbLX = 0; return;
            case ExternalControllerBinding.AXIS_Y_NEGATIVE:
            case ExternalControllerBinding.AXIS_Y_POSITIVE: out.thumbLY = 0; return;
            case ExternalControllerBinding.AXIS_Z_NEGATIVE:
            case ExternalControllerBinding.AXIS_Z_POSITIVE: out.thumbRX = 0; return;
            case ExternalControllerBinding.AXIS_RZ_NEGATIVE:
            case ExternalControllerBinding.AXIS_RZ_POSITIVE: out.thumbRY = 0; return;
        }
        int idx = getButtonIdxByKeyCode(keyCode);
        if (idx == IDX_BUTTON_L2) out.triggerL = 0;
        else if (idx == IDX_BUTTON_R2) out.triggerR = 0;
        if (idx != -1) out.setPressed(idx, false);
    }

    public boolean isConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.getDescriptor().equals(id)) return true;
        }
        return false;
    }

    public ExternalControllerBinding getControllerBinding(int keyCode) {
        for (ExternalControllerBinding controllerBinding : controllerBindings) {
            if (controllerBinding.getKeyCode() == keyCode) return controllerBinding;
        }
        return null;
    }

    public ExternalControllerBinding getControllerBindingAt(int index) {
        return controllerBindings.get(index);
    }

    public void addControllerBinding(ExternalControllerBinding controllerBinding) {
        if (getControllerBinding(controllerBinding.getKeyCode()) == null) controllerBindings.add(controllerBinding);
    }

    public int getPosition(ExternalControllerBinding controllerBinding) {
        return controllerBindings.indexOf(controllerBinding);
    }

    public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
        controllerBindings.remove(controllerBinding);
    }

    public void setButtonMapping(byte originalButton, byte mappedButton) {
        buttonMappings.put(originalButton, mappedButton);
        // Remove triggerType handling from here
    }


    public byte getMappedButton(byte originalButton) {
        byte mappedButton = buttonMappings.getOrDefault(originalButton, originalButton);
//        Log.d("ExternalController", "getMappedButton: Original button = " + originalButton + ", Mapped button = " + mappedButton);
        return mappedButton;
    }


    public int getControllerBindingCount() {
        return controllerBindings.size();
    }

    public JSONObject toJSONObject() {
        try {
            if (controllerBindings.isEmpty()) return null;
            JSONObject controllerJSONObject = new JSONObject();
            controllerJSONObject.put("id", id);
            controllerJSONObject.put("name", name);

            JSONArray controllerBindingsJSONArray = new JSONArray();
            for (ExternalControllerBinding controllerBinding : controllerBindings) controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
            controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);

            return controllerJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ExternalController ? ((ExternalController)obj).id.equals(this.id) : super.equals(obj);
    }

    private void processJoystickInput(MotionEvent event) {
        // Radial dead zone per stick, values passed on unscaled (the game applies its own dead zone
        // on top). A per-axis cut snapped small off-axis movement to 0, so the stick "stuck" to
        // the horizontal / vertical near the axes.
        float lx = getRawAxis(event, MotionEvent.AXIS_X), ly = getRawAxis(event, MotionEvent.AXIS_Y);
        float rx = getRawAxis(event, MotionEvent.AXIS_Z), ry = getRawAxis(event, MotionEvent.AXIS_RZ);
        boolean leftOut = lx * lx + ly * ly >= ControlElement.STICK_DEAD_ZONE * ControlElement.STICK_DEAD_ZONE;
        boolean rightOut = rx * rx + ry * ry >= ControlElement.STICK_DEAD_ZONE * ControlElement.STICK_DEAD_ZONE;
        state.thumbLX = leftOut ? lx : 0f;
        state.thumbLY = leftOut ? ly : 0f;
        state.thumbRX = rightOut ? rx : 0f;
        state.thumbRY = rightOut ? ry : 0f;

        // Only a device with a HAT axis reports its D-pad here. Others send KEYCODE_DPAD_* keys,
        // and reading the absent axis as 0 released a held D-pad on every stick movement. The D-pad
        // is also no longer dropped while the left stick is tilted: holding both is legitimate.
        if (hasHat) {
            float axisX = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, -1);
            float axisY = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y, -1);
            state.dpad[0] = axisY == -1.0f;
            state.dpad[1] = axisX == 1.0f;
            state.dpad[2] = axisY == 1.0f;
            state.dpad[3] = axisX == -1.0f;
        }
    }

    private static float getRawAxis(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        if (device == null) return 0f;
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) return 0f;
        float value = event.getAxisValue(axis);
        return Math.abs(value) <= range.getFlat() ? 0f : value;
    }



    private void processTriggerButton(MotionEvent event) {
        if (!hasAnalogTriggers) return; // digital triggers: driven by BUTTON_L2/R2 keys only
        triggerLAxis = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_BRAKE) : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        triggerRAxis = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_GAS) : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        updateTriggers();
    }

    /**
     * Analog triggers follow their axis (their BUTTON_L2/R2 keys, if any, are ignored so the two
     * don't fight); digital ones are 0 / 1 from the key. The "pressed" bit uses a half-way
     * threshold (it used to need exactly 1.0, which some triggers never reach).
     */
    private void updateTriggers() {
        state.triggerL = hasAnalogTriggers ? Math.max(0f, Math.min(1f, triggerLAxis)) : (triggerLKey ? 1f : 0f);
        state.triggerR = hasAnalogTriggers ? Math.max(0f, Math.min(1f, triggerRAxis)) : (triggerRKey ? 1f : 0f);
        state.setPressed(IDX_BUTTON_L2, state.triggerL >= 0.5f);
        state.setPressed(IDX_BUTTON_R2, state.triggerR >= 0.5f);
    }

    public boolean isXboxController() {
        InputDevice device = InputDevice.getDevice(getDeviceId());
        if (device == null) return false;
        int vendorId = device.getVendorId();
        return vendorId == 0x045E; // Microsoft's Vendor ID for Xbox controllers
    }

    private void processXboxTriggerButton(MotionEvent event) {
        // Retrieve axis values for triggers
        float l = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f
                ? event.getAxisValue(MotionEvent.AXIS_BRAKE)
                : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float r = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f
                ? event.getAxisValue(MotionEvent.AXIS_GAS)
                : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);

        // Simulate full press by setting trigger values to 1.0f when pulled
        if (l > 0.0f) {
            state.triggerL = 1.0f; // Simulate full press
            state.setPressed(IDX_BUTTON_L2, true);
        } else {
            state.triggerL = 0.0f; // Simulate release
            state.setPressed(IDX_BUTTON_L2, false);
        }

        if (r > 0.0f) {
            state.triggerR = 1.0f; // Simulate full press
            state.setPressed(IDX_BUTTON_R2, true);
        } else {
            state.triggerR = 0.0f; // Simulate release
            state.setPressed(IDX_BUTTON_R2, false);
        }
    }



//    private void processTriggerButton(MotionEvent event) {
//        // Get the raw analog values of L2 and R2 triggers
//        float l = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_BRAKE) : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
//        float r = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_GAS) : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
//
//        // Get the mapped buttons for L2 and R2
//        byte leftTriggerMapped = getMappedButton(IDX_BUTTON_L2);
//        byte rightTriggerMapped = getMappedButton(IDX_BUTTON_R2);
//
//
//
//        // --- Handle button remapping ONLY ---
//        // (Do NOT store original trigger values yet)
//
//        if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // L2 and R2 are swapped
//            state.triggerL = r;
//            state.triggerR = l;
//            state.setPressed(IDX_BUTTON_L2, r == 1.0f);
//            state.setPressed(IDX_BUTTON_R2, l == 1.0f);
////            Log.d("ExternalController", "trigger was swapped");
//        } else {
//        if (leftTriggerMapped != IDX_BUTTON_L2 && leftTriggerMapped != IDX_BUTTON_R2) {
//            // L2 is remapped to a button OTHER than R2
//            state.setPressed(leftTriggerMapped, l > 0.5f);
//            state.triggerL = 0; // Ensure analog value is reset
////            Log.d("ExternalController", "trigger was reset");
//        }
//        if (rightTriggerMapped != IDX_BUTTON_R2 && rightTriggerMapped != IDX_BUTTON_L2) {
//            // R2 is remapped to a button OTHER than L2
//            state.setPressed(rightTriggerMapped, r > 0.5f);
//            state.triggerR = 0; // Ensure analog value is reset
//        }
//
//        // --- Handle trigger cross-mapping ---
//
//        // Reset trigger values to 0 before cross-mapping < Maybe remove this
////        state.triggerL = 0;
////        state.triggerR = 0;
//
//        if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // L2 and R2 are swapped
//            state.triggerL = r;
//            state.triggerR = l;
//        } else if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_R2) {
//            // BOTH L2 and R2 are mapped to R2
//            state.triggerR = Math.max(l, r);
//        } else if (leftTriggerMapped == IDX_BUTTON_L2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // BOTH L2 and R2 are mapped to L2
//            state.triggerL = Math.max(l, r);
//        } else {
//            // Not mapping to the same trigger, handle individually
//            if (rightTriggerMapped == IDX_BUTTON_L2) {
//                // R2 is mapped to L2
//                state.triggerL = r;
//            } else if (leftTriggerMapped == IDX_BUTTON_R2) {
//                // L2 is mapped to R2
//                state.triggerR = l;
//            }
//
//            // Set original values if not cross-mapped
//            if (leftTriggerMapped != IDX_BUTTON_R2) {
//                state.triggerL = l;
//            }
//            if (rightTriggerMapped != IDX_BUTTON_L2) {
//                state.triggerR = r;
//            }
//        }
//
//        if (leftTriggerMapped != IDX_BUTTON_L2 && leftTriggerMapped != IDX_BUTTON_R2) {
//            state.triggerL = 0; // Reset L2 analog value if it's mapped to anything else
////            Log.d("ExternalController", "trigger was reset");
//        }
//        if (rightTriggerMapped != IDX_BUTTON_R2 && rightTriggerMapped != IDX_BUTTON_L2) {
//            state.triggerR = 0; // Reset R2 analog value if it's mapped to anything else
//        }
//
//        }
//        // Log for debugging
////        Log.d("ExternalController", "processTriggerButton: L trigger = " + state.triggerL + ", R trigger = " + state.triggerR +
////                ", Mapped L trigger = " + leftTriggerMapped + ", Mapped R trigger = " + rightTriggerMapped);
//    }







//    public boolean updateStateFromMotionEvent(MotionEvent event) {
//        if (isJoystickDevice(event)) {
//            // Check if the event contains trigger axis data
//            boolean hasTriggerData = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_BRAKE) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_GAS) != 0f;
//
//            if (hasTriggerData) {
////                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
////                triggerType = (byte) preferences.getInt("trigger_type", TRIGGER_IS_BUTTON);
//
//                if (triggerType == TRIGGER_IS_AXIS) {
////                    Log.d("ExternalController", "triggerType is " + triggerType);
//                    processTriggerButton(event);
//                }
//            }
//
//            int historySize = event.getHistorySize();
//            for (int i = 0; i < historySize; i++) {
//                processJoystickInput(event, i);
//            }
//            processJoystickInput(event, -1);
//            return true;
//        }
//        return false;
//    }

    public boolean updateStateFromMotionEvent(MotionEvent event) {
        if (isJoystickDevice(event)) {
            probeCaps(event.getDevice());
            processTriggerButton(event);
            // The state is absolute, so only the latest sample of a batched event matters.
            processJoystickInput(event);
            return true;
        }
        return false;
    }


    public boolean updateStateFromKeyEvent(KeyEvent event) {
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        probeCaps(event.getDevice());
        int buttonIdx = getButtonIdxByKeyCode(keyCode);
        if (buttonIdx != -1) {
            if (buttonIdx == IDX_BUTTON_L2) {
                triggerLKey = pressed;
                updateTriggers();
            } else if (buttonIdx == IDX_BUTTON_R2) {
                triggerRKey = pressed;
                updateTriggers();
            } else
                state.setPressed(buttonIdx, pressed);
            return true;
        }

        // D-pad keys the framework synthesizes from stick movement carry FLAG_FALLBACK: consume
        // them without touching the D-pad (the real D-pad of this device is a HAT or real keys).
        boolean synthetic = (event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!synthetic) state.dpad[0] = pressed;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!synthetic) state.dpad[1] = pressed;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!synthetic) state.dpad[2] = pressed;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!synthetic) state.dpad[3] = pressed;
                return true;
        }
        return false;
    }

//    public boolean updateStateFromKeyEvent(KeyEvent event) {
//        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
//        int keyCode = event.getKeyCode();
//        int buttonIdx = getButtonIdxByKeyCode(keyCode);
//
//        if (buttonIdx != -1) {
//            byte mappedButtonIdx = getMappedButton((byte) buttonIdx);
//
//            if (mappedButtonIdx == IDX_BUTTON_L2) {
//                state.triggerL = pressed ? 1.0f : 0f;
//                state.setPressed(mappedButtonIdx, pressed);
//                triggerLPressedViaButton = pressed;
//            } else if (mappedButtonIdx == IDX_BUTTON_R2) {
//                state.triggerR = pressed ? 1.0f : 0f;
//                state.setPressed(mappedButtonIdx, pressed);
//                triggerRPressedViaButton = pressed;
//            } else {
//                state.setPressed(mappedButtonIdx, pressed);
//            }
//            return true;
//        }
//
//        // Handle D-pad directions with mappings
//        switch (keyCode) {
//            case KeyEvent.KEYCODE_DPAD_UP:
//                state.dpad[0] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_RIGHT:
//                state.dpad[1] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_DOWN:
//                state.dpad[2] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_LEFT:
//                state.dpad[3] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//        }
//        return false;
//    }




    public static ArrayList<ExternalController> getControllers() {
        int[] deviceIds = InputDevice.getDeviceIds();
        ArrayList<ExternalController> controllers = new ArrayList<>();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (isGameController(device)) {
                ExternalController controller = new ExternalController();
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controllers.add(controller);
            }
        }
        return controllers;
    }

    public static ExternalController getController(String id) {
        for (ExternalController controller : getControllers()) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public static ExternalController getController(int deviceId) {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            if (deviceIds[i] == deviceId) {
                InputDevice device = InputDevice.getDevice(deviceIds[i]);
                if (isGameController(device)) {
                    ExternalController controller = new ExternalController();
                    controller.setId(device.getDescriptor());
                    controller.setName(device.getName());
                    controller.deviceId = deviceIds[i];
                    return controller;
                }
            }
        }
        return null;
    }

    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        if (device.getName() != null && device.getName().toLowerCase().contains("uinput-fpc")) return false;
        int sources = device.getSources();
        // Exclude devices with SOURCE_MOUSE from being considered controllers
        return !device.isVirtual() && ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && (sources & InputDevice.SOURCE_MOUSE) == 0));
    }


    public float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
        if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
            float value = event.getAxisValue(axis);
            return Math.abs(value) == 1.0f ? value : 0.0f;
        }

        InputDevice device = event.getDevice();
        if (device == null) return 0.0f;
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) return 0.0f;

        float flat = range.getFlat();
        float value = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);

        if (Math.abs(value) <= flat) return 0.0f;

        if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y || axis == MotionEvent.AXIS_Z || axis == MotionEvent.AXIS_RZ) {
             return Math.abs(value) >= ControlElement.STICK_DEAD_ZONE ? value : 0.0f;
        }

        return 0.0f;
    }




    public static boolean isJoystickDevice(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.getAction() == MotionEvent.ACTION_MOVE;
    }

    public static int getButtonIdxByKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return IDX_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return IDX_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return IDX_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return IDX_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return IDX_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return IDX_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return IDX_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_START:
                return IDX_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return IDX_BUTTON_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return IDX_BUTTON_R3;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return IDX_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }

    public static int getButtonIdxByName(String name) {
        switch (name) {
            case "A":
                return IDX_BUTTON_A;
            case "B":
                return IDX_BUTTON_B;
            case "X":
                return IDX_BUTTON_X;
            case "Y":
                return IDX_BUTTON_Y;
            case "L1":
                return IDX_BUTTON_L1;
            case "R1":
                return IDX_BUTTON_R1;
            case "SELECT":
                return IDX_BUTTON_SELECT;
            case "START":
                return IDX_BUTTON_START;
            case "L3":
                return IDX_BUTTON_L3;
            case "R3":
                return IDX_BUTTON_R3;
            case "L2":
                return IDX_BUTTON_L2;
            case "R2":
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }

}
