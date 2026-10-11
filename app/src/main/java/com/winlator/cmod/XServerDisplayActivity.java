package com.winlator.cmod;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.util.Rational;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerFiles;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.ContainerOverlay;
import com.winlator.cmod.container.SaveProfiles;
import com.winlator.cmod.container.SaveRegistry;
import com.winlator.cmod.container.SharedComponents;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.DXVKConfig;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfig;
import com.winlator.cmod.contentdialog.WineD3DConfig;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DebugLogFile;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.GameSaveManager;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.LosslessDll;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.core.WinlatorLogcatLogger;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.ui.GraphicsPanelCallbacks;
import com.winlator.cmod.ui.GraphicsPanelState;
import com.winlator.cmod.ui.GraphicsSidebarPanelHost;
import com.winlator.cmod.ui.GraphicsSidebarPanelKt;
import com.winlator.cmod.ui.HudPanelCallbacks;
import com.winlator.cmod.ui.toast.WinToast;
import com.winlator.cmod.ui.HudPanelState;
import com.winlator.cmod.ui.HudSidebarPanelHost;
import com.winlator.cmod.ui.InputPanelCallbacks;
import com.winlator.cmod.ui.InputPanelState;
import com.winlator.cmod.ui.InputProfileOption;
import com.winlator.cmod.ui.InputSidebarPanelHost;
import com.winlator.cmod.ui.ScreenPanelCallbacks;
import com.winlator.cmod.ui.ScreenSidebarPanelHost;
import com.winlator.cmod.ui.ThemedLoadingOverlayHost;
import com.winlator.cmod.ui.SidebarRailCallbacks;
import com.winlator.cmod.ui.IngameSidebarController;
import com.winlator.cmod.ui.IngameSidebarHost;
import com.winlator.cmod.ui.SidebarRailItemData;
import com.winlator.cmod.ui.SidebarRailState;
import com.winlator.cmod.ui.TaskManagerCallbacks;
import com.winlator.cmod.ui.TaskManagerPanelHost;
import com.winlator.cmod.ui.TaskManagerPanelState;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.winhandler.GamepadMode;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.ProcessInfo;
import com.winlator.cmod.winhandler.TaskManagerSidebar;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.extensions.RandrExtension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;
import com.winlator.cmod.core.AppDefaults;

public class XServerDisplayActivity extends AppCompatActivity {

    private static final HashMap<String, Boolean> WINE_XRANDR_SUPPORT_CACHE = new HashMap<>();
    private final AtomicBoolean exiting = new AtomicBoolean(false);

    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private XServerRendererView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private FrameRating classicHud = null;
    private WinlatorHUD modernHud = null;
    private Runnable editInputControlsCallback;
    // Profile id the controls editor reported on exit (-1: none, e.g. the Input Controls screen).
    private int editorResultProfileId = -1;
    private Runnable refreshInputPanel;
    private Shortcut shortcut;
    private int activeLsfgMultiplier;
    private java.io.File activeLsfgDll;
    private float activeLsfgFlowScale = 0.80f;
    // Adaptive frame gen: target Hz, 0 = fixed multiplier. Stored with
    // lsfgMultiplier = 4 (the ceiling the pacer may use to reach the target).
    private int activeLsfgTargetRate = 0;
    // Last Adaptive target, kept while a fixed multiplier is selected (activeLsfgTargetRate is 0
    // then, which is what gets passed to native) so switching back restores it. Persisted
    // separately as lsfgTargetMemo; never 0 (falls back to DEFAULT_LSFG_TARGET_RATE).
    private int rememberedLsfgTargetRate = Container.DEFAULT_LSFG_TARGET_RATE;
    private boolean graphicsFsrEnabled;
    private int graphicsUpscalerModeIndex;
    private int graphicsPostFxModeIndex;
    private int graphicsSharpnessPercent = 50;
    private int graphicsReshadeStrength = 65;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String graphicsWrapper = Container.DEFAULT_GRAPHICS_WRAPPER;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private TaskManagerSidebar taskManagerSidebar;
    private SidebarRailState sidebarRailState;
    private IngameSidebarController ingameSidebar;
    private WineRequestHandler wineRequestHandler;
    // Cursor Speed (pref "cursor_speed"): one shared speed for the finger on the touch area,
    // Trackpad elements, mouse-move buttons and a captured physical mouse (nobody plays with
    // both at once). The active profile doesn't multiply it.
    private float globalCursorSpeed = 1.0f;
    private final com.winlator.cmod.math.MotionAccumulator capturedMouseMotion = new com.winlator.cmod.math.MotionAccumulator();
    // Touch surface mode (sidebar "Touch Mode"); starts from the shortcut's Touch Mode
    // (stored as simTouchScreen). Sidebar changes last for this session only — the shortcut is never rewritten
    // from in-game (its editor's Input tab stays the one place that sets the default).
    private int touchMode = TouchpadView.MODE_TRACKPAD;
    private float refreshRate = 60.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private WinlatorLogcatLogger winlatorLogcatLogger;
    private String rendererLogPath;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private String wineCpuTopologyValue = "";
    private int frameRatingWindowId = -1;

    private int activeRendererWindowId = -1;
    private String lastRendererName = null;
    private boolean cursorLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;
    // Like Relative/Disable Mouse: starts from the shortcut ("tapToClick", on unless "0"),
    // the sidebar changes it for the session only.
    private boolean isTapToClickEnabled = true;
    // Touchscreen Timeout: controls fade out after 5 s idle, the next touch only brings them
    // back. Global pref like its Controls-card neighbours (show controls, haptics): the sidebar
    // switch saves it.
    private boolean isTouchscreenTimeout = false;
    // Resolved once in onCreate; drives the Wine joystick registry keys and whether any fake
    // gamepad node is created at all (GamepadMode.NONE).
    private int gamepadMode = GamepadMode.BOTH;
    private boolean simulateTouchScreen = false;

