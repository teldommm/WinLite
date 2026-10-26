#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>
#include <android/log.h>
#include "../../../adrenotools/include/adrenotools/driver.h"
#include "VulkanRendererContext.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeConfigureFrameGen(
    JNIEnv* env, jobject, jlong handle, jstring cachePath, jint multiplier,
    jfloat flowScale, jfloat refreshHz, jint targetRate) {
    auto* ctx = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!ctx) return nullptr;
    if (multiplier < 2) {
        ctx->setFrameGenArmed(false, 0);
        return nullptr;
    }
    if (!cachePath) {
        ctx->setFrameGenArmed(false, 0);
        return env->NewStringUTF("Shader cache is unavailable");
    }
    const char* path = env->GetStringUTFChars(cachePath, nullptr);
    if (!path) return nullptr;
    const std::string pathStr(path);
    env->ReleaseStringUTFChars(cachePath, path);

    // The gates depend on which shader set the cache holds: the precompiled
    // SPIR-V some Lossless.dll builds carry runs without Vulkan 1.2 / vulkanMemoryModel,
    // the DXBC-translated set does not.
    lsfg::Variant variant = lsfg::Variant::None;
    if (lsfg::cacheVariant(pathStr, variant) != lsfg::DllStatus::Ok) variant = lsfg::Variant::None;
    std::string unsupported;
    {
        auto frameLock = ctx->lockFrameExclusive();
        if (!ctx->lsfgCaps_.supported(variant)) {
            lsfg::Caps probe = ctx->lsfgCaps_;
            lsfg::explain(probe, variant);
            unsupported = probe.reason;
        }
    }
    if (!unsupported.empty()) {
        ctx->setFrameGenArmed(false, 0);
        return env->NewStringUTF(unsupported.c_str());
    }
    ctx->setLsfgCachePath(pathStr.c_str());
    ctx->setFrameGenTuning(flowScale, refreshHz);
    ctx->setFrameGenArmed(true, std::clamp((int)multiplier, 2, 4), std::max((int)targetRate, 0));
    return nullptr;
}

// Whether the fp16 shader set can run here: shaderFloat16 was offered and
// enabled at device creation, along with the common storage-image gates.
// Java uses it to decide preferFp16 for the cache build.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeFrameGenSupportsFp16(
    JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::shared_lock<std::shared_mutex> frameLock(ctx->frameMutex);
    return ctx->lsfgCaps_.featuresEnabled && ctx->lsfgCaps_.float16Enabled ? JNI_TRUE : JNI_FALSE;
}

