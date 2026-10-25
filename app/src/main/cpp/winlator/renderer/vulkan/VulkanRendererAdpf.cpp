// ADPF (Android Performance Hint) for the Vulkan renderer: see adpf/adpf_session.h for the
// device side. What lives here is what the renderer decides: which threads belong to the
// session, what one cycle of work is, how long it really worked, and when the load changes.
//
// What is reported: the render thread's CPU time for a cycle (renderFrame, from its first line
// to the end of the last present) minus the time it spent blocked - on the previous frame's
// fence, on a swapchain image, on an older image's fence. That time is waiting for the GPU or the
// display, not work; reporting it would tell the system the thread needs more clock when it
// needs none. The GPU side is not reported from here: a timestamp span around a submit that waits
// for a swapchain image also measures that wait (the frame-generation chain timing says the same),
// so the GPU is timed only by the framework where the device offers it (API 36 graphics-pipeline
// session) and otherwise left unmeasured, which the API allows.
#include "VulkanRendererContext.h"

#include <cstdlib>
#include <unistd.h>

static int64_t adpfNowNs() {   // CLOCK_MONOTONIC, the clock the work durations are in
    return (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void VulkanRendererContext::setPerformanceHint(bool enabled) {
    if (adpfWanted_.exchange(enabled, std::memory_order_acq_rel) == enabled) return;
    if (enabled) adpfSetStatus("starting");
    adpfDirty_.store(true, std::memory_order_release);
    wakeRender();
}

void VulkanRendererContext::setPerformanceHintThreads(const int32_t* tids, size_t count) {
    std::vector<int32_t> list;
    for (size_t i = 0; tids && i < count; i++)
        if (tids[i] > 0) list.push_back(tids[i]);
    {
        std::lock_guard<std::mutex> lk(adpfMutex_);
        if (list == adpfCompanions_) return;
        adpfCompanions_ = std::move(list);
        adpfCompanionsDirty_ = true;
    }
    if (!adpfWanted_.load(std::memory_order_relaxed)) return;
    adpfDirty_.store(true, std::memory_order_release);
    wakeRender();
}

std::string VulkanRendererContext::performanceHintStatus() {
    std::lock_guard<std::mutex> lk(adpfMutex_);
    return adpfStatus_;
}

void VulkanRendererContext::adpfSetStatus(const std::string& text) {
    std::lock_guard<std::mutex> lk(adpfMutex_);
    adpfStatus_ = text;
}

void VulkanRendererContext::adpfNote(uint32_t bits) {
    if (!adpfWanted_.load(std::memory_order_relaxed)) return;
    adpfNotifyBits_.fetch_or(bits, std::memory_order_relaxed);
    adpfDirty_.store(true, std::memory_order_release);
    wakeRender();
}

int64_t VulkanRendererContext::adpfCycleNs() const {
    // One cycle = one guest frame's trip through the compositor. It is as long as the display's
    // refresh, stretched by an FPS limit (the guest delivers no faster) and, with frame
    // generation, by the measured source interval (one source frame is composited, then its
    // generated frames follow).
    int64_t cycle = refreshPeriodNs_.load(std::memory_order_relaxed);
    if (cycle <= 0) cycle = 16666667;   // refresh rate not known yet: 60 Hz
    const int fps = fpsLimit_.load(std::memory_order_relaxed);
    if (fps > 0) cycle = std::max<int64_t>(cycle, 1000000000LL / fps);
    if (fgArmed_.load(std::memory_order_relaxed)) cycle = std::max(cycle, fgSourceIntervalNs());
    return std::clamp<int64_t>(cycle, 2000000, 200000000);
}

void VulkanRendererContext::adpfService() {
    // Cleared first: a request that arrives while this runs sets it again and is served next time.
    adpfDirty_.store(false, std::memory_order_release);

    // frameMutex shared: setters that hold it exclusive also touch the session (surface binding),
    // and the window pointer below is only stable under it.
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    if (!adpfWanted_.load(std::memory_order_acquire)) {
        if (adpfSession_.isOpen()) {
            adpfSession_.close();
            RLOG("adpf: session closed");
        }
        adpfOpenTried_ = false;
        adpfNotifyBits_.store(0, std::memory_order_relaxed);
        adpfSetStatus("off");
        return;
    }

    std::vector<int32_t> tids;
    bool companionsChanged;
    {
        std::lock_guard<std::mutex> lk(adpfMutex_);
        tids.push_back(renderTid_.load(std::memory_order_relaxed));   // the pipeline's head first
        tids.insert(tids.end(), adpfCompanions_.begin(), adpfCompanions_.end());
        companionsChanged = adpfCompanionsDirty_;
        adpfCompanionsDirty_ = false;
    }

    if (!adpfSession_.isOpen()) {
        // One attempt per switch-on: a device that refuses will refuse again, and a session that
        // was dropped for repeated errors is not worth hammering the service for.
        adpfNotifyBits_.store(0, std::memory_order_relaxed);   // a new session starts blank
        if (adpfOpenTried_) return;
        adpfOpenTried_ = true;
        ANativeWindow* win = surfaceDetached.load(std::memory_order_acquire) ? nullptr : window;
        const int64_t cycle = adpfCycleNs();
        if (adpfSession_.open(tids, cycle, win)) {
            adpfTargetNs_ = cycle;
            adpfSetStatus(adpfSession_.describe());
        } else {
            adpfSetStatus("unavailable: " + adpfSession_.reason());
            RLOG_E("adpf: unavailable: %s", adpfSession_.reason().c_str());
        }
        return;
    }

    if (companionsChanged) {
        adpfSession_.setThreads(tids);
        adpfSetStatus(adpfSession_.describe());
    }

    const uint32_t bits = adpfNotifyBits_.exchange(0, std::memory_order_relaxed);
    if (bits & kAdpfResetAll)         adpfSession_.notify(adpf::Hint::Reset, true, true, "winlite-reset");
    else if (bits & kAdpfResetGpu)    adpfSession_.notify(adpf::Hint::Reset, false, true, "winlite-gpu-effect");
    if (bits & kAdpfIncreaseGpu)      adpfSession_.notify(adpf::Hint::Increase, false, true, "winlite-frame-gen");
    if (bits & kAdpfSpikeAll)         adpfSession_.notify(adpf::Hint::Spike, true, true, "winlite-rebuild");
}

void VulkanRendererContext::adpfShutdown() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);
    adpfSession_.close();
}

// renderFrame's success path, frameMutex shared.
void VulkanRendererContext::adpfFrameEnd(int64_t startNs, int64_t blockedNs) {
    if (!adpfSession_.isOpen() || startNs <= 0) return;
    const int64_t cpuNs = std::max<int64_t>(adpfNowNs() - startNs - blockedNs, 1);

    // The target follows the refresh rate, the FPS limit and frame generation. A dead band keeps
    // it from being rewritten for every wobble of the measured source rate.
    const int64_t cycle = adpfCycleNs();
    if (std::llabs(cycle - adpfTargetNs_) * 20 > cycle) {
        adpfSession_.setTarget(cycle);
        adpfTargetNs_ = cycle;
        adpfSetStatus(adpfSession_.describe());
    }

    // No GPU duration: see the note at the top of this file.
    adpfSession_.reportCycle(startNs, cpuNs, cpuNs, 0);
    if (!adpfSession_.isOpen())   // dropped after repeated errors
        adpfSetStatus("unavailable: " + adpfSession_.reason());
}