    private SensorManager sensorManager;

    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 1000;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }

    private void advertisePanelRefreshRates() {
        RandrExtension randr = xServer.getExtension(RandrExtension.MAJOR_OPCODE);
        if (randr == null) return;

        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) return;

        java.util.TreeSet<Short> distinct =
                new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (android.view.Display.Mode mode : modes) {
            short hz = (short)Math.round(mode.getRefreshRate());
            if (hz > 0) distinct.add(hz);
        }
        if (distinct.isEmpty()) return;

        short[] rates = new short[distinct.size()];
        int index = 0;
        for (short hz : distinct) rates[index++] = hz;

        short activeRate = (short)Math.round(display.getRefreshRate());
        randr.setRefreshRates(rates, activeRate);
        Log.d("XServerDisplayActivity", "RandR advertising refresh rates "
                + java.util.Arrays.toString(rates) + ", active=" + activeRate);
    }

    private int frameGenSpinnerIndex() {
        if (activeLsfgMultiplier < 2) return 0;
        if (activeLsfgTargetRate > 0) return GraphicsSidebarPanelKt.FRAMEGEN_TARGET_INDEX;
        return Math.min(3, activeLsfgMultiplier - 1);
    }

    private float pickHighestRefreshRate() {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();

        float maxRefresh = 0f;

        for (android.view.Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefresh)
                maxRefresh = mode.getRefreshRate();
        }

        Log.d("XServerDisplayActivity", "Picking refresh rate " + maxRefresh);

        return maxRefresh;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        setContentView(R.layout.xserver_display_activity);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        if (preferences.getBoolean("high_refresh_rate_mode", false)) {
            android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredRefreshRate = pickHighestRefreshRate();
            refreshRate = params.preferredRefreshRate;
            getWindow().setAttributes(params);
        } else {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            refreshRate = display.getRefreshRate();
        }

        cursorLock = preferences.getBoolean("cursor_lock", true);

        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        startTime = System.currentTimeMillis();

        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener(
                (view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                openSidebarPanel(activeSidebarPanelId);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                onSidebarClosed();
            }
        });

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box64_logs", false);

        wireSidebarListeners(enableLogs);

        imageFs = ImageFs.find(this);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists())
                    eventFile.delete();
            }
        }

        winHandler = new WinHandler(this);
        wineRequestHandler = new WineRequestHandler(this);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);

        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");

        }

        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        incrementPlayCount();

        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        taskAffinityMask = (short) ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));

        String affinityCpuList = container.getCPUList(true);

        if (shortcut != null) {
            affinityCpuList = shortcut.getExtra("cpuList", container.getCPUList(true));
            taskAffinityMask = (short) ProcessHelper.getAffinityMask(affinityCpuList);
            taskAffinityMaskWoW64 = taskAffinityMask;
        }

        boolean syncCpuTopology = shortcut != null
                ? shortcut.getExtra("syncCpuTopology", container.isSyncCpuTopology() ? "1" : "").equals("1")
                : container.isSyncCpuTopology();

        wineCpuTopologyValue = "";
        if (syncCpuTopology && affinityCpuList != null && !affinityCpuList.isEmpty()) {
            int coreCount = affinityCpuList.split(",").length;
            wineCpuTopologyValue = coreCount + ":" + affinityCpuList;
        }

        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("imgVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        boolean enableWinlatorLogs = preferences.getBoolean("enable_winlator_logs", false);
        if (enableLogs || enableWinlatorLogs) DebugLogFile.setFilename(getExecutable());
        if (enableLogs) {
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        }
        if (enableWinlatorLogs) {
            winlatorLogcatLogger = new WinlatorLogcatLogger(DebugLogFile.getWinlatorLogFile(this));
            winlatorLogcatLogger.start();
            ProcessHelper.addDebugCallback(winlatorLogcatLogger);
        }

        graphicsDriver = container.getGraphicsDriver();
        graphicsWrapper = container.getGraphicsWrapper();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = Container.normalizeAudioDriver(container.getAudioDriver());
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        // Gamepad API (Exclusive Input + Enable XInput/DInput, shortcut overrides container).
        gamepadMode = GamepadMode.resolve(container, shortcut);
        winHandler.setGamepadEnabled(gamepadMode != GamepadMode.NONE);
        lc_all = container.getLC_ALL();

        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            shortcut.putExtra("lastRunAt", String.valueOf(System.currentTimeMillis()));
            shortcut.saveData();

            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsWrapper = Container.normalizeGraphicsWrapper(
                    shortcut.getExtra("graphicsWrapper", container.getGraphicsWrapper()));
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = Container.normalizeAudioDriver(shortcut.getExtra("audioDriver", container.getAudioDriver()));
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            midiSoundFont = shortcut.getExtra("midiSoundFont", container.getMIDISoundFont());
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness="
                        + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100 + ";" + "dlsDenoise="
                        + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }

            simulateTouchScreen = shortcut.getExtra("simTouchScreen").equals("1");
            isRelativeMouseMovement = shortcut.getExtra("enableRelativeMouse").equals("1");
            isMouseDisabled = shortcut.getExtra("disableMouse").equals("1");
            isTapToClickEnabled = !shortcut.getExtra("tapToClick").equals("0");
        }

        this.graphicsDriverConfig = GraphicsDriverConfig.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfig.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/"))
                    return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        boolean removeLoadingBarWhenBootingGames = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBarWhenBootingGames) preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        // Vulkan is now the only X-server renderer; XServer only needs the surface format
        // (still driven by the container/shortcut's surfaceFormat setting).
        xServer = new XServer(new ScreenInfo(screenSize), getSelectedSurfaceFormat());
        xServer.setWinHandler(winHandler);
        xServer.setRelativeMouseMovement(isRelativeMouseMovement);
        advertisePanelRefreshRates();

        boolean[] winStarted = { false };

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    if (isDrawnCursorVisible()) {
                        xServerView.setCursorVisible(true);
                    }
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }

                if (frameRatingWindowId == window.id) {
                    if (classicHud != null) classicHud.update();
                    if (modernHud != null) modernHud.onFrame();
                } else if (frameRatingWindowId == -1 && lastRendererName != null
                        && window.isApplicationWindow()
                        && ((modernHud != null && modernHud.isUserEnabled())
                         || (classicHud != null && classicHud.getVisibility() == View.VISIBLE))) {

                    frameRatingWindowId = window.id;
                    activeRendererWindowId = window.id;
                    if (xServerView != null) xServerView.setFpsWindowId(window.id);
                    if (classicHud != null) classicHud.update();
                    if (modernHud != null) modernHud.onFrame();
                }
            }

            @Override
            public void onMapWindow(Window window) {
                assignTaskAffinity(window);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }

            @Override
            public void onDestroyWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            try {
                final InputStream in;
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                } else {
                    in = null;
                }
                MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                    @Override
                    public void onSuccess(SF2Soundbank soundbank) {
                        midiHandler = new MidiHandler();
                        midiHandler.setSoundBank(soundbank);
                        midiHandler.start();
                    }

                    @Override
                    public void onFailed(Exception e) {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (Exception e2) {
                            }
                        }
                    }
                };
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    MidiManager.load(in, callback);
                } else {
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
                }
            } catch (Exception e) {
            }
        }

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        // Keep the keep-alive foreground service running for the whole session and switch its
        // (single) notification to the game. It used to be stopped here and replaced by a plain
        // notification, which left a minimized game unprotected. exit() restarts the process,
        // which ends the session mode with it.
        com.winlator.cmod.services.NotificationService.startSession(this);

        Runnable runnable = () -> {
            setupUI();
            setupSidebarInputControls();
            if (controlsProfile.isEmpty()) {

                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                if (!prepareContainerOverlay()) return;
                // Before Wine starts: link this game's save profile (Winlator/saves) into the container.
                SaveProfiles.apply(this, container, shortcut);
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                applyGameRefreshRateUnlock();
                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
            runnable.run();
    }

    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        containerId = Integer.parseInt(line.split(":")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    // Touchscreen: the finger is the cursor, so the drawn one is hidden. Trackpad and Off show it
    // (with the touch surface off, the cursor is moved by layout elements or a physical mouse).
    private boolean isDrawnCursorVisible() {
        return !(simulateTouchScreen && !isMouseDisabled);
    }

    private void handleCapturedPointer(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS: {
                int button = event.getActionButton();
                if (button == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (button == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (button == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                }
                break;
            }
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                int button = event.getActionButton();
                if (button == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (button == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (button == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE: {
                // Captured pointer: getX()/getY() are the device's relative counts. Scale them by
                // Cursor Speed and carry the fraction, so fine motion isn't rounded away.
                float[] p = XForm.transformPoint(xform, event.getX(), event.getY());
                capturedMouseMotion.add(p[0] * globalCursorSpeed, p[1] * globalCursorSpeed);
                int dx = capturedMouseMotion.x();
                int dy = capturedMouseMotion.y();
                if (dx == 0 && dy == 0) break;
                if (xServer.isRelativeMouseMovement())
                    winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                else
                    xServer.injectPointerMoveDelta(dx, dy);
                break;
            }
            case MotionEvent.ACTION_SCROLL: {
                // Same wheel path as an uncaptured mouse: accumulated, 120 per notch.
                touchpadView.injectWheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                break;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
        if (!isInPictureInPictureMode())
            ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        super.onPause();

        if (!isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }

            ProcessHelper.pauseAllWineProcesses();
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        int w = xServer.screenInfo.width;
        int h = xServer.screenInfo.height;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(w, h));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && xServerView != null) {
            int[] loc = new int[2];
            xServerView.getLocationOnScreen(loc);
            Rect hint = new Rect(loc[0], loc[1],
                    loc[0] + xServerView.getWidth(), loc[1] + xServerView.getHeight());
            builder.setSourceRectHint(hint);
        }
        enterPictureInPictureMode(builder.build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (xServerView == null) return;
        xServerView.setPipMode(isInPictureInPictureMode);
        if (!isInPictureInPictureMode) {
            xServerView.post(() -> {
                if (xServerView == null) return;
                int w = xServerView.getWidth();
                int h = xServerView.getHeight();
                if (w > 0 && h > 0) xServerView.onSurfaceChanged(w, h);
            });
        }
    }

    private void savePlaytimeData() {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        editor.apply();

        startTime = System.currentTimeMillis();
    }

    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.apply();
    }

    private void exit() {
        if (!exiting.compareAndSet(false, true)) return;
        boolean removeLoadingBar = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        View shutdownOverlay = removeLoadingBar ? null : ThemedLoadingOverlayHost.show(this, getString(R.string.shutdown));

        if (xServerView != null) {
            xServerView.forceCleanup();
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);

        if (midiHandler != null)
            midiHandler.stop();

        if (environment != null)
            environment.stopEnvironmentComponents();
        if (winHandler != null)
            winHandler.stop();
        if (wineRequestHandler != null)
            wineRequestHandler.stop();

        Executors.newSingleThreadExecutor().execute(() -> {

            ProcessHelper.terminateAllWineProcesses();

            long start = System.currentTimeMillis();
            while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= 1500) {

                    for (String pid : ProcessHelper.listRunningWineProcesses()) {
                        ProcessHelper.killProcess(Integer.parseInt(pid));
                    }
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            // Wine is stopped by now, so the save files are no longer being written.
            // The game's registry keys go to its save profile first, so a backup includes them.
            SaveRegistry.finishSession(this);
            if (shortcut != null && GameSaveManager.shouldAutoBackup(shortcut)) {
                GameSaveManager.BackupResult saveResult = GameSaveManager.backup(shortcut, true);
                if (saveResult.ok) {
                    Log.i("GameSaveManager", "Auto backup completed: " + saveResult.fileCount + " files");
                } else {
                    Log.w("GameSaveManager", "Auto backup skipped/failed: " + saveResult.error);
                }
            }

            runOnUiThread(() -> ThemedLoadingOverlayHost.dismiss(shutdownOverlay));
            runOnUiThread(() -> AppUtils.restartApplication(getApplicationContext()));
        });
    }

    @Override
    protected void onDestroy() {
        if (winlatorLogcatLogger != null) {
            ProcessHelper.removeDebugCallback(winlatorLogcatLogger);
            winlatorLogcatLogger.stop();
            winlatorLogcatLogger = null;
        }
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else
                drawerLayout.closeDrawers();
        }
    }

    private void showVibrationDialog() {
        if (winHandler == null)
            return;

        int maxControllers = winHandler.getMaxControllers();
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Boolean> initiallyChecked = new ArrayList<>();

        for (int i = 0; i < maxControllers; i++) {
            items.add(getString(R.string.vibration_slot, i + 1));
            initiallyChecked.add(winHandler.isVibrationEnabledForSlot(i));
        }

        ThemedAlertHost.multiChoice(
                this,
                getString(R.string.vibration),
                items,
                getString(R.string.ok),
                positions -> {
                    for (int i = 0; i < maxControllers; i++) {
                        winHandler.setVibrationEnabledForSlot(i, positions.contains(i));
                    }
                },
                initiallyChecked
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && cursorLock)
            touchpadView.requestPointerCapture();
        else if (!hasFocus)
            touchpadView.releasePointerCapture();
    }

    /** Builds the shared base prefix of the container's Wine version if needed. Runs before anything touches the prefix. */
    private boolean prepareContainerOverlay() {
        if (ContainerOverlay.prepare(this, contentsManager, container)) return true;
        runOnUiThread(() -> {
            Toast.makeText(this, "This container cannot be started: delete and recreate it (old full prefix), or the overlay library / base prefix is missing.", Toast.LENGTH_LONG).show();
            exit();
        });
        return false;
    }

    private void setupWineSystemFiles() {
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        String dxwrapper = this.dxwrapper;

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + dxwrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
            String ddrawrapper = dxwrapperConfig.get("ddrawrapper");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        }

        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents())
                : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme + "," + xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme + "," + xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);

        WineUtils.setJoystickRegistryKeys(container, gamepadMode);

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, startupSelection);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged)
            container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        envVars.put("LC_ALL", lc_all);
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels",
                AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all");

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());

        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut);
        guestProgramLauncherComponent.setGamepadEnabled(gamepadMode != GamepadMode.NONE);

        if (container != null) {
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);

            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            // A shortcut's own list replaces the container's one.
            envVars.putAll(shortcut != null ? shortcut.getEffectiveEnvVars(container) : container.getEnvVars());

            applyOpenGLDriverEnvVars();

            if (!wineCpuTopologyValue.isEmpty()) {
                envVars.put("WINE_CPU_TOPOLOGY", wineCpuTopologyValue);
            }

            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }
            if (!envVars.has("WINE_FAST_YIELD")) {
                envVars.put("WINE_FAST_YIELD", "1");
            }

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                            : container.getBox64Preset());

            guestProgramLauncherComponent.setFEXCorePreset(
                    shortcut != null
                            ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset())
                            : container.getFEXCorePreset());
        }

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear();
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));

        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        } else if (audioDriver.equals("pulse-audio-gn")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(this::exit));

        environment.addComponent(guestProgramLauncherComponent);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {

        }

        environment.startEnvironmentComponents();

        winHandler.start();

        if (wineRequestHandler != null)
            wineRequestHandler.start();

        dxwrapperConfig = null;

    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private String getSelectedSurfaceFormat() {
        if (shortcut != null) return shortcut.getSurfaceFormat();
        return container != null ? container.getSurfaceFormat() : "bgra8";
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);

        VulkanXServerView.loadNativeLibrary();
        xServerView = new VulkanXServerView(this, xServer);
        final XServerRendererView renderer = xServerView;
        renderer.setCursorVisible(false);

        {
            VulkanXServerView vkRenderer = (VulkanXServerView) renderer;

            // Opt-in Vulkan validation layer: WINLATOR_VULKAN_VALIDATION=1 in the container or
            // shortcut environment variables (needs libVkLayer_khronos_validation.so in the app).
            EnvVars vulkanStartupVars = new EnvVars();
            if (shortcut != null) vulkanStartupVars.putAll(shortcut.getEffectiveEnvVars(container));
            else if (container != null) vulkanStartupVars.putAll(container.getEnvVars());
            String validationValue = vulkanStartupVars.get("WINLATOR_VULKAN_VALIDATION");
            boolean validationEnabled = "1".equals(validationValue)
                    || "true".equalsIgnoreCase(validationValue)
                    || "yes".equalsIgnoreCase(validationValue);
            vkRenderer.setValidationEnabled(validationEnabled, AppUtils.getNativeLibDir(this));

            String rendererDriverId = shortcut != null ? shortcut.getRendererDriverId()
                    : (container != null ? container.getRendererDriverId() : "");
            if (rendererDriverId == null || rendererDriverId.isEmpty()) {
                rendererDriverId = graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null;
            }
            if (rendererDriverId != null && !rendererDriverId.isEmpty() && !rendererDriverId.equalsIgnoreCase("system")) {
                try {
                    String driverPath = getFilesDir().getAbsolutePath() + "/contents/adrenotools/" + rendererDriverId + "/";
                    AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
                    if (adrenotoolsManager.isFromResources(rendererDriverId) &&
                            !new File(driverPath).isDirectory()) {
                        adrenotoolsManager.extractDriverFromResources(rendererDriverId);
                    }
                    String libraryName = adrenotoolsManager.getLibraryName(rendererDriverId);
                    String nativeLibDir = AppUtils.getNativeLibDir(this);
                    if (!libraryName.isEmpty())
                        vkRenderer.setDriverInfo(driverPath, libraryName, nativeLibDir);
                } catch (Exception ignored) {
                }
            }

            String presentMode = shortcut != null ? shortcut.getRendererPresentMode()
                    : (container != null ? container.getRendererPresentMode() : "fifo");
            vkRenderer.setVkPresentMode(com.winlator.cmod.contentdialog.RendererOptions.toVkPresentMode(presentMode));
            vkRenderer.setFilterMode(shortcut != null ? shortcut.getRendererFilterMode()
                    : (container != null ? container.getRendererFilterMode() : 0));

            int lsfgMultiplier = shortcut != null ? shortcut.getLsfgMultiplier()
                    : container != null ? container.getLsfgMultiplier() : 0;
            java.io.File lsfgDll = LosslessDll.isGlobalDllAvailable(this) ? LosslessDll.globalDllFile(this)
                    : shortcut != null ? LosslessDll.containerDllFile(shortcut)
                    : LosslessDll.containerDllFile(container);
            float lsfgFlowScale = shortcut != null ? shortcut.getLsfgFlowScale()
                    : container != null ? container.getLsfgFlowScale() : 0.80f;
            int lsfgTargetRate = shortcut != null ? shortcut.getLsfgTargetRate()
                    : container != null ? container.getLsfgTargetRate() : 0;
            activeLsfgMultiplier = lsfgMultiplier;
            activeLsfgDll = lsfgDll;
            activeLsfgFlowScale = lsfgFlowScale;
            activeLsfgTargetRate = lsfgMultiplier < 2 ? 0 : lsfgTargetRate;
            int lsfgTargetMemo = shortcut != null ? shortcut.getLsfgTargetMemo()
                    : container != null ? container.getLsfgTargetMemo() : 0;
            // Saves from before the memo existed only stored the target while Adaptive was on.
            rememberedLsfgTargetRate = lsfgTargetMemo > 0 ? lsfgTargetMemo
                    : activeLsfgTargetRate > 0 ? activeLsfgTargetRate
                    : Container.DEFAULT_LSFG_TARGET_RATE;
            vkRenderer.setFrameGenRefreshRate(pickHighestRefreshRate());
            vkRenderer.setFrameGenNative(lsfgDll, lsfgMultiplier, lsfgFlowScale, activeLsfgTargetRate);
        }

        if (shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        rootView.addView(xServerView);

        globalCursorSpeed = clampSpeedPercent(Math.round(preferences.getFloat("cursor_speed", 1.0f) * 100f)) / 100f;
        touchpadView = new TouchpadView(this, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setTapToClickEnabled(isTapToClickEnabled);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.openDrawer(GravityCompat.START);
        });
        View.OnCapturedPointerListener capturedPointerListener = new View.OnCapturedPointerListener() {
            @Override
            public boolean onCapturedPointer(View view, MotionEvent event) {
                handleCapturedPointer(event);
                return true;
            }
        };
        touchpadView.setOnCapturedPointerListener(cursorLock ? capturedPointerListener : null);
        touchpadView.setFocusable(true);
        touchpadView.setFocusableInTouchMode(true);
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView
                .setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        isTouchscreenTimeout = preferences.getBoolean("touchscreen_timeout_enabled", false);
        inputControlsView.setAutoHideEnabled(isTouchscreenTimeout);
        rootView.addView(inputControlsView);

        if (container != null) {
            int hudMode = resolveHudMode();

            if (hudMode == 1) {

                classicHud = new FrameRating(this, graphicsDriverConfig);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
            } else if (hudMode == 2) {

                modernHud = new WinlatorHUD(this);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                modernHud.enableByUser();
                renderer.setFrameRating(modernHud);
            }
        }

        // getExtra() returns "" (never null) when the shortcut has no override, so an empty
        // value means "inherit the container's setting".
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : "";

        boolean shouldStretch = false;

        if (shortcutFullscreenStretched != null && !shortcutFullscreenStretched.isEmpty()) {

            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {

            shouldStretch = true;
        }

        if (shouldStretch) {

            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null)
                    showInputControls(profile);
            }

            // The shortcut's Touch Mode picks the starting mode: "simTouchScreen" = Trackpad or
            // Touchscreen, "disableMouse" = Off (read with isMouseDisabled above). The sidebar can
            // switch it for the session.
            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchMode = simTouchScreen.equals("1") ? TouchpadView.MODE_TOUCHSCREEN : TouchpadView.MODE_TRACKPAD;
            touchpadView.setTouchMode(touchMode);
            if (!isDrawnCursorVisible()) {
                renderer.setCursorVisible(false);
            }
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        setupSidebarHudControls();
        setupSidebarGraphicsControls();
    }

    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                editorResultProfileId = data != null ? data.getIntExtra("profile_id", -1) : -1;
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            });

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void wireSidebarListeners(boolean enableLogs) {

        // The whole sidebar (card, rail and all five panels) is one Compose composition —
        // see IngameSidebar.kt. Panels register into it via their XxxPanelHost.attach(
        // ingameSidebar, R.id.LLSubXxx, ...) calls below and in setupSidebar*Controls().
        ComposeView sidebarView = findViewById(R.id.IngameSidebarCompose);
        if (sidebarView != null) {
            List<SidebarRailItemData> railItems = Arrays.asList(
                    new SidebarRailItemData(R.id.LLSubGraphics, R.drawable.ic_sidebar_gpu, "Graphics"),
                    new SidebarRailItemData(R.id.LLSubScreen, R.drawable.ic_sidebar_effects, "Screen"),
                    new SidebarRailItemData(R.id.LLSubInput, R.drawable.icon_mouse, "Input"),
                    new SidebarRailItemData(R.id.LLSubFPS, R.drawable.ic_sidebar_performance, "HUD"),
                    new SidebarRailItemData(R.id.LLSubTaskManager, R.drawable.ic_sidebar_task_manager, "Task Manager")
            );
            ingameSidebar = IngameSidebarHost.attach(sidebarView, railItems, R.id.LLSubFPS, new SidebarRailCallbacks() {
                @Override
                public void onSelect(int subId) {
                    openSidebarPanel(subId);
                }

                @Override
                public void onPauseToggle() {
                    if (isPaused) {
                        ProcessHelper.resumeAllWineProcesses();
                    } else {
                        ProcessHelper.pauseAllWineProcesses();
                    }
                    isPaused = !isPaused;
                    if (sidebarRailState != null) sidebarRailState.setPaused(isPaused);
                    drawerLayout.closeDrawers();
                    WinToast.show(XServerDisplayActivity.this,
                            isPaused ? "Game paused. Open the side menu to resume." : "Game resumed",
                            Toast.LENGTH_SHORT);
                }

                @Override
                public void onExit() {
                    drawerLayout.closeDrawers();
                    // Same themed confirm as Task Manager's End Process, red confirm button.
                    ThemedAlertHost.confirm(XServerDisplayActivity.this, getString(R.string.exit_game),
                            getString(R.string.do_you_want_to_exit_game), getString(R.string.exit),
                            () -> exit(), true);
                }
            });
            sidebarRailState = ingameSidebar.getRailState();
        }
        openSidebarPanel(R.id.LLSubFPS);

        setupSidebarInputControls();


        if (ingameSidebar != null) {
            ScreenSidebarPanelHost.attach(ingameSidebar, R.id.LLSubScreen, new ScreenPanelCallbacks() {
                @Override
                public void onPipMode() {
                    enterPipMode();
                    drawerLayout.closeDrawers();
                }

                @Override
                public void onToggleFullscreen() {
                    if (xServerView != null) {
                        xServerView.toggleFullscreen();
                        if (touchpadView != null)
                            touchpadView.toggleFullscreen();
                    }
                    drawerLayout.closeDrawers();
                }

                @Override
                public void onMagnifier() {
                    if (xServerView != null) {
                        final XServerRendererView renderer = xServerView;
                        if (magnifierView == null) {
                            FrameLayout flContainer = findViewById(R.id.FLXServerDisplay);
                            magnifierView = new MagnifierView(XServerDisplayActivity.this);
                            magnifierView.setZoomButtonCallback(value -> {
                                renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                                magnifierView.setZoomValue(renderer.getMagnifierZoom());
                            });
                            magnifierView.setZoomValue(renderer.getMagnifierZoom());
                            magnifierView.setHideButtonCallback(() -> {
                                flContainer.removeView(magnifierView);
                                magnifierView = null;
                            });
                            flContainer.addView(magnifierView);
                        }
                    }
                    drawerLayout.closeDrawers();
                }

                @Override
                public void onSoftStretch(boolean enabled) {
                    if (xServerView != null) {
                        XServerRendererView rendererRef = xServerView;
                        if (enabled && !rendererRef.isFullscreen()) {
                            rendererRef.toggleFullscreen();
                            if (touchpadView != null) touchpadView.toggleFullscreen();
                        }
                        // Vulkan is the only renderer now, so rendererRef is always a VulkanXServerView.
                        ((VulkanXServerView) rendererRef).setStretchMode(enabled ? 1 : 0);
                    }
                    drawerLayout.closeDrawers();
                }
            });
        }

        if (ingameSidebar != null) {
            TaskManagerPanelState taskManagerState = TaskManagerPanelHost.attach(ingameSidebar, R.id.LLSubTaskManager, new TaskManagerCallbacks() {
                @Override
                public void onNewTask() {
                    ThemedAlertHost.prompt(XServerDisplayActivity.this, getString(R.string.new_task), "taskmgr.exe",
                            getString(R.string.ok), command -> winHandler.exec(command));
                }

                @Override
                public void onBringToFront(int pid, String name) {
                    winHandler.bringToFront(name);
                }

                @Override
                public void onEndProcess(int pid, String name) {
                    ThemedAlertHost.confirm(XServerDisplayActivity.this, getString(R.string.end_process),
                            getString(R.string.do_you_want_to_end_this_process), getString(R.string.ok),
                            () -> {
                                winHandler.killProcess(name);
                                WinToast.show(XServerDisplayActivity.this, "Ending " + name, Toast.LENGTH_SHORT);
                            }, true);
                }

                @Override
                public void onProcessorAffinity(int pid, String name, int affinityMask) {
                    // Same fallback the old dialog used: Wine's reported affinity mask is
                    // unreliable after SetProcessAffinityMask, so prefer the Linux /proc read
                    // whenever it returns something.
                    String cpuList = new ProcessInfo(pid, name, 0, affinityMask, false).getCPUList();
                    int linuxMask = ProcessHelper.getProcessAffinityMask(pid);
                    if (linuxMask != 0) {
                        cpuList = new ProcessInfo(pid, "", 0, linuxMask, false).getCPUList();
                    }

                    int numProcessors = Runtime.getRuntime().availableProcessors();
                    List<String> checkedCpus = Arrays.asList(cpuList.split(","));
                    List<String> entries = new ArrayList<>();
                    List<Boolean> initiallyChecked = new ArrayList<>();
                    for (int i = 0; i < numProcessors; i++) {
                        entries.add("CPU" + i);
                        initiallyChecked.add(checkedCpus.contains(String.valueOf(i)));
                    }

                    ThemedAlertHost.multiChoice(XServerDisplayActivity.this, getString(R.string.processor_affinity),
                            entries, getString(R.string.ok), positions -> {
                                int mask = 0;
                                for (int position : positions) mask |= (1 << position);
                                winHandler.setProcessAffinity(pid, mask);
                                if (taskManagerSidebar != null) taskManagerSidebar.updateNow();
                                WinToast.show(XServerDisplayActivity.this, "Affinity updated for " + name, Toast.LENGTH_SHORT);
                            }, initiallyChecked);
                }
            });
            taskManagerSidebar = new TaskManagerSidebar(this, taskManagerState);
        }
    }

    private int activeSidebarPanelId = R.id.LLSubFPS;

    // Drawer closed: nothing to hide anymore (the single sidebar composition just stays
    // composed off-screen) — only the Task Manager's 1s process poll needs to stop.
    private void onSidebarClosed() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        // The combined Pause / Exit cell always reopens on Pause.
        if (sidebarRailState != null) sidebarRailState.setExitMode(false);
        // Drop focus from a sidebar text field so it applies its value, the IME goes away and
        // key events return to the X server.
        if (com.winlator.cmod.ui.SidebarTextInputFocus.active) {
            View focus = getCurrentFocus();
            if (focus != null) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                focus.clearFocus();
            }
            com.winlator.cmod.ui.SidebarTextInputFocus.active = false;
        }
    }

    private boolean isSidebarTextInputActive() {
        return com.winlator.cmod.ui.SidebarTextInputFocus.active
                && drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START);
    }

    // Selection + the panel switch animation are both driven by sidebarRailState.selectedId
    // inside IngameSidebar.kt (AnimatedContent). The Task Manager poll is started/stopped
    // here for every path that shows a panel — rail tap AND drawer re-open. Previously
    // only the rail tap started it, so re-opening the drawer on Task Manager showed a
    // frozen list (onDrawerClosed had stopped the poll and nothing restarted it).
    private void openSidebarPanel(int subId) {
        if (taskManagerSidebar != null) {
            if (subId == R.id.LLSubTaskManager) taskManagerSidebar.start();
            else taskManagerSidebar.stop();
        }
        if (sidebarRailState != null) sidebarRailState.setSelectedId(subId);
        activeSidebarPanelId = subId;
    }

    private void setupSidebarHudControls() {
        if (ingameSidebar == null) return;

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box64_logs", false);

        int currentMode = 0;
        if      (modernHud  != null) currentMode = 2;
        else if (classicHud != null) currentMode = 1;
        else if (container  != null) {
            currentMode = resolveHudMode();
        }

        boolean hudOn    = currentMode != 0;
        boolean isModern = currentMode == 2;

        HudPanelCallbacks callbacks = new HudPanelCallbacks() {
            @Override
            public void onHudMasterToggled(boolean enabled, boolean styleIsModern) {
                if (enabled) {
                    int style = styleIsModern ? 2 : 1;
                    enableHudLazily(style);
                    saveHudModeToContainer(style);
                } else {
                    if (classicHud != null) classicHud.disableByUser();
                    if (modernHud  != null) modernHud.disableByUser();
                    saveHudModeToContainer(0);
                }
            }

            @Override
            public void onStyleChanged(boolean isModern) {
                int newStyle = isModern ? 2 : 1;
                if (classicHud != null) classicHud.disableByUser(false);
                if (modernHud  != null) modernHud.disableByUser(false);
                enableHudLazily(newStyle);
                saveHudModeToContainer(newStyle);
            }

            @Override
            public void onHudScale(int percent, boolean commit, boolean isModern) {
                float scale = 1f + (percent - 50f) / 50f;
                if (isModern) {
                    if (modernHud != null) modernHud.setHudScale(scale, commit);
                } else if (classicHud != null) {
                    classicHud.setHudScale(scale, commit);
                }
            }

            @Override
            public void onHudAlpha(int percent, boolean commit, boolean isModern) {
                if (isModern) {
                    if (modernHud != null) modernHud.setHudAlpha(percent / 100f, commit);
                } else if (classicHud != null) {
                    classicHud.setHudAlpha(percent / 100f, commit);
                }
            }

            @Override
            public void onResetHudLayout(boolean isModern) {
                if (isModern) {
                    // Layout, every metric checkbox and the dual-cell correction.
                    if (modernHud != null) modernHud.resetLayout();
                    else {
                        WinlatorHUD.resetSavedLayout(XServerDisplayActivity.this);
                        WinlatorHUD.resetSavedMetrics(XServerDisplayActivity.this);
                    }
                } else if (classicHud != null) {
                    classicHud.resetLayout();
                } else {
                    FrameRating.resetSavedLayout(XServerDisplayActivity.this);
                }
                WinToast.show(XServerDisplayActivity.this, "HUD layout reset", Toast.LENGTH_SHORT);
            }

            @Override
            public void onShowLogs() {
                if (debugDialog != null) debugDialog.show();
                drawerLayout.closeDrawers();
            }
        };

        int savedHudScalePercent = WinlatorHUD.getSavedScalePercent(this);
        int savedHudAlphaPercent = WinlatorHUD.getSavedAlphaPercent(this);
        HudPanelState state = new HudPanelState(hudOn, isModern, savedHudScalePercent, savedHudAlphaPercent,
                FrameRating.getSavedScalePercent(this), FrameRating.getSavedAlphaPercent(this), enableLogs);
        HudSidebarPanelHost.attach(ingameSidebar, R.id.LLSubFPS, state, callbacks);
    }

    private void enableHudLazily(int style) {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (rootView == null || xServerView == null) return;
        final XServerRendererView renderer = xServerView;

        boolean rendererAlreadyActive = (activeRendererWindowId != -1);

        if (style == 2) {
            if (modernHud == null) {
                modernHud = new WinlatorHUD(this);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                renderer.setFrameRating(modernHud);
                if (rendererAlreadyActive) {

                    frameRatingWindowId = activeRendererWindowId;
                    renderer.setFpsWindowId(frameRatingWindowId);

                    final String name = lastRendererName;
                    modernHud.onRendererDetected(name);
                }
            }
            modernHud.enableByUser();
        } else {
            if (classicHud == null) {
                classicHud = new FrameRating(this, graphicsDriverConfig);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
                if (rendererAlreadyActive) {
                    frameRatingWindowId = activeRendererWindowId;
                    renderer.setFpsWindowId(frameRatingWindowId);
                    runOnUiThread(() -> classicHud.update());
                }
            }
            classicHud.enableByUser();
        }
    }

    /** HUD mode for this session: the shortcut's own value if set, otherwise the container's. */
    private int resolveHudMode() {
        if (shortcut != null) {
            String own = shortcut.getExtra("hudMode");
            if (!own.isEmpty()) {
                try { return Integer.parseInt(own); } catch (NumberFormatException ignored) {}
            }
        }
        if (container == null) return 0;
        String extra = container.getExtra("hudMode");
        if (!extra.isEmpty()) {
            try { return Integer.parseInt(extra); } catch (NumberFormatException ignored) {}
        }
        return container.isShowFPS() ? 1 : 0;
    }

    /** Persists the in-game HUD choice where it came from: the shortcut when launched from one. */
    private void saveHudModeToContainer(int mode) {
        if (shortcut != null) {
            shortcut.putExtra("hudMode", String.valueOf(mode));
            shortcut.saveData();
            return;
        }
        if (container == null) return;
        container.putExtra("hudMode", String.valueOf(mode));
        container.setShowFPS(mode != 0);
        container.saveData();
    }

    private void setupSidebarGraphicsControls() {
        if (ingameSidebar == null) return;

        // Vulkan is the only renderer now, so renderer (when set) is always a VulkanXServerView.
        final XServerRendererView renderer = xServerView;
        final VulkanXServerView vkRenderer = renderer != null ? (VulkanXServerView) renderer : null;

        int initialFpsLimit = loadFpsLimit();
        if (vkRenderer != null) vkRenderer.setFpsLimit(initialFpsLimit);

        // FSR/Upscaler/PostFX/Sharpness prefer the per-shortcut value over the container-wide
        // one, exactly like SidebarCleanupView's bolted-on autosave used to (it re-read these
        // same three keys from the Shortcut ~650ms after setup and overrode whatever the
        // container-based read had already shown). Doing the shortcut-aware read here up
        // front avoids that startup flicker instead of reproducing it.
        String savedFilter = readGraphicsExtra("graphicsFilterMode");
        boolean fsrOn = !savedFilter.isEmpty() && Integer.parseInt(savedFilter) > 0;
        int upscalerModeIndex = !savedFilter.isEmpty() ? Math.max(0, Integer.parseInt(savedFilter) - 2) : 0;
        graphicsFsrEnabled = fsrOn;
        graphicsUpscalerModeIndex = upscalerModeIndex;

        String savedSharp = readGraphicsExtra("graphicsSharpness");
        int initSharp = savedSharp.isEmpty() ? 50 : Math.round(Float.parseFloat(savedSharp));
        graphicsSharpnessPercent = initSharp;

        String savedPFX = readGraphicsExtra("graphicsPostFXMode");
        int initPFX = savedPFX.isEmpty() ? 0 : Integer.parseInt(savedPFX);
        if (initPFX < 0 || initPFX >= 5) initPFX = 0;
        graphicsPostFxModeIndex = initPFX;

        if (vkRenderer != null) {
            if (fsrOn) vkRenderer.setFilterMode(upscalerModeIndex + 2);
            vkRenderer.setSharpness(initSharp / 100f);
            if (initPFX > 0) vkRenderer.setPostFXMode(initPFX);
        }

        GraphicsPanelState state = new GraphicsPanelState(
                initialFpsLimit,
                fsrOn,
                upscalerModeIndex,
                initSharp,
                initPFX,
                0,  // ReShade never persisted anything in the original either — always starts Off
                graphicsReshadeStrength,
                vkRenderer != null,
                frameGenSpinnerIndex(),
                activeLsfgFlowScale,
                rememberedLsfgTargetRate
        );

        GraphicsPanelCallbacks callbacks = new GraphicsPanelCallbacks() {
            @Override
            public void onFpsLimitChanged(int fps) {
                saveFpsLimit(fps);
                if (vkRenderer != null) vkRenderer.setFpsLimit(fps);
            }

            @Override
            public void onFsrToggled(boolean enabled) {
                graphicsFsrEnabled = enabled;
                if (vkRenderer != null) {
                    vkRenderer.setFilterMode(enabled
                            ? graphicsUpscalerModeIndex + 2
                            : (container != null ? container.getRendererFilterMode() : 0));
                }
                autosaveGraphicsSettings();
            }

            @Override
            public void onUpscalerModeChanged(int index) {
                graphicsUpscalerModeIndex = index;
                if (vkRenderer != null && graphicsFsrEnabled) vkRenderer.setFilterMode(index + 2);
                autosaveGraphicsSettings();
            }

            @Override
            public void onSharpnessChanged(int percent) {
                graphicsSharpnessPercent = percent;
                if (vkRenderer != null) vkRenderer.setSharpness(percent / 100f);
            }

            @Override
            public void onSharpnessCommitted(int percent) {
                graphicsSharpnessPercent = percent;
                autosaveGraphicsSettings();
            }

            @Override
            public void onPostFxModeChanged(int index) {
                graphicsPostFxModeIndex = index;
                if (vkRenderer != null) vkRenderer.setPostFXMode(index);
                autosaveGraphicsSettings();
            }

            @Override
            public void onReshadeEffectChanged(int index) {
                // Mirrors ReshadeSidebarPanelView.applyEffect() exactly, including the
                // effect-mode lookup table. ReShade itself is still never persisted — the
                // original ReshadeSidebarPanelView never wrote to Container or Shortcut
                // either, so this is session-only by design, not an oversight.
                if (vkRenderer == null) return;
                int[] effectModes = {0, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
                if (index <= 0 || index >= effectModes.length) {
                    vkRenderer.setPostFXMode(0);
                    return;
                }
                vkRenderer.setFilterMode(0);
                vkRenderer.setSharpness(graphicsReshadeStrength / 100f);
                vkRenderer.setPostFXMode(effectModes[index]);
            }

            @Override
            public void onReshadeStrengthChanged(int percent) {
                graphicsReshadeStrength = percent;
                if (vkRenderer != null) vkRenderer.setSharpness(percent / 100f);
            }

            @Override
            public void onFrameGenChanged(int multiplierIndex) {
                // 0 = Off, 1..3 = fixed 2x..4x, 4 = LSFG Adaptive FPS. Adaptive keeps frame
                // generation armed at the largest multiplier (the pacer decides per frame how
                // many to generate to hit the target); the other modes clear the target.
                if (multiplierIndex == GraphicsSidebarPanelKt.FRAMEGEN_TARGET_INDEX) {
                    activeLsfgMultiplier = 4;
                    activeLsfgTargetRate = rememberedLsfgTargetRate;
                } else {
                    activeLsfgMultiplier = multiplierIndex < 1 ? 0 : multiplierIndex + 1;
                    activeLsfgTargetRate = 0;
                }
                if (shortcut != null) {
                    shortcut.setLsfgMultiplier(activeLsfgMultiplier);
                    shortcut.setLsfgTargetRate(activeLsfgTargetRate);
                    shortcut.setLsfgTargetMemo(rememberedLsfgTargetRate);
                    shortcut.saveData();
                } else if (container != null) {
                    container.setLsfgMultiplier(activeLsfgMultiplier);
                    container.setLsfgTargetRate(activeLsfgTargetRate);
                    container.setLsfgTargetMemo(rememberedLsfgTargetRate);
                    container.saveData();
                }
                if (vkRenderer != null)
                    vkRenderer.setFrameGenNative(activeLsfgDll, activeLsfgMultiplier,
                            activeLsfgFlowScale, activeLsfgTargetRate);
            }

            @Override
            public void onFrameGenTargetFpsChanged(int fps) {
                int clamped = Math.max(0, Math.min(fps, 1000));
                if (clamped <= 0) return;
                if (clamped == rememberedLsfgTargetRate) return;
                rememberedLsfgTargetRate = clamped;
                // Always persist the memo; only the effective target (and native) follow while
                // Adaptive is the active mode.
                boolean adaptive = activeLsfgTargetRate > 0;
                if (adaptive) activeLsfgTargetRate = clamped;
                if (shortcut != null) {
                    if (adaptive) shortcut.setLsfgTargetRate(activeLsfgTargetRate);
                    shortcut.setLsfgTargetMemo(rememberedLsfgTargetRate);
                    shortcut.saveData();
                } else if (container != null) {
                    if (adaptive) container.setLsfgTargetRate(activeLsfgTargetRate);
                    container.setLsfgTargetMemo(rememberedLsfgTargetRate);
                    container.saveData();
                }
                if (adaptive && vkRenderer != null)
                    vkRenderer.setFrameGenNative(activeLsfgDll, activeLsfgMultiplier,
                            activeLsfgFlowScale, activeLsfgTargetRate);
            }

            @Override
            public void onFrameGenFlowScaleChanged(float scale) {
                if (Math.abs(scale - activeLsfgFlowScale) < 0.001f) return;
                activeLsfgFlowScale = scale;
                if (shortcut != null) {
                    shortcut.setLsfgFlowScale(activeLsfgFlowScale);
                    shortcut.saveData();
                } else if (container != null) {
                    container.setLsfgFlowScale(activeLsfgFlowScale);
                    container.saveData();
                }
                if (vkRenderer != null)
                    vkRenderer.setFrameGenNative(activeLsfgDll, activeLsfgMultiplier,
                            activeLsfgFlowScale, activeLsfgTargetRate);
            }

        };

        GraphicsSidebarPanelHost.attach(ingameSidebar, R.id.LLSubGraphics, state, callbacks);
    }

    // Shortcut-preferred, Container-fallback read — same priority SidebarCleanupView's
    // restoreShortcutGraphics() used (only it did this ~650ms after showing the Container
    // value first). Empty on both means "no saved value", handled by each caller's default.
    private String readGraphicsExtra(String key) {
        if (shortcut != null) {
            String value = shortcut.getExtra(key, "");
            if (!value.isEmpty()) return value;
        }
        return container != null ? container.getExtra(key, "") : "";
    }

    // Same shape as SidebarCleanupView's saveGraphicsState(): writes all three settings
    // together, to the Shortcut when one exists so each shortcut keeps its own graphics
    // preset, falling back to Container otherwise.
    private void autosaveGraphicsSettings() {
        String filter = String.valueOf(graphicsFsrEnabled ? graphicsUpscalerModeIndex + 2 : 0);
        String sharp = String.valueOf(graphicsSharpnessPercent);
        String post = String.valueOf(graphicsPostFxModeIndex);

        if (shortcut != null) {
            shortcut.putExtra("graphicsFilterMode", filter);
            shortcut.putExtra("graphicsSharpness", sharp);
            shortcut.putExtra("graphicsPostFXMode", post);
            shortcut.putExtra("graphicsColorMode", "0");
            shortcut.saveData();
        } else if (container != null) {
            container.putExtra("graphicsFilterMode", filter);
            container.putExtra("graphicsSharpness", sharp);
            container.putExtra("graphicsPostFXMode", post);
            container.putExtra("graphicsColorMode", "0");
            container.saveData();
        }
    }

    private int loadFpsLimit() {
        String savedLimit, oldPreset, oldEnabled;
        if (shortcut != null) {
            savedLimit = shortcut.getExtra("nativeFpsLimit", "");
            oldPreset = shortcut.getExtra("graphicsFpsPreset", "");
            oldEnabled = shortcut.getExtra("nativeFpsLimiterEnabled", "");
            if (savedLimit.isEmpty() && oldPreset.isEmpty() && oldEnabled.isEmpty() && container != null) {
                savedLimit = container.getExtra("nativeFpsLimit", "");
                oldPreset = container.getExtra("graphicsFpsPreset", "");
                oldEnabled = container.getExtra("nativeFpsLimiterEnabled", "");
            }
        } else if (container != null) {
            savedLimit = container.getExtra("nativeFpsLimit", "");
            oldPreset = container.getExtra("graphicsFpsPreset", "");
            oldEnabled = container.getExtra("nativeFpsLimiterEnabled", "");
        } else {
            return 0;
        }

        int limit = parsePositiveOrZero(savedLimit);
        if (savedLimit.isEmpty()) {
            int oldIndex = parsePositiveOrZero(oldPreset);
            int[] oldValues = {0, 30, 60, 90, 120};
            limit = oldIndex >= 0 && oldIndex < oldValues.length ? oldValues[oldIndex] : 0;
        }
        if ("0".equals(oldEnabled)) limit = 0;
        return limit;
    }

    private void saveFpsLimit(int fps) {
        if (shortcut != null) {
            shortcut.putExtra("nativeFpsLimiterEnabled", null);
            shortcut.putExtra("nativeFpsLimit", String.valueOf(fps));
            shortcut.saveData();
        } else if (container != null) {
            container.putExtra("nativeFpsLimiterEnabled", null);
            container.putExtra("nativeFpsLimit", String.valueOf(fps));
            container.saveData();
        }
    }

    private int parsePositiveOrZero(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void setupSidebarInputControls() {
        if (inputControlsView == null || inputControlsManager == null) return;

        if (ingameSidebar == null) return;

        InputPanelCallbacks callbacks = new InputPanelCallbacks() {
            // Mirrors the old applySidebarInputControls(): always re-reads/re-persists the
            // full snapshot of profile + switches, whichever one just changed.
            @Override
            public void onControlsSettingsChanged(int profileId, boolean showTouchscreenControls,
                                                   boolean touchscreenHaptics) {
                inputControlsView.setShowTouchscreenControls(showTouchscreenControls);

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
                int position = -1;
                for (int i = 0; i < profiles.size(); i++) {
                    if (profiles.get(i).id == profileId) {
                        position = i;
                        break;
                    }
                }

                SharedPreferences.Editor editor = preferences.edit();
                editor.putBoolean("show_touchscreen_controls_enabled", showTouchscreenControls);
                editor.putBoolean("touchscreen_haptics_enabled", touchscreenHaptics);
                editor.putInt("selected_profile_index", position);
                editor.apply();

                if (position >= 0) {
                    showInputControls(profiles.get(position));
                } else {
                    hideInputControls();
                }
            }

            @Override
            public void onTouchscreenTimeout(boolean enabled) {
                isTouchscreenTimeout = enabled;
                inputControlsView.setAutoHideEnabled(enabled);
                preferences.edit().putBoolean("touchscreen_timeout_enabled", enabled).apply();
            }

            @Override
            public void onEditProfiles(int selectedProfileId) {
                // A profile is active -> straight into the controls editor on it (it can switch
                // profiles itself). Controls Disabled -> the Input Controls screen, as before.
                Intent intent;
                if (selectedProfileId >= 0 && inputControlsManager.getProfile(selectedProfileId) != null) {
                    intent = new Intent(XServerDisplayActivity.this, ControlsEditorActivity.class);
                    intent.putExtra("profile_id", selectedProfileId);
                } else {
                    intent = new Intent(XServerDisplayActivity.this, MainActivity.class);
                    intent.putExtra("edit_input_controls", true);
                    intent.putExtra("selected_profile_id", selectedProfileId >= 0 ? selectedProfileId : 0);
                }
                editorResultProfileId = -1;
                editInputControlsCallback = () -> {
                    // Keep a layout active after editing: the one the editor ended on (it can
                    // switch profiles in place), else the one active before. Profiles are
                    // reloaded first so the edits apply; if that profile was deleted,
                    // onControlsSettingsChanged finds no match and falls back to Disabled.
                    int profileId = editorResultProfileId >= 0 ? editorResultProfileId : selectedProfileId;
                    editorResultProfileId = -1;
                    // Read before hideInputControls(), which forces "show controls" back on.
                    boolean showControls = inputControlsView.isShowTouchscreenControls();
                    hideInputControls();
                    inputControlsManager.loadProfiles(true);
                    // Built-in icons are cached by id; the editor can override them
                    // (custom_icons/override_<id>.png), so drop the cache to pick that up.
                    inputControlsView.invalidateIconCache();
                    onControlsSettingsChanged(
                            profileId,
                            showControls,
                            preferences.getBoolean("touchscreen_haptics_enabled", false)
                    );
                    refreshInputPanel.run();
                };
                controlsEditorActivityResultLauncher.launch(intent);
            }

            @Override
            public void onControlsOpacity(int percent, boolean commit) {
                float opacity = percent / 100f;
                if (commit) preferences.edit().putFloat("overlay_opacity", opacity).apply();
                inputControlsView.setOverlayOpacity(opacity);
                inputControlsView.invalidate();
            }

            @Override
            public void onShowKeyboard() {
                AppUtils.showKeyboard(XServerDisplayActivity.this);
                drawerLayout.closeDrawers();
            }

            @Override
            public void onVibration() {
                showVibrationDialog();
                drawerLayout.closeDrawers();
            }

            @Override
            public void onTouchMode(int mode) {
                if (mode == TouchpadView.MODE_OFF) {
                    // Touch surface off; the last Trackpad/Touchscreen choice is kept for when a
                    // mode is picked again.
                    isMouseDisabled = true;
                    if (touchpadView != null) touchpadView.setMouseEnabled(false);
                } else {
                    isMouseDisabled = false;
                    touchMode = mode == TouchpadView.MODE_TOUCHSCREEN ? TouchpadView.MODE_TOUCHSCREEN : TouchpadView.MODE_TRACKPAD;
                    simulateTouchScreen = touchMode == TouchpadView.MODE_TOUCHSCREEN;
                    if (touchpadView != null) {
                        touchpadView.setMouseEnabled(true);
                        touchpadView.setTouchMode(touchMode);
                    }
                }
                if (xServerView != null) xServerView.setCursorVisible(isDrawnCursorVisible());
            }

            @Override
            public void onCursorSpeed(int percent, boolean commit) {
                globalCursorSpeed = clampSpeedPercent(percent) / 100f;
                if (touchpadView != null) touchpadView.setSensitivity(globalCursorSpeed);
                // Same global setting as Settings > Cursor speed.
                if (commit) preferences.edit().putFloat("cursor_speed", globalCursorSpeed).apply();
            }

            @Override
            public void onTapToClick(boolean enabled) {
                isTapToClickEnabled = enabled;
                if (touchpadView != null) touchpadView.setTapToClickEnabled(enabled);
            }

            @Override
            public void onRelativeMouse(boolean enabled) {
                isRelativeMouseMovement = enabled;
                if (xServer != null)
                    xServer.setRelativeMouseMovement(isRelativeMouseMovement);
            }
        };

        refreshInputPanel = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<InputProfileOption> profileOptions = new ArrayList<>();
            int selectedProfileId = -1;
            for (ControlsProfile profile : profiles) {
                profileOptions.add(new InputProfileOption(profile.id, profile.getName()));
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedProfileId = profile.id;
            }

            InputPanelState state = new InputPanelState(
                    profileOptions,
                    selectedProfileId,
                    inputControlsView.isShowTouchscreenControls(),
                    isTouchscreenTimeout,
                    preferences.getBoolean("touchscreen_haptics_enabled", false),
                    Math.round(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY) * 100f),
                    isRelativeMouseMovement,
                    isMouseDisabled ? TouchpadView.MODE_OFF : touchMode,
                    Math.round(globalCursorSpeed * 100f),
                    isTapToClickEnabled
            );
            InputSidebarPanelHost.attach(ingameSidebar, R.id.LLSubInput, state, callbacks);
        };
        refreshInputPanel.run();
    }

    /** Cursor Speed uses the Settings range: 10..200 %. */
    private static int clampSpeedPercent(int percent) {
        return Math.max(10, Math.min(200, percent));
    }

    private void simulateConfirmInputControlsDialog() {

        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", false);

        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        int selectedProfileIndex = preferences.getInt("selected_profile_index", -1);

        if (selectedProfileIndex >= 0 && selectedProfileIndex < inputControlsManager.getProfiles().size()) {

            ControlsProfile profile = inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {

            hideInputControls();
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setProfile(profile);
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();

        // One global Cursor Speed for touch and for the profile's stick/button mouse moves.
        touchpadView.setSensitivity(globalCursorSpeed);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        // No controls any more: no pointer may stay on the touchpad's ignore list.
        touchpadView.setPointerIdsToIgnore(java.util.Collections.emptySet());

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    static String resolveGraphicsWrapperArchiveName(String graphicsWrapper) {
        // assets/graphics_driver/<name>.tzst; the name is also stored in the ImageFS variant marker.
        if (Container.ADRENO_GRAPHICS_WRAPPER.equals(graphicsWrapper)) return "wrapper_adreno";
        if (Container.LEGACY_GRAPHICS_WRAPPER.equals(graphicsWrapper)) return "wrapper_legacy";
        return "wrapper_default";
    }

    private String getSelectedOpenGLDriver() {
        return "freedreno".equalsIgnoreCase(graphicsDriver) ? "freedreno" : "zink";
    }

    // Patch component of the driver's Vulkan version ("1.3.<patch>"). GPUInformation returns
    // "Unknown" when the query fails (driver not installed/broken meta.json); splitting that and
    // taking [2] used to throw ArrayIndexOutOfBoundsException and abort the container launch.
    // Falls back to the system driver's patch, then to 0.
    private String resolveVulkanPatchVersion(String driverId) {
        String patch = vulkanPatchOf(GPUInformation.getVulkanVersion(driverId, this));
        if (patch == null && !"System".equals(driverId)) {
            patch = vulkanPatchOf(GPUInformation.getVulkanVersion("System", this));
        }
        return patch != null ? patch : "0";
    }

    private static String vulkanPatchOf(String version) {
        if (version == null) return null;
        String[] parts = version.split("\\.");
        return parts.length >= 3 && parts[2].matches("\\d+") ? parts[2] : null;
    }

    private void extractOpenGLDriver(File rootDir) {
        String selectedDriver = getSelectedOpenGLDriver();
        String installedDriver = container.getExtra("installedOpenGLDriver", "");

        if (!firstTimeBoot && selectedDriver.equals(installedDriver))
            return;

        Log.d("XServerDisplayActivity", "Installing OpenGL driver " + selectedDriver
                + " (installed: '" + installedDriver + "')");

        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                "graphics_driver/opengl_" + selectedDriver + ".tzst", rootDir);
        if (!extracted) {
            Log.e("XServerDisplayActivity", "Failed to extract OpenGL driver " + selectedDriver);
            return;
        }

        container.putExtra("installedOpenGLDriver", selectedDriver);
        container.saveData();
    }

    private boolean isMesaGlVersionOverrideManual() {
        if (shortcut != null) {
            String shortcutEnvironment = shortcut.getExtra("envVars", "");
            EnvVars shortcutEnvVars = new EnvVars(shortcutEnvironment);
            if (shortcutEnvVars.has("MESA_GL_VERSION_OVERRIDE")) {
                return !"1".equals(shortcut.getExtra("autoMesaGlVersionOverride", "0"));
            }
            // Own list without the variable: the container's value isn't applied at all.
            if (shortcut.hasOwnEnvVars()) return false;
        }

        if (container == null) return false;
        EnvVars containerEnvVars = new EnvVars(container.getEnvVars());
        return containerEnvVars.has("MESA_GL_VERSION_OVERRIDE")
                && !"1".equals(container.getExtra("autoMesaGlVersionOverride", "0"));
    }

    private void applyOpenGLDriverEnvVars() {
        boolean freedreno = "freedreno".equals(getSelectedOpenGLDriver());

        envVars.put("GALLIUM_DRIVER", freedreno ? "freedreno" : "zink");
        envVars.put("MESA_LOADER_DRIVER_OVERRIDE", freedreno ? "kgsl" : "zink");

        if (freedreno) {
            if (!envVars.has("MESA_GL_VERSION_OVERRIDE")) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
            envVars.remove("ZINK_DESCRIPTORS");
            envVars.remove("ZINK_DEBUG");
            envVars.remove("ZINK_INLINE_UNIFORMS");
            if (!envVars.has("FD_MESA_DEBUG")) envVars.put("FD_MESA_DEBUG", "hiprio");
            if (!envVars.has("vblank_mode")) envVars.put("vblank_mode", "0");
        } else {
            if (!isMesaGlVersionOverrideManual()
                    && "3.3".equals(envVars.get("MESA_GL_VERSION_OVERRIDE"))) {
                envVars.remove("MESA_GL_VERSION_OVERRIDE");
            }
            if (!envVars.has("ZINK_DESCRIPTORS")) envVars.put("ZINK_DESCRIPTORS", "lazy");
            if (!envVars.has("ZINK_DEBUG")) envVars.put("ZINK_DEBUG", "compact");
        }
    }

    // MESA_VK_WSI_DEBUG is a comma separated flag list: "Disable DRI3" (sw) and
    // "Sync Frame" (forcesync) must not overwrite each other.
    private void appendWsiDebugFlag(String flag) {
        String current = envVars.get("MESA_VK_WSI_DEBUG");
        if (current.isEmpty()) {
            envVars.put("MESA_VK_WSI_DEBUG", flag);
            return;
        }
        for (String existing : current.split(",")) {
            if (existing.trim().equals(flag)) return;
        }
        envVars.put("MESA_VK_WSI_DEBUG", current + "," + flag);
    }

    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = graphicsDriverConfig.get("version");

        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId);

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            DXVKConfig.setEnvVars(this, dxwrapperConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                Log.d("GraphicsDriverExtraction", "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        } else {
            WineD3DConfig.setEnvVars(this, dxwrapperConfig, envVars);
        }

        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            appendWsiDebugFlag("sw");
        }

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");

        File graphicsRuntimeMarker = new File(rootDir,
                "usr/lib/.winlator-graphics-runtime-c7474f7e-25b50a11-v2");
        // The wrapper libs live in the ImageFS, which is shared by every container, so the
        // installed variant is tracked in the ImageFS itself (not in per-container extras).
        File wrapperVariantMarker = new File(rootDir, "usr/lib/.winlator-graphics-wrapper-variant");
        String wrapperArchive = resolveGraphicsWrapperArchiveName(graphicsWrapper);
        boolean installCommonRuntime = firstTimeBoot || !graphicsRuntimeMarker.isFile();
        // ImageFS that predates variant selection always carries the current wrapper.
        String installedWrapper = wrapperVariantMarker.isFile()
                ? FileUtils.readString(wrapperVariantMarker).trim() : "wrapper";
        boolean wrapperChanged = !wrapperArchive.equals(installedWrapper);
        if (installCommonRuntime || wrapperChanged) {
            Log.d("XServerDisplayActivity", "Installing graphics wrapper " + wrapperArchive);
            boolean installed = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                    "graphics_driver/" + wrapperArchive + ".tzst", rootDir);
            if (installed) FileUtils.writeString(wrapperVariantMarker, wrapperArchive);
            if (installCommonRuntime) {
                installed &= TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst",
                        rootDir);
                installed &= TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                        "graphics_driver/extra_libs" + ".tzst", rootDir);
                if (installed) FileUtils.writeString(graphicsRuntimeMarker,
                        "wrapper=c7474f7e;extra_libs=25b50a11;layers=9d57736d;opengl=split-v1");
            }
        }

        extractOpenGLDriver(rootDir);

        if (!"System".equals(adrenoToolsDriverId)) {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
        }

        String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
        vulkanVersion = vulkanVersion + "." + resolveVulkanPatchVersion(adrenoToolsDriverId);
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String gpuName = graphicsDriverConfig.get("gpuName");
        String dxvkVersion = dxwrapperConfig.get("version");
        if (!gpuName.equals("Device") && !dxvkVersion.equals("1.11.1-sarek")) {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            envVars.put("WRAPPER_DEVICE_ID", WineD3DConfig.getDeviceIdFromGPUName(this, gpuName));
            envVars.put("WRAPPER_VENDOR_ID", WineD3DConfig.getVendorIdFromGPUName(this, gpuName));
        }

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);

        String presentMode = graphicsDriverConfig.get("presentMode");
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String resourceType = graphicsDriverConfig.get("resourceType");
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

        String syncFrame = graphicsDriverConfig.get("syncFrame");
        if (syncFrame.equals("1"))
            appendWsiDebugFlag("forcesync");

        String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");

        switch (bcnEmulation) {
            case "auto" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            case "full" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }

        String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);

        if (xServer.getSurfaceFormat() == Drawable.HAL_PIXEL_FORMAT_RGBA_8888) {
            envVars.put("WRAPPER_SURFACE_FORMAT", "rgba8");
        }

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // While paused, swallow touches so they don't reach the game - but not when the drawer is open
        // or a themed overlay (e.g. the Exit confirmation) is on screen, otherwise its buttons are dead.
        if (isPaused && !ThemedAlertHost.isShowing()
                && (drawerLayout == null || !drawerLayout.isDrawerOpen(GravityCompat.START))) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;

        if (winHandler != null) {
            handledByWinHandler = winHandler.onGenericMotionEvent(event);
            if (handledByWinHandler) {

            }
        }

        if (touchpadView != null) {
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
            if (handledByTouchpadView) {

            }
        }

        boolean handledBySuper = super.dispatchGenericMotionEvent(event);
        if (!handledBySuper) {

        }

        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }

    private static final int RECAPTURE_DELAY_MS = 10000;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A text field in the sidebar (Custom FPS) is being edited: numeric IMEs deliver digits and
        // backspace as KeyEvents, which the X server keyboard would otherwise swallow below.
        if (isSidebarTextInputActive()) return super.dispatchKeyEvent(event);


        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                boolean handled = inputControlsView.onKeyEvent(event)
                        || (winHandler != null && winHandler.onKeyEvent(event))
                                && (xServer != null && xServer.keyboard.onKeyEvent(event));
                return true;
            }
        }

        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event)
                && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = { "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll",
                "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll" };

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];

            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                contentsManager.applyContent(dxvkProfile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxvkWrapper);
                SharedComponents.extractAndLink(this, "dxwrapper/" + dxvkWrapper + ".tzst", windowsDir, onExtractFileListener);

                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
                    SharedComponents.extractAndLink(this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            if (vkd3dWrapper.contains("None")) {
                Log.d(TAG, "No VKD3D has been selected, restoring original d3d12");
                restoreOriginalDllFiles(new String[] { "d3d12.dll", "d3d12core.dll" });
            } else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
                    contentsManager.applyContent(vkd3dProfile);
                } else {
                    Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + vkd3dWrapper);
                    SharedComponents.extractAndLink(this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            Log.d(TAG, "Extracting nglide wrapper");
            SharedComponents.extractAndLink(this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

            if (ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files");
                restoreOriginalDllFiles(new String[] { "ddraw.dll", "d3dimm.dll" });
            } else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

                Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
                SharedComponents.extractAndLink(this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
            }

            Log.d(TAG, "Finished extraction of DXVK wrapper files, version: " + dxwrapper);
        } else if (dxwrapper.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d.");
            restoreOriginalDllFiles(dlls);
        }
    }

    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0])
            return a[0] - b[0];
        if (a[1] != b[1])
            return a[1] - b[1];
        return a[2] - b[2];
    }

    private static final Pattern SEMVER_LOOSE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int[] parseSemverLoose(String s) {
        if (s == null)
            return new int[] { 0, 0, 0 };

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[] { 0, 0, 0 };
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[] { major, minor, patch };
    }

    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty())
            return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(
                    FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents())
                    : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(
                    container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot)
                    continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    SharedComponents.extractAndLink(this, "wincomponents/" + identifier + ".tzst", windowsDir, onExtractFileListener);
                } else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname + ".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity",
                        "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty())
                restoreOriginalDllFiles(dlls.toArray(new String[0]));
        } catch (JSONException e) {
        }
    }

    /** The originals live in the base prefix: dropping the container's override makes them show through again. */
    private void restoreOriginalDllFiles(final String... dlls) {
        File upperWine = ContainerFiles.upperDir(container);
        for (String dll : dlls) {
            ContainerFiles.removeOverride(upperWine, "drive_c/windows/system32/" + dll);
            ContainerFiles.removeOverride(upperWine, "drive_c/windows/syswow64/" + dll);
        }
    }

    private String getWineStartCommand() {

        EnvVars envVars = getOverrideEnvVars();

        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String fullPath = shortcut.path.replace("\"", "");
                String exeDir;
                String filename;

                if (fullPath.contains("\\")) {
                    int lastSlash = fullPath.lastIndexOf("\\");
                    if (lastSlash != -1) {
                        exeDir = fullPath.substring(0, lastSlash);
                        filename = fullPath.substring(lastSlash + 1);
                    } else {
                        exeDir = "D:\\";
                        filename = fullPath;
                    }
                } else {
                    exeDir = FileUtils.getDirname(fullPath);
                    filename = FileUtils.getName(fullPath);
                }

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {

            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS");
            } else {
                args += "\"wfm.exe\"";
            }
        }

        String command = "winhandler.exe " + args;

        return command;
    }

    private String getExecutable() {
        String filename = "wfm.exe";
        if (shortcut != null && shortcut.path != null) {
            String cleanPath = shortcut.path.replace("\"", "");
            int lastSlash = cleanPath.lastIndexOf('/');
            int lastBackslash = cleanPath.lastIndexOf('\\');
            int lastSeparator = Math.max(lastSlash, lastBackslash);
            if (lastSeparator != -1) {
                filename = cleanPath.substring(lastSeparator + 1);
            } else {
                filename = cleanPath;
            }
        }
        return filename;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerRendererView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                } else if (audioDriver.equals("pulse-audio-gn")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }


    private void applyGameRefreshRateUnlock() {
        final String x11DriverKey = "Software\\Wine\\X11 Driver";
        final boolean xrandrCapable = isSelectedWineXrandrCapable();
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (xrandrCapable) {
                registryEditor.setStringValue(x11DriverKey, "EmulateModelist", "Y");
                registryEditor.setStringValue(x11DriverKey, "EmulateModeset", "Y");
            } else {
                registryEditor.removeValue(x11DriverKey, "EmulateModelist");
                registryEditor.removeValue(x11DriverKey, "EmulateModeset");
            }
        }

        Log.d("XServerDisplayActivity", "RandR Wine mode emulation: "
                + (xrandrCapable ? "disabled" : "unchanged (layer has no XRandR)"));
    }

    private boolean isSelectedWineXrandrCapable() {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;

        String cacheKey = wineInfo.identifier() + "@" + wineInfo.path;
        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            Boolean cached = WINE_XRANDR_SUPPORT_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }

        File winex11 = new File(wineInfo.path, "lib/wine/aarch64-unix/winex11.so");
        if (!winex11.isFile()) {
            winex11 = new File(wineInfo.path, "lib/wine/x86_64-unix/winex11.so");
        }

        boolean capable = winex11.isFile()
                && fileContainsAny(winex11, "libXrandr.so", "RRQueryVersion")
                && !fileContainsAny(winex11, "XRandR support not compiled in.");

        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            WINE_XRANDR_SUPPORT_CACHE.put(cacheKey, capable);
        }
        return capable;
    }

    private static boolean fileContainsAny(File file, String... markers) {
        byte[][] needles = new byte[markers.length][];
        int maxNeedle = 0;
        for (int i = 0; i < markers.length; i++) {
            needles[i] = markers[i].getBytes(StandardCharsets.US_ASCII);
            maxNeedle = Math.max(maxNeedle, needles[i].length);
        }

        final int chunkSize = 64 * 1024;
        byte[] buffer = new byte[chunkSize + maxNeedle];
        try (FileInputStream input = new FileInputStream(file)) {
            int carry = 0;
            int read;
            while ((read = input.read(buffer, carry, chunkSize)) != -1) {
                int length = carry + read;
                for (byte[] needle : needles) {
                    if (indexOfBytes(buffer, length, needle) >= 0) return true;
                }

                carry = Math.min(length, maxNeedle - 1);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static int indexOfBytes(byte[] haystack, int haystackLength, byte[] needle) {
        if (needle.length == 0 || needle.length > haystackLength) return -1;

        outer:
        for (int i = 0; i <= haystackLength - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void applyGeneralPatches(Container container) {
        // container_pattern_common lives in the shared base prefix, nothing to extract per container.
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    public void setRefreshRate(float refreshRate) {
        this.refreshRate = refreshRate;
    }

    public float getRefreshRate() {
        return refreshRate;
    }

    public boolean isShowFPS() {
        if (container == null) return false;
        return resolveHudMode() != 0;
    }

    public void updateFrameRating(Window window) {
        if (window == null || frameRatingWindowId != window.id) return;
        if (classicHud != null) classicHud.update();
        if (modernHud != null) modernHud.onFrame();
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 || taskAffinityMaskWoW64 == 0)
            return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    private void changeFrameRatingVisibility(Window window, Property property) {

        String propName = (property != null) ? property.nameAsString() : null;

        if (property != null) {
            if (activeRendererWindowId == -1 && propName.contains("_MESA_DRV")) {
                activeRendererWindowId = window.id;
            }

            if (propName.contains("_MESA_DRV_ENGINE_NAME")
                    && (activeRendererWindowId == -1 || window.id == activeRendererWindowId)) {
                lastRendererName = property.toString();
            }
        } else if (activeRendererWindowId != -1 && window.id == activeRendererWindowId) {

            activeRendererWindowId = -1;
            lastRendererName = null;
        }

        if (classicHud == null && modernHud == null) return;

        if (property != null) {
            if (frameRatingWindowId == -1 && propName.contains("_MESA_DRV")) {
                frameRatingWindowId = window.id;
                if (xServerView != null) xServerView.setFpsWindowId(window.id);
                Log.d("XServerDisplayActivity", "Showing hud for Window " + window.getName());
                if (classicHud != null) classicHud.update();

                if (modernHud != null) {
                    final String nameToPass = lastRendererName;
                    runOnUiThread(() -> modernHud.onRendererDetected(nameToPass));
                }
            }

            if (propName.contains("_MESA_DRV_ENGINE_NAME") && window.id == frameRatingWindowId) {
                String rendererName = property.toString();
                if (classicHud != null) runOnUiThread(() -> classicHud.setRenderer(rendererName));
                if (modernHud != null) runOnUiThread(() -> modernHud.setRenderer(rendererName));
            }
            if (propName.contains("_MESA_DRV_GPU_NAME") && window.id == frameRatingWindowId) {
                String gpuName = property.toString();
                if (classicHud != null) runOnUiThread(() -> classicHud.setGpuName(gpuName));
                if (modernHud != null) runOnUiThread(() -> modernHud.setGpuName(gpuName));
            }
        } else if (frameRatingWindowId != -1 && window.id == frameRatingWindowId) {

            frameRatingWindowId = -1;
            if (xServerView != null) xServerView.setFpsWindowId(-1);
            Log.d("XServerDisplayActivity", "Hiding hud for Window " + window.getName());
            if (classicHud != null) runOnUiThread(() -> {
                classicHud.setVisibility(View.GONE);
                classicHud.reset();
            });
            if (modernHud != null) runOnUiThread(() -> modernHud.onRendererGone());
        }
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

}

