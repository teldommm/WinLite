#include "perf_hint.h"

#include <android/log.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>

bool PerfHint::available() {
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

void PerfHint::ensureSession(int64_t targetNs) {
    if (!available()) return;
    targetNs = std::max<int64_t>(1, targetNs);
    std::lock_guard<std::mutex> lk(mutex);
    if (!session) {
        manager = getManager();
        if (!manager) return;
        const int32_t tid = static_cast<int32_t>(syscall(SYS_gettid));
        session = createSession(manager, &tid, 1, targetNs);
        if (!session) return;
        sessionTargetNs = targetNs;
        if (!logged) {
            logged = true;
            __android_log_print(ANDROID_LOG_INFO, "Winlator_Renderer",
                "PerfHint: session created (tid=%d, target=%lld ns)", tid,
                static_cast<long long>(targetNs));
        }
        return;
    }
    if (targetNs != sessionTargetNs) {
        if (updateTarget(session, targetNs) == 0) sessionTargetNs = targetNs;
    }
}

void PerfHint::reportWork(int64_t workNs) {
    std::lock_guard<std::mutex> lk(mutex);
    if (session) report(session, std::max<int64_t>(1, workNs));
}

void PerfHint::close() {
    std::lock_guard<std::mutex> lk(mutex);
    if (session) {
        closeSessionFn(session);
        session = nullptr;
    }
}

bool PerfHint::hasSession() const {
    std::lock_guard<std::mutex> lk(mutex);
    return session != nullptr;
}
