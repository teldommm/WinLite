#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>

// Android PerformanceHint (APerformanceHint_* from libandroid.so, API 30+), resolved with
// dlopen/dlsym so the renderer builds and runs on any API level and on devices whose power HAL
// has no implementation (every call then safely does nothing).
//
// A session tells the device's power HAL two things: the target frame time and how long the
// render thread's CPU work actually took. That lets the CPU be boosted exactly for the
// emulation load of a produced frame instead of holding a high clock bias (or stuttering on an
// inert governor). See APerformanceHintManager in the NDK docs.
//
// Threading: reportWork()/ensureSession() run on the render thread. close() may run on any
// thread (a settings toggle): it is serialized with the rest through an internal mutex and
// drops the session; a later ensureSession() recreates it.
class PerfHint {
public:
    // Resolve the API from libandroid.so. False when a symbol is missing (API < 30 or a HAL
    // without the feature); cheap after the first call.
    bool available();

    // Create the session on the current thread's tid if none exists and keep the target work
    // duration at targetNs. Safe to call every frame: after the first it only updates the
    // target when it changed.
    void ensureSession(int64_t targetNs);

    // Actual CPU work duration of the last frame (nanoseconds, >= 1).
    void reportWork(int64_t workNs);

    // Drop the session. Idempotent; callable from any thread.
    void close();

    bool hasSession() const;

private:
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

    mutable std::mutex mutex;
    void* manager = nullptr;
    void* session = nullptr;
    int64_t sessionTargetNs = 0;
    bool resolved = false;
    bool logged = false;   // one log line per process is enough
};
