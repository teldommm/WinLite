#include "adpf_session.h"

#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>

#define LOG_TAG "WinliteAdpf"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adpf {
namespace {

// APerformanceHintFeature, in the order the API 36 header declares it.
constexpr int32_t kFeatureSessions = 0;
constexpr int32_t kFeatureSurfaceBinding = 2;
constexpr int32_t kFeatureGraphicsPipeline = 3;
constexpr int32_t kFeatureAutoCpu = 4;
constexpr int32_t kFeatureAutoGpu = 5;

// A run of consecutive failed calls ends the session (the system service is gone, or it keeps
// rejecting what it is given); a hint that was rate limited (EBUSY) is not a failure.
constexpr int kMaxFailures = 16;
constexpr int64_t kMinTargetNs = 1000000;   // the work duration API wants a positive target

// Every handle is opaque here (void*): the types of the newest steps are not in older NDK
// headers, and nothing below needs them.
struct Api {
    void* (*getManager)() = nullptr;
    void* (*createSession)(void*, const int32_t*, size_t, int64_t) = nullptr;
    int   (*updateTarget)(void*, int64_t) = nullptr;
    int   (*report)(void*, int64_t) = nullptr;
    void  (*closeSession)(void*) = nullptr;
    int   (*setThreads)(void*, const int32_t*, size_t) = nullptr;                  // 34
    int   (*report2)(void*, void*) = nullptr;                                      // 35
    void* (*wdCreate)() = nullptr;
    void  (*wdRelease)(void*) = nullptr;
    void  (*wdStart)(void*, int64_t) = nullptr;
    void  (*wdTotal)(void*, int64_t) = nullptr;
    void  (*wdCpu)(void*, int64_t) = nullptr;
    void  (*wdGpu)(void*, int64_t) = nullptr;
    int   (*createFromConfig)(void*, void*, void**) = nullptr;                     // 36
    int   (*maxPipelineThreads)(void*) = nullptr;
    int   (*notifyIncrease)(void*, bool, bool, const char*) = nullptr;
    int   (*notifyReset)(void*, bool, bool, const char*) = nullptr;
    int   (*notifySpike)(void*, bool, bool, const char*) = nullptr;
    int   (*setSurfaces)(void*, ANativeWindow**, size_t, void**, size_t) = nullptr;
    bool  (*featureSupported)(int32_t) = nullptr;
    void* (*cfgCreate)() = nullptr;
    void  (*cfgRelease)(void*) = nullptr;
    void  (*cfgTids)(void*, const int32_t*, size_t) = nullptr;
    void  (*cfgTarget)(void*, int64_t) = nullptr;
    void  (*cfgPipeline)(void*, bool) = nullptr;
    void  (*cfgSurfaces)(void*, ANativeWindow**, size_t, void**, size_t) = nullptr;
    void  (*cfgAuto)(void*, bool, bool) = nullptr;
    void* manager = nullptr;
};

Api g_api;
Caps g_caps;
std::once_flag g_once;

template <typename F>
void load(void* lib, F& field, const char* name) {
    field = reinterpret_cast<F>(dlsym(lib, name));
}

void probe() {
    g_caps.deviceApi = android_get_device_api_level();
    if (g_caps.deviceApi < 33) {
        LOGI("API level %d < 33: no hint sessions", g_caps.deviceApi);
        return;
    }
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return;

    load(lib, g_api.getManager, "APerformanceHint_getManager");
    load(lib, g_api.createSession, "APerformanceHint_createSession");
    load(lib, g_api.updateTarget, "APerformanceHint_updateTargetWorkDuration");
    load(lib, g_api.report, "APerformanceHint_reportActualWorkDuration");
    load(lib, g_api.closeSession, "APerformanceHint_closeSession");
    load(lib, g_api.setThreads, "APerformanceHint_setThreads");
    load(lib, g_api.report2, "APerformanceHint_reportActualWorkDuration2");
    load(lib, g_api.wdCreate, "AWorkDuration_create");
    load(lib, g_api.wdRelease, "AWorkDuration_release");
    load(lib, g_api.wdStart, "AWorkDuration_setWorkPeriodStartTimestampNanos");
    load(lib, g_api.wdTotal, "AWorkDuration_setActualTotalDurationNanos");
    load(lib, g_api.wdCpu, "AWorkDuration_setActualCpuDurationNanos");
    load(lib, g_api.wdGpu, "AWorkDuration_setActualGpuDurationNanos");
    load(lib, g_api.createFromConfig, "APerformanceHint_createSessionUsingConfig");
    load(lib, g_api.maxPipelineThreads, "APerformanceHint_getMaxGraphicsPipelineThreadsCount");
    load(lib, g_api.notifyIncrease, "APerformanceHint_notifyWorkloadIncrease");
    load(lib, g_api.notifyReset, "APerformanceHint_notifyWorkloadReset");
    load(lib, g_api.notifySpike, "APerformanceHint_notifyWorkloadSpike");
    load(lib, g_api.setSurfaces, "APerformanceHint_setNativeSurfaces");
    load(lib, g_api.featureSupported, "APerformanceHint_isFeatureSupported");
    load(lib, g_api.cfgCreate, "ASessionCreationConfig_create");
    load(lib, g_api.cfgRelease, "ASessionCreationConfig_release");
    load(lib, g_api.cfgTids, "ASessionCreationConfig_setTids");
    load(lib, g_api.cfgTarget, "ASessionCreationConfig_setTargetWorkDurationNanos");
    load(lib, g_api.cfgPipeline, "ASessionCreationConfig_setGraphicsPipeline");
    load(lib, g_api.cfgSurfaces, "ASessionCreationConfig_setNativeSurfaces");
    load(lib, g_api.cfgAuto, "ASessionCreationConfig_setUseAutoTiming");

    if (!g_api.getManager || !g_api.createSession || !g_api.updateTarget || !g_api.report ||
        !g_api.closeSession) {
        LOGW("hint session entry points missing");
        return;
    }
    g_api.manager = g_api.getManager();
    if (!g_api.manager) {
        LOGW("no performance hint manager");
        return;
    }
    // API 36 can say outright that sessions are pointless on this device.
    if (g_api.featureSupported && !g_api.featureSupported(kFeatureSessions)) {
        LOGI("hint sessions are switched off on this device");
        return;
    }
    g_caps.manager = true;
    g_caps.setThreads = g_api.setThreads != nullptr;
    g_caps.workDuration2 = g_api.report2 && g_api.wdCreate && g_api.wdRelease && g_api.wdStart &&
                           g_api.wdTotal && g_api.wdCpu && g_api.wdGpu;
    g_caps.config = g_api.createFromConfig && g_api.cfgCreate && g_api.cfgRelease &&
                    g_api.cfgTids && g_api.cfgTarget;
    g_caps.notify = g_api.notifyIncrease && g_api.notifyReset && g_api.notifySpike;
    if (g_caps.config && g_api.featureSupported) {
        g_caps.surfaces = g_api.cfgSurfaces && g_api.setSurfaces &&
                          g_api.featureSupported(kFeatureSurfaceBinding);
        if (g_api.cfgPipeline && g_api.maxPipelineThreads &&
            g_api.featureSupported(kFeatureGraphicsPipeline)) {
            g_caps.maxPipelineThreads = std::max(0, g_api.maxPipelineThreads(g_api.manager));
            g_caps.graphicsPipeline = g_caps.maxPipelineThreads > 0;
        }
        // The framework times a surface's frames only for a graphics-pipeline session bound to it.
        g_caps.autoCpu = g_api.cfgAuto && g_caps.graphicsPipeline && g_caps.surfaces &&
                         g_api.featureSupported(kFeatureAutoCpu);
        g_caps.autoGpu = g_api.cfgAuto && g_caps.graphicsPipeline && g_caps.surfaces &&
                         g_api.featureSupported(kFeatureAutoGpu);
    }
    LOGI("API %d: level %d, setThreads=%d workDuration2=%d config=%d notify=%d surfaces=%d "
         "pipeline=%d (max %d threads) autoCpu=%d autoGpu=%d",
         g_caps.deviceApi, g_caps.level(), g_caps.setThreads, g_caps.workDuration2, g_caps.config,
         g_caps.notify, g_caps.surfaces, g_caps.graphicsPipeline, g_caps.maxPipelineThreads,
         g_caps.autoCpu, g_caps.autoGpu);
}

// Positive, unique, in order.
std::vector<int32_t> sanitize(const std::vector<int32_t>& in) {
    std::vector<int32_t> out;
    out.reserve(in.size());
    for (int32_t tid : in)
        if (tid > 0 && std::find(out.begin(), out.end(), tid) == out.end()) out.push_back(tid);
    return out;
}

}  // namespace

