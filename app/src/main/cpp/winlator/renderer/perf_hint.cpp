#include "perf_hint.h"

#include <android/log.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

namespace {
int64_t threadCpuTimeNs() {
    timespec time{};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &time) != 0) return 0;
    return static_cast<int64_t>(time.tv_sec) * 1000000000LL + time.tv_nsec;
}
}

PerfHint::~PerfHint() {
    closeSession();
    if (lib) dlclose(lib);
}

void PerfHint::setEnabled(bool value) {
    std::lock_guard<std::mutex> lock(mutex);
    const bool previous = enabled.exchange(value, std::memory_order_relaxed);
    if (previous && !value) {
        closeSession();
        retryAt = {};
    }
}

bool PerfHint::resolveApi() {
    if (resolved) return getManager != nullptr;
    resolved = true;
    lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return false;
    getManager = reinterpret_cast<GetManagerFn>(
        dlsym(lib, "APerformanceHint_getManager"));
    createSession = reinterpret_cast<CreateSessionFn>(
        dlsym(lib, "APerformanceHint_createSession"));
    updateTarget = reinterpret_cast<UpdateTargetFn>(
        dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
    report = reinterpret_cast<ReportWorkFn>(
        dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    closeSessionFn = reinterpret_cast<CloseSessionFn>(
        dlsym(lib, "APerformanceHint_closeSession"));
    const bool ok = getManager && createSession && updateTarget && report && closeSessionFn;
    if (!ok) {
        getManager = nullptr; createSession = nullptr; updateTarget = nullptr;
        report = nullptr; closeSessionFn = nullptr;
        __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer",
            "PerfHint: APerformanceHint API not available on this device");
    }
    return ok;
}

int64_t PerfHint::beginWork() {
    if (!enabled.load(std::memory_order_relaxed)) return 0;
    std::lock_guard<std::mutex> lock(mutex);
    if (!enabled.load(std::memory_order_relaxed) || !resolveApi()) return 0;
    if (retryAt != std::chrono::steady_clock::time_point{} &&
        std::chrono::steady_clock::now() < retryAt) return 0;
    return threadCpuTimeNs();
}

void PerfHint::reportWork(int64_t startNs, int64_t targetNs) {
    if (startNs <= 0 || targetNs <= 0) return;
    const int64_t workNs = threadCpuTimeNs() - startNs;
    if (workNs <= 0) return;

    std::lock_guard<std::mutex> lock(mutex);
    if (!enabled.load(std::memory_order_relaxed)) return;
    if (!session) {
        const int32_t tid = static_cast<int32_t>(syscall(SYS_gettid));
        void* manager = getManager();
        if (manager) session = createSession(manager, &tid, 1, targetNs);
        if (!session) {
            retryLater();
            return;
        }
        sessionTargetNs = targetNs;
        if (!creationLogged) {
            creationLogged = true;
            __android_log_print(ANDROID_LOG_INFO, "Winlator_Renderer",
                "PerfHint: session created (tid=%d, target=%lld ns)", tid,
                static_cast<long long>(targetNs));
        }
    }
    if (targetNs != sessionTargetNs) {
        if (updateTarget(session, targetNs) != 0) {
            retryLater();
            return;
        }
        sessionTargetNs = targetNs;
    }
    if (report(session, workNs) != 0) {
        retryLater();
        return;
    }
    retryAt = {};
    failureLogged = false;
}

void PerfHint::retryLater() {
    closeSession();
    retryAt = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    if (!failureLogged) {
        failureLogged = true;
        __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer",
            "PerfHint: session unavailable, will retry in 5 seconds");
    }
}

void PerfHint::close() {
    std::lock_guard<std::mutex> lock(mutex);
    closeSession();
}

void PerfHint::closeSession() {
    if (session) {
        closeSessionFn(session);
        session = nullptr;
    }
}
