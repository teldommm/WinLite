#pragma once

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>

// The NDK Performance Hint API (Android 13 / API 33+) is loaded at runtime. Only
// the render thread is in the session. Enabling and closing are serialized with reporting.
class PerfHint {
public:
    ~PerfHint();
    void setEnabled(bool value);

    // Both calls run on the render thread. A zero start means sampling is unavailable.
    int64_t beginWork();
    void reportWork(int64_t startNs, int64_t targetNs);

    // Drop the session while the surface is detached. The setting is retained.
    void close();

private:
    bool resolveApi();
    void closeSession();
    void retryLater();

    // Opaque stand-ins for the NDK types, so no android/performance_hint.h dependency remains
    // at compile time (the symbols are looked up at runtime anyway).
    using GetManagerFn = void* (*)();
    using CreateSessionFn = void* (*)(void* manager, const int32_t* threadIds, size_t size,
                                        int64_t initialTargetWorkDurationNanos);
    using UpdateTargetFn = int (*)(void* session, int64_t targetDurationNanos);
    using ReportWorkFn = int (*)(void* session, int64_t actualDurationNanos);
    using CloseSessionFn = void (*)(void* session);

    void* lib = nullptr;
    GetManagerFn getManager = nullptr;
    CreateSessionFn createSession = nullptr;
    UpdateTargetFn updateTarget = nullptr;
    ReportWorkFn report = nullptr;
    CloseSessionFn closeSessionFn = nullptr;

    std::atomic<bool> enabled{false};
    std::mutex mutex;
    void* session = nullptr;
    int64_t sessionTargetNs = 0;
    std::chrono::steady_clock::time_point retryAt{};
    bool resolved = false;
    bool creationLogged = false;
    bool failureLogged = false;
};