const Caps& caps() {
    std::call_once(g_once, probe);
    return g_caps;
}

bool Session::open(const std::vector<int32_t>& tids, int64_t targetNs, ANativeWindow* window) {
    close();
    reason_.clear();
    failures_ = 0;
    const Caps& c = caps();
    if (!c.manager) {
        reason_ = c.deviceApi < 33 ? "needs Android 13 or newer"
                                   : "performance hints are not available on this device";
        return false;
    }
    const std::vector<int32_t> list = sanitize(tids);
    if (list.empty()) {
        reason_ = "no threads to hint";
        return false;
    }
    if (!create(list, std::max(targetNs, kMinTargetNs), window)) {
        if (reason_.empty()) reason_ = "the system refused the session";
        return false;
    }
    LOGI("session open: %s", describe().c_str());
    return true;
}

bool Session::create(const std::vector<int32_t>& list, int64_t targetNs, ANativeWindow* window) {
    const Caps& c = caps();
    targetNs_ = targetNs;

    // API 36: a graphics-pipeline session. It times the path from the guest's frame to the
    // buffer on screen, is bound to the swapchain's window, and lets the framework measure the
    // CPU and GPU sides itself wherever the device can.
    if (c.config && c.graphicsPipeline) {
        std::vector<int32_t> use = list;
        if (use.size() > (size_t)c.maxPipelineThreads) use.resize((size_t)c.maxPipelineThreads);
        void* cfg = g_api.cfgCreate();
        if (cfg) {
            g_api.cfgTids(cfg, use.data(), use.size());
            g_api.cfgTarget(cfg, targetNs);
            g_api.cfgPipeline(cfg, true);
            const bool bind = c.surfaces && window != nullptr;
            ANativeWindow* windows[1] = {window};
            if (bind) g_api.cfgSurfaces(cfg, windows, 1, nullptr, 0);
            const bool aCpu = bind && c.autoCpu;
            const bool aGpu = bind && c.autoGpu;
            if (aCpu || aGpu) g_api.cfgAuto(cfg, aCpu, aGpu);
            void* made = nullptr;
            const int rc = g_api.createFromConfig(g_api.manager, cfg, &made);
            g_api.cfgRelease(cfg);
            if (rc == 0 && made) {
                session_ = made;
                pipeline_ = true;
                autoCpu_ = aCpu;
                autoGpu_ = aGpu;
                surfaceBound_ = bind;
                threadCount_ = use.size();
                tids_ = use;
                return true;
            }
            // EBUSY hands back a session, but too many pipeline threads would put every
            // graphics-pipeline session of the app into undefined behaviour: not that one.
            if (made) g_api.closeSession(made);
            LOGW("graphics-pipeline session refused (%d: %s); using a plain session", rc,
                 rc > 0 ? strerror(rc) : "unknown");
        }
    }

    void* made = g_api.createSession(g_api.manager, list.data(), list.size(), targetNs);
    if (!made) {
        reason_ = "createSession failed";
        return false;
    }
    session_ = made;
    pipeline_ = autoCpu_ = autoGpu_ = surfaceBound_ = false;
    threadCount_ = list.size();
    tids_ = list;
    // A plain session can still be tied to the window (API 36), which lets the system tune the
    // display path for it.
    if (c.surfaces && window) bindWindow(window);
    return true;
}

