// ADPF (Android Performance Hint) for the renderer: one hint session over the threads that
// carry a frame (the render thread, the X server's connector thread, the Present limiter
// threads), fed with the render thread's per-cycle work duration.
//
// Everything is resolved with dlsym: the app's minSdk is 28 and the API arrives in steps, so the
// session uses the best the device offers and quietly falls back:
//   API 36  createSessionUsingConfig: graphics-pipeline session bound to the swapchain's
//           ANativeWindow, the framework's own CPU/GPU timing where the device has it, workload
//           hints (increase / reset / spike) when the load changes
//   API 35  reportActualWorkDuration2 (start, total, CPU, GPU breakdown)
//   API 34  setThreads (thread set can change while the session lives)
//   API 33  createSession + updateTargetWorkDuration + reportActualWorkDuration
// Below 33 (or with hint sessions switched off by the device) nothing is created.
//
// Threading: a session is NOT thread safe. The caller serialises every call on one Session
// (VulkanRendererContext does it under its frame lock). caps() is safe from any thread.
#pragma once

#include <android/native_window.h>

#include <cstdint>
#include <string>
#include <vector>

namespace adpf {

struct Caps {
    int  deviceApi = 0;
    bool manager = false;           // hint sessions are usable at all (API 33+, manager present)
    bool setThreads = false;        // API 34
    bool workDuration2 = false;     // API 35
    bool config = false;            // API 36: createSessionUsingConfig
    bool notify = false;            // API 36: notifyWorkload*
    bool surfaces = false;          // API 36: sessions can be bound to surfaces
    bool graphicsPipeline = false;  // API 36: graphics-pipeline sessions
    bool autoCpu = false;           // API 36: the framework times the CPU side
    bool autoGpu = false;           // API 36: the framework times the GPU side
    int  maxPipelineThreads = 0;    // API 36: threads a graphics-pipeline session may hold
    // Highest API step the device offers: 0 = none, otherwise 33, 34, 35 or 36.
    int level() const {
        if (!manager) return 0;
        if (config) return 36;
        if (workDuration2) return 35;
        if (setThreads) return 34;
        return 33;
    }
};

// Probed once, on first use.
const Caps& caps();

enum class Hint { Increase, Reset, Spike };

class Session {
public:
    Session() = default;
    ~Session() { close(); }
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    // Creates the session over `tids` (render thread first: a graphics-pipeline session keeps
    // only as many as the device allows, in this order). targetNs: the duration one cycle of
    // work should fit in. window: the swapchain's window, for surface binding (may be null).
    // False if the device offers nothing; reason() says why.
    bool open(const std::vector<int32_t>& tids, int64_t targetNs, ANativeWindow* window);
    void close();
    bool isOpen() const { return session_ != nullptr; }

    // Replaces the thread set (API 34); older devices reopen the session over the new set.
    void setThreads(const std::vector<int32_t>& tids);
    void setTarget(int64_t targetNs);
    // Re-binds the session to the swapchain's window after it was replaced; null = none.
    void bindWindow(ANativeWindow* window);
    // One cycle of work. startNs: CLOCK_MONOTONIC when it began; totalNs: its duration from
    // there; cpuNs / gpuNs: what was spent on each side, 0 = not measured. Sides the framework
    // times itself are ignored, and with both automatic nothing is sent.
    void reportCycle(int64_t startNs, int64_t totalNs, int64_t cpuNs, int64_t gpuNs);
    // Workload hint (API 36); silently dropped where the device has none.
    void notify(Hint hint, bool cpu, bool gpu, const char* identifier);

    bool autoCpu() const { return autoCpu_; }
    bool autoGpu() const { return autoGpu_; }
    // One line for logs and the UI: mode, thread count, target.
    std::string describe() const;
    // Why open() gave nothing (empty when it did).
    const std::string& reason() const { return reason_; }

private:
    bool create(const std::vector<int32_t>& tids, int64_t targetNs, ANativeWindow* window);
    void noteResult(int rc, const char* what);

    void*   session_ = nullptr;     // APerformanceHintSession*
    void*   workDuration_ = nullptr;  // AWorkDuration*, reused every cycle
    bool    pipeline_ = false;
    bool    autoCpu_ = false;
    bool    autoGpu_ = false;
    bool    surfaceBound_ = false;
    size_t  threadCount_ = 0;
    int64_t targetNs_ = 0;
    int     failures_ = 0;          // consecutive failed calls; the session is dropped at the limit
    std::vector<int32_t> tids_;     // what the session was last given, for a reopen
    std::string reason_;
};

}  // namespace adpf
