#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <cstddef>
#include <algorithm>
#include <vector>
#include <string>
#include <mutex>
#include <shared_mutex>
#include "../../../adrenotools/include/adrenotools/driver.h"
#include "VulkanRendererContext.h"

static void* openAdrenotoolsDriver(const char* driverPath, const char* libraryName,
                                   const char* nativeLibDir) {
    if (!driverPath || !libraryName || !nativeLibDir) return nullptr;
    if (access(driverPath, F_OK) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: driverPath not accessible: %s", driverPath);
        return nullptr;
    }
    char tmpdir[512];
    snprintf(tmpdir, sizeof(tmpdir), "%stemp", driverPath);
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
        "openAdrenotoolsDriver: driverPath=%s lib=%s nativeLibDir=%s tmp=%s",
        driverPath, libraryName, nativeLibDir, tmpdir);
    setenv("ADRENOTOOLS_DRIVER_PATH", driverPath, 1);
    setenv("ADRENOTOOLS_DRIVER_NAME", libraryName, 1);
    setenv("ADRENOTOOLS_HOOKS_PATH", nativeLibDir, 1);
    const char* redirectDir = getenv("ADRENOTOOLS_REDIRECT_DIR");
    int featureFlags = ADRENOTOOLS_DRIVER_CUSTOM;
    if (redirectDir && redirectDir[0] != '\0') {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    } else {
        unsetenv("ADRENOTOOLS_DRIVER_FILE_REDIRECT");
    }
    void* handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW,
        featureFlags,
        tmpdir,
        nativeLibDir,
        driverPath,
        libraryName,
        (redirectDir && redirectDir[0] != '\0') ? redirectDir : nullptr,
        nullptr);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: adrenotools_open_libvulkan failed");
    } else {
        __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
            "openAdrenotoolsDriver: SUCCESS handle=%p", handle);
    }
    return handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeInit(
    JNIEnv* env, jobject ,
    jobject surface, jint w, jint h,
    jstring jDriverPath, jstring jLibraryName, jstring jNativeLibDir,
    jboolean validationEnabled, jstring jCacheDir)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;

    // The layer is only looked for when it was asked for; this just reports in logcat whether the
    // .so is actually there (the renderer then enables it if the loader exposes it).
    if (validationEnabled == JNI_TRUE) {
        const char* nativeLibDir = jNativeLibDir
            ? env->GetStringUTFChars(jNativeLibDir, nullptr) : nullptr;
        if (nativeLibDir) {
            std::string layerPath = std::string(nativeLibDir) + "/libVkLayer_khronos_validation.so";
            const bool layerPresent = access(layerPath.c_str(), R_OK) == 0;
            __android_log_print(layerPresent ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                "Winlator_VulkanValidation", "requested layer=%s present=%d",
                layerPath.c_str(), layerPresent ? 1 : 0);
            env->ReleaseStringUTFChars(jNativeLibDir, nativeLibDir);
        }
    }

    void* adrenotoolsHandle = nullptr;
    if (jDriverPath && jLibraryName && jNativeLibDir) {
        const char* dp  = env->GetStringUTFChars(jDriverPath,   nullptr);
        const char* lib = env->GetStringUTFChars(jLibraryName,  nullptr);
        const char* nld = env->GetStringUTFChars(jNativeLibDir, nullptr);
        adrenotoolsHandle = openAdrenotoolsDriver(dp, lib, nld);
        env->ReleaseStringUTFChars(jDriverPath,   dp);
        env->ReleaseStringUTFChars(jLibraryName,  lib);
        env->ReleaseStringUTFChars(jNativeLibDir, nld);
    }

    // Where the pipeline cache lives (the app's cache directory); null keeps it in memory only.
    std::string cacheDir;
    if (jCacheDir) {
        const char* cd = env->GetStringUTFChars(jCacheDir, nullptr);
        if (cd) { cacheDir = cd; env->ReleaseStringUTFChars(jCacheDir, cd); }
    }

    try {
        return reinterpret_cast<jlong>(
            new VulkanRendererContext(win, w, h, adrenotoolsHandle,
                                      validationEnabled == JNI_TRUE,
                                      cacheDir.empty() ? nullptr : cacheDir.c_str()));
    } catch (...) {
        ANativeWindow_release(win);
        if (adrenotoolsHandle) dlclose(adrenotoolsHandle);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeResize(
    JNIEnv*, jobject, jlong h, jint w, jint ht)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(h);
    if (r) r->onSurfaceResized(w, ht);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDestroy(
    JNIEnv*, jobject, jlong h)
{
    delete reinterpret_cast<VulkanRendererContext*>(h);
}

// ---------------------------------------------------------------- frames from the X server
//
// The X server (xcore, in libwinlator.so) hands frames to the renderer itself, from its own
// threads, instead of going through Java for each one: VulkanXServerView passes this table and
// its native handle to XCore.setRenderer(). The layout is xcore's `RendererApi`
// (rust/xcore/src/android/output.rs); bump the version on both sides together.
//
// These are called from Rust: nothing may unwind out of them.

struct XcoreRendererApi {
    uint32_t version;
    void    (*updateContent)(void* ctx, int64_t id, AHardwareBuffer* ahb);
    void    (*releaseBuffer)(void* ctx, AHardwareBuffer* ahb);
    int32_t (*displayClock)(void* ctx, int64_t* out);
    // Version 2: the window tree and the cursor come from the X server too (they went through
    // Java's event thread, which read the state back from the server after the fact).
    void    (*initRoot)(void* ctx, int64_t root, int64_t content, int32_t width, int32_t height);
    void    (*createWindow)(void* ctx, int64_t id, int64_t parent, int64_t content,
                            int32_t x, int32_t y, int32_t width, int32_t height);
    void    (*destroyWindow)(void* ctx, int64_t id);
    void    (*mapWindow)(void* ctx, int64_t id);
    void    (*unmapWindow)(void* ctx, int64_t id);
    void    (*reparentWindow)(void* ctx, int64_t id, int64_t parent);
    void    (*updateGeometry)(void* ctx, int64_t id, int64_t content,
                              int32_t x, int32_t y, int32_t width, int32_t height);
    void    (*syncChildren)(void* ctx, int64_t parent, const int64_t* ids, int32_t count);
    void    (*setViewable)(void* ctx, int64_t id, int32_t viewable);
    void    (*setPointer)(void* ctx, int32_t x, int32_t y);
    void    (*setCursorVisible)(void* ctx, int32_t visible);
    void    (*updateCursor)(void* ctx, const uint8_t* rgba, int32_t width, int32_t height,
                            int32_t stride, int32_t hotX, int32_t hotY);
};

// Mirrors the Rust side (xcore src/android/output.rs), which checks the same numbers.
static_assert(sizeof(XcoreRendererApi) == 128, "XcoreRendererApi layout");
static_assert(offsetof(XcoreRendererApi, initRoot) == 32, "XcoreRendererApi layout");
static_assert(offsetof(XcoreRendererApi, updateCursor) == 120, "XcoreRendererApi layout");

static void xcoreUpdateContent(void* ctx, int64_t id, AHardwareBuffer* ahb) noexcept {
    try {
        if (ctx && ahb) static_cast<VulkanRendererContext*>(ctx)->updateWindowContentAHB(id, ahb, 0, 0, 0, 0);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "updateContent: exception, frame dropped");
    }
}