void Session::close() {
    if (workDuration_ && g_api.wdRelease) g_api.wdRelease(workDuration_);
    workDuration_ = nullptr;
    if (session_ && g_api.closeSession) g_api.closeSession(session_);
    session_ = nullptr;
    pipeline_ = autoCpu_ = autoGpu_ = surfaceBound_ = false;
    threadCount_ = 0;
}

void Session::setThreads(const std::vector<int32_t>& tids) {
    if (!session_) return;
    std::vector<int32_t> list = sanitize(tids);
    if (pipeline_ && list.size() > (size_t)caps().maxPipelineThreads)
        list.resize((size_t)caps().maxPipelineThreads);
    if (list.empty() || list == tids_) return;
    if (g_api.setThreads) {
        const int rc = g_api.setThreads(session_, list.data(), list.size());
        if (rc == 0) {
            tids_ = list;
            threadCount_ = list.size();
            LOGI("threads set: %zu", list.size());
        } else {
            // A thread that is gone, or not the app's: the old set stays.
            LOGW("setThreads(%zu) failed: %d (%s)", list.size(), rc, rc > 0 ? strerror(rc) : "?");
        }
        return;
    }
    // API 33 cannot change a session's threads: start another one over the new set.
    const int64_t target = targetNs_;
    close();
    if (!create(list, target, nullptr)) LOGW("reopen over %zu threads failed", list.size());
}

