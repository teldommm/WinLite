#include "VulkanRendererClock.h"
#include "WlControl.h"

bool DisplayClock::setPeriod(uint64_t periodNs) {
    const uint64_t old = period_.load(std::memory_order_relaxed);
    if (periodNs == old) return false;
    period_.store(periodNs, std::memory_order_relaxed);
    const bool newMode = old && periodNs &&
        (periodNs * 100 < old * 99 || periodNs * 100 > old * 101);
    if (newMode || !periodNs) reset();
    else wlcontrol::publishClock(periodNs, anchor_.load(std::memory_order_relaxed));
    return newMode;
}

void DisplayClock::noteShown(uint64_t shownNs) {
    if (!shownNs || shownNs <= anchor_.load(std::memory_order_relaxed)) return;
    anchor_.store(shownNs, std::memory_order_release);
    seq_.fetch_add(1, std::memory_order_release);
    // The game's WL swapchain paces its FPS limit and frame generation on this grid.
    wlcontrol::publishClock(period_.load(std::memory_order_relaxed), shownNs);
}

void DisplayClock::reset() {
    anchor_.store(0, std::memory_order_release);
    seq_.fetch_add(1, std::memory_order_release);
    wlcontrol::publishClock(period_.load(std::memory_order_relaxed), 0);
}

bool DisplayClock::grid(uint64_t nowNs, uint64_t& anchor, uint64_t& period) const {
    const uint64_t a = anchor_.load(std::memory_order_acquire);
    const uint64_t p = period_.load(std::memory_order_relaxed);
    if (!a || !p || nowNs <= a || nowNs - a >= kFreshNs) return false;
    anchor = a;
    period = p;
    return true;
}

bool DisplayClock::get(uint64_t nowNs, int64_t out[3]) const {
    uint64_t anchor = 0, period = 0;
    if (!grid(nowNs, anchor, period)) return false;
    out[0] = (int64_t)period;
    out[1] = 0;
    out[2] = (int64_t)(uint32_t)seq_.load(std::memory_order_acquire);
    return true;
}