// The server dropped the buffer: forget the renderer's import of it (once nothing shows it).
static void xcoreReleaseBuffer(void* ctx, AHardwareBuffer* ahb) noexcept {
    try {
        if (ctx && ahb) static_cast<VulkanRendererContext*>(ctx)->releaseAhb(ahb);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "releaseBuffer: exception");
    }
}

static int32_t xcoreDisplayClock(void* ctx, int64_t* out) noexcept {
    if (!ctx || !out) return 0;
    return static_cast<const VulkanRendererContext*>(ctx)->getDisplayClock(out) ? 1 : 0;
}

// The tree and cursor calls only lock renderMutex (the content's import may wait for the GPU):
// called on the server's frame thread, never on a Java thread.
#define XCORE_CTX(ctx) static_cast<VulkanRendererContext*>(ctx)
#define XCORE_GUARD(name, body)                                                              \
    do {                                                                                     \
        if (!ctx) return;                                                                    \
        try { body; } catch (...) {                                                          \
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", name ": exception"); \
        }                                                                                    \
    } while (0)

static void xcoreInitRoot(void* ctx, int64_t root, int64_t content, int32_t w, int32_t h) noexcept {
    XCORE_GUARD("initRoot", XCORE_CTX(ctx)->initRootWindow(root, content, w, h));
}
static void xcoreCreateWindow(void* ctx, int64_t id, int64_t parent, int64_t content,
                              int32_t x, int32_t y, int32_t w, int32_t h) noexcept {
    XCORE_GUARD("createWindow", XCORE_CTX(ctx)->createWindowNode(id, parent, content, x, y, w, h));
}
static void xcoreDestroyWindow(void* ctx, int64_t id) noexcept {
    XCORE_GUARD("destroyWindow", XCORE_CTX(ctx)->destroyWindowNode(id));
}
static void xcoreMapWindow(void* ctx, int64_t id) noexcept {
    XCORE_GUARD("mapWindow", XCORE_CTX(ctx)->mapWindowNode(id));
}
static void xcoreUnmapWindow(void* ctx, int64_t id) noexcept {
    XCORE_GUARD("unmapWindow", XCORE_CTX(ctx)->unmapWindowNode(id));
}
static void xcoreReparentWindow(void* ctx, int64_t id, int64_t parent) noexcept {
    XCORE_GUARD("reparentWindow", XCORE_CTX(ctx)->reparentWindowNode(id, parent));
}
static void xcoreUpdateGeometry(void* ctx, int64_t id, int64_t content,
                                int32_t x, int32_t y, int32_t w, int32_t h) noexcept {
    XCORE_GUARD("updateGeometry", XCORE_CTX(ctx)->updateWindowGeometryNode(id, content, x, y, w, h));
}
static void xcoreSyncChildren(void* ctx, int64_t parent, const int64_t* ids, int32_t count) noexcept {
    if (!ids || count < 0) return;
    XCORE_GUARD("syncChildren", XCORE_CTX(ctx)->syncChildOrder(parent, ids, count));
}
static void xcoreSetViewable(void* ctx, int64_t id, int32_t viewable) noexcept {
    XCORE_GUARD("setViewable", XCORE_CTX(ctx)->setWindowViewable(id, viewable != 0));
}
static void xcoreSetPointer(void* ctx, int32_t x, int32_t y) noexcept {
    XCORE_GUARD("setPointer", XCORE_CTX(ctx)->updatePointerPosition((short)x, (short)y));
}
static void xcoreSetCursorVisible(void* ctx, int32_t visible) noexcept {
    XCORE_GUARD("setCursorVisible", XCORE_CTX(ctx)->setCursorVisible(visible != 0));
}
static void xcoreUpdateCursor(void* ctx, const uint8_t* rgba, int32_t w, int32_t h,
                              int32_t stride, int32_t hotX, int32_t hotY) noexcept {
    if (!rgba || w <= 0 || h <= 0 || w > 0x7fff || h > 0x7fff) return;
    XCORE_GUARD("updateCursor", XCORE_CTX(ctx)->updateCursorImage(
        const_cast<uint8_t*>(rgba), (short)w, (short)h, (short)std::max(stride, w),
        (short)hotX, (short)hotY, true));
}

#undef XCORE_GUARD
#undef XCORE_CTX

static const XcoreRendererApi kXcoreRendererApi = {
    2, xcoreUpdateContent, xcoreReleaseBuffer, xcoreDisplayClock,
    xcoreInitRoot, xcoreCreateWindow, xcoreDestroyWindow, xcoreMapWindow, xcoreUnmapWindow,
    xcoreReparentWindow, xcoreUpdateGeometry, xcoreSyncChildren, xcoreSetViewable,
    xcoreSetPointer, xcoreSetCursorVisible, xcoreUpdateCursor,
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeRendererApi(JNIEnv*, jclass)
{
    return reinterpret_cast<jlong>(&kXcoreRendererApi);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetTransformAndScissor(
    JNIEnv*, jobject, jlong handle,
    jfloat ox, jfloat oy, jfloat sx, jfloat sy,
    jboolean hasScissor, jint scX, jint scY, jint scW, jint scH)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setTransformAndScissor(ox, oy, sx, sy, (bool)hasScissor, scX, scY, scW, scH);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetVerboseLog(
    JNIEnv*, jobject, jlong handle, jboolean v)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setVerboseLog((bool)v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDumpRendererInfo(
    JNIEnv*, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->dumpRendererInfo();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetFilterMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setFilterMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetStretchMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setStretchMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPostFXMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPostFXMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetSharpness(
    JNIEnv*, jobject, jlong handle, jfloat s)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setSharpness((float)s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPresentMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPresentMode((VkPresentModeKHR)mode);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetSupportedPresentModes(
    JNIEnv* env, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return env->NewIntArray(0);
    std::vector<int> modes;
    {
        // Rewritten by createSwapchain() on the render thread.
        std::shared_lock<std::shared_mutex> frameLock(r->frameMutex);
        modes = r->getSupportedPresentModes();
    }
    jintArray arr = env->NewIntArray((jsize)modes.size());
    if (!modes.empty())
        env->SetIntArrayRegion(arr, 0, (jsize)modes.size(), modes.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDetachSurface(
    JNIEnv*, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->detachSurface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeReattachSurface(
    JNIEnv* env, jobject, jlong handle, jobject surface)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = r->reattachSurface(win);
    return (jboolean)ok;
}


extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetWfg(
    JNIEnv*, jobject, jlong handle, jboolean enabled, jint flowSide, jboolean fastInterp,
    jint targetFps, jint multiplier, jfloat refreshHz)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    r->setWfgConfig((int)flowSide, fastInterp == JNI_TRUE, (int)targetFps, (int)multiplier,
                    (float)refreshHz);
    r->setWfgEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetWfgFpsLimit(
    JNIEnv*, jobject, jlong handle, jint fps)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    r->setWfgFpsLimit((int)fps);
}

// The display clock for the FPS limiters: {refresh period ns, arrival phase error ns, its
// sequence number}; false while the renderer has no fresh refresh grid.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetDisplayClock(
    JNIEnv* env, jobject, jlong handle, jlongArray out)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !out || env->GetArrayLength(out) < 3) return JNI_FALSE;
    int64_t v[3];
    if (!r->getDisplayClock(v)) return JNI_FALSE;
    const jlong j[3] = {(jlong)v[0], (jlong)v[1], (jlong)v[2]};
    env->SetLongArrayRegion(out, 0, 3, j);
    return JNI_TRUE;
}

// {presented frames (real and generated), WFG-generated frames, presented frames as of the
// latest real frame's burst, that burst's time (ns, System.nanoTime() clock)}; a 2-long array
// gets the first two only.
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetFrameCounts(
    JNIEnv* env, jobject, jlong handle, jlongArray out)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !out) return;
    const jsize n = env->GetArrayLength(out);
    if (n < 2) return;
    const auto stamp = r->getPresentStamp();
    jlong vals[4] = { (jlong)r->getPresentedFrames(), (jlong)r->getWfgGeneratedFrames(),
                      (jlong)stamp.frames, (jlong)stamp.ns };
    env->SetLongArrayRegion(out, 0, n >= 4 ? 4 : 2, vals);
}