void Session::setTarget(int64_t targetNs) {
    if (!session_) return;
    targetNs = std::max(targetNs, kMinTargetNs);
    if (targetNs == targetNs_) return;
    const int rc = g_api.updateTarget(session_, targetNs);
    if (rc == 0) targetNs_ = targetNs;
    noteResult(rc, "updateTargetWorkDuration");
}

void Session::bindWindow(ANativeWindow* window) {
    if (!session_ || !caps().surfaces || !g_api.setSurfaces) return;
    ANativeWindow* windows[1] = {window};
    const int rc = g_api.setSurfaces(session_, window ? windows : nullptr, window ? 1 : 0,
                                     nullptr, 0);
    surfaceBound_ = window != nullptr && rc == 0;
    if (rc != 0) LOGW("setNativeSurfaces failed: %d (%s)", rc, rc > 0 ? strerror(rc) : "?");
}

void Session::reportCycle(int64_t startNs, int64_t totalNs, int64_t cpuNs, int64_t gpuNs) {
    if (!session_) return;
    // Both sides timed by the framework: the session runs itself, reports would be ignored.
    if (autoCpu_ && autoGpu_) return;
    if (autoCpu_) cpuNs = 0;
    if (autoGpu_) gpuNs = 0;
    cpuNs = std::max<int64_t>(cpuNs, 0);
    gpuNs = std::max<int64_t>(gpuNs, 0);
    if (cpuNs == 0 && gpuNs == 0) return;
    totalNs = std::max<int64_t>({totalNs, cpuNs, gpuNs});

    if (caps().workDuration2 && startNs > 0) {
        if (!workDuration_) workDuration_ = g_api.wdCreate();
        if (workDuration_) {
            g_api.wdStart(workDuration_, startNs);
            g_api.wdTotal(workDuration_, totalNs);
            g_api.wdCpu(workDuration_, cpuNs);
            g_api.wdGpu(workDuration_, gpuNs);
            noteResult(g_api.report2(session_, workDuration_), "reportActualWorkDuration2");
            return;
        }
    }
    noteResult(g_api.report(session_, totalNs), "reportActualWorkDuration");
}

void Session::notify(Hint hint, bool cpu, bool gpu, const char* identifier) {
    if (!session_ || !caps().notify) return;
    auto fn = hint == Hint::Increase ? g_api.notifyIncrease
            : hint == Hint::Reset    ? g_api.notifyReset
                                     : g_api.notifySpike;
    noteResult(fn(session_, cpu, gpu, identifier ? identifier : "winlite"), "notifyWorkload");
}

void Session::noteResult(int rc, const char* what) {
    // EBUSY: the framework rate-limited a hint. Not a fault.
    if (rc == 0 || rc == EBUSY) {
        failures_ = 0;
        return;
    }
    // A negative value is an errno, too, on some builds.
    const int err = rc < 0 ? -rc : rc;
    ++failures_;
    if (failures_ == 1 || failures_ % 32 == 0)
        LOGW("%s failed: %d (%s), %d in a row", what, rc, strerror(err), failures_);
    if (failures_ >= kMaxFailures) {
        LOGE("dropping the session after %d failures in a row (last: %s)", failures_, what);
        reason_ = std::string("dropped after repeated errors (") + what + ")";
        close();
    }
}

std::string Session::describe() const {
    if (!session_) return "off";
    char buf[192];
    const char* mode;
    if (pipeline_) {
        mode = autoCpu_ && autoGpu_ ? "graphics pipeline, system timing"
             : autoGpu_             ? "graphics pipeline, system GPU timing"
             : autoCpu_             ? "graphics pipeline, system CPU timing"
                                    : "graphics pipeline";
    } else {
        mode = caps().workDuration2 ? "work duration (CPU)" : "work duration";
    }
    snprintf(buf, sizeof(buf), "API %d: %s%s, %zu thread%s, target %.1f ms", caps().deviceApi, mode,
             surfaceBound_ ? ", surface bound" : "", threadCount_, threadCount_ == 1 ? "" : "s",
             (double)targetNs_ / 1.0e6);
    return buf;
}

}  // namespace adpf