// Measured presents/sec (real + generated), already smoothed by the renderer
// itself (see VulkanRendererContext::trackPresentedRate). Called for the HUD on
// every guest present, on the X server's thread: an atomic read, no frameMutex
// (a shared lock does not exclude the render thread, which also holds it shared
// while it writes the pacer state the old stats call read, and it made the X
// thread wait behind any pending configuration change). The renderer's
// lifetime is guarded by the Java side's lock.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetFrameGenPresentedRate(
    JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!ctx) return 0.0f;
    return ctx->fgPresentedRate_.load(std::memory_order_relaxed);
}

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
    jboolean validationEnabled)
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

    try {
        return reinterpret_cast<jlong>(
            new VulkanRendererContext(win, w, h, adrenotoolsHandle,
                                      validationEnabled == JNI_TRUE));
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

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateWindowContentAHB(
    JNIEnv*, jobject, jlong handle, jlong contentId, jlong sourceId, jlong ahbPtr,
    jshort w, jshort h, jint x, jint y)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r && ahbPtr)
        r->updateWindowContentAHB(contentId, sourceId,
                                  reinterpret_cast<AHardwareBuffer*>(ahbPtr), w, h, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativePresentFrame(
    JNIEnv* env, jobject, jlong handle, jlong contentId, jlong sourceId, jlong ahbPtr,
    jlong guestFrameNs, jlongArray jout)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    int64_t out[4] = {0, 0, 0, 0};
    if (r && ahbPtr)
        r->presentFrame(contentId, sourceId, reinterpret_cast<AHardwareBuffer*>(ahbPtr),
                        guestFrameNs, out);
    if (jout) {
        const jsize n = std::min<jsize>(env->GetArrayLength(jout), 4);
        jlong vals[4] = {(jlong)out[0], (jlong)out[1], (jlong)out[2], (jlong)out[3]};
        if (n > 0) env->SetLongArrayRegion(jout, 0, n, vals);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSourceBusy(
    JNIEnv*, jobject, jlong handle, jlong sourceId)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return (r && r->sourceBusy(sourceId)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeBeginSourceIdleWait(
    JNIEnv*, jobject, jlong handle, jlong sourceId)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return r ? (jlong)r->beginSourceIdleWait(sourceId) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeEndSourceIdleWait(
    JNIEnv*, jobject, jlong handle, jlong serial, jlong timeoutNs)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r && serial != 0) r->endSourceIdleWait((uint64_t)serial, timeoutNs > 0 ? (uint64_t)timeoutNs : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetFpsLimit(
    JNIEnv*, jobject, jlong handle, jint fps)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setFpsLimit(fps);
}

// ---- ADPF (Android Performance Hint) ----

// What the device offers: 0 = nothing, otherwise the API step 33 / 34 / 35 / 36.
extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativePerformanceHintLevel(JNIEnv*, jobject)
{
    return adpf::caps().level();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPerformanceHint(
    JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPerformanceHint(enabled == JNI_TRUE);
}

// Native thread ids (gettid) of the other threads that carry a frame.
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPerformanceHintThreads(
    JNIEnv* env, jobject, jlong handle, jintArray tids)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    const jsize n = tids ? env->GetArrayLength(tids) : 0;
    std::vector<int32_t> list((size_t)n);
    if (n > 0) env->GetIntArrayRegion(tids, 0, n, reinterpret_cast<jint*>(list.data()));
    r->setPerformanceHintThreads(list.data(), list.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeOnVsync(
    JNIEnv*, jobject, jlong handle, jlong frameTimeNs, jlong deadlineNs, jlong presentNs)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->onVsync(frameTimeNs, deadlineNs, presentNs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetDisplayRefreshRate(
    JNIEnv*, jobject, jlong handle, jfloat hz)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setDisplayRefreshRate(hz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeReleaseDrawable(
    JNIEnv*, jobject, jlong handle, jlong drawableId)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->releaseDrawable(drawableId);
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
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPointerPos(
    JNIEnv*, jobject, jlong handle, jshort x, jshort y)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->updatePointerPosition(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetCursorVisible(
    JNIEnv*, jobject, jlong handle, jboolean v)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setCursorVisible(v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf,
    jshort w, jshort h, jshort stride, jshort hotX, jshort hotY, jboolean rgba)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    jlong required = (jlong)std::max((int)stride, (int)w) * h * 4;
    if (px && env->GetDirectBufferCapacity(buf) >= required)
        r->updateCursorImage(px, w, h, stride, hotX, hotY, rgba == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeInitRootWindow(
    JNIEnv*, jobject, jlong handle, jlong rootId, jlong contentId, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->initRootWindow(rootId, contentId, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeCreateWindow(
    JNIEnv*, jobject, jlong handle, jlong id, jlong parentId, jlong contentId,
    jint x, jint y, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->createWindowNode(id, parentId, contentId, x, y, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDestroyWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->destroyWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeMapWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->mapWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUnmapWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->unmapWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeReparentWindow(
    JNIEnv*, jobject, jlong handle, jlong id, jlong newParentId)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->reparentWindowNode(id, newParentId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateWindowGeometry(
    JNIEnv*, jobject, jlong handle, jlong id, jlong contentId,
    jint x, jint y, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->updateWindowGeometryNode(id, contentId, x, y, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSyncChildOrder(
    JNIEnv* env, jobject, jlong handle, jlong parentId, jlongArray jorderedIds, jint count)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || count <= 0 || !jorderedIds || env->GetArrayLength(jorderedIds) < count) return;
    // Copied out first: syncChildOrder() waits for renderMutex, which the render thread and the
    // configuration calls hold across fence waits and vkDeviceWaitIdle. A JNI critical section
    // open across that wait blocks the garbage collector's thread flip (ART: ThreadFlipBegin
    // waits for every critical section to close, and stops new ones from opening) - every
    // allocating thread, the UI thread included, waits with it.
    std::vector<int64_t> ids((size_t)count);
    env->GetLongArrayRegion(jorderedIds, 0, count, reinterpret_cast<jlong*>(ids.data()));
    if (env->ExceptionCheck()) return;
    r->syncChildOrder(parentId, ids.data(), count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetWindowViewable(
    JNIEnv*, jobject, jlong handle, jlong id, jboolean viewable)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setWindowViewable(id, viewable);
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

extern "C" JNIEXPORT jintArray JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetSwapchainSize(
    JNIEnv* env, jobject, jlong handle)
{
    jintArray arr = env->NewIntArray(2);
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return arr;
    VkExtent2D ext = r->getSwapchainExtent();
    jint vals[2] = { (jint)ext.width, (jint)ext.height };
    env->SetIntArrayRegion(arr, 0, 2, vals);
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

