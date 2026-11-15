#pragma once
#include <atomic>
#include <cstdint>

// The display's refresh grid: the refresh period and a refresh the panel really showed a frame
// at (CLOCK_MONOTONIC ns). One clock per renderer, fed by whichever path puts the frames on the
// display:
//   X composite  VK_GOOGLE_display_timing feedback of the renderer's own presents
//                (VulkanRendererContext::pollPresentTiming)
//   Direct       the present times SurfaceFlinger reports for its transactions (DirectServer)
// Read from any thread: xcore's Present (MSC, FPS-limit intervals), the HUD; every change is
// published into wl.control for the game's WL swapchain (wlcontrol::publishClock).
//
// Writers: one at a time (the path that is showing frames); readers never block.
class DisplayClock {
public:
    // A grid is trusted while a frame reached the display this recently.
    static constexpr uint64_t kFreshNs = 1000000000ull;

    // The refresh period the platform reports (0: unknown). Returns true when it differs by more
    // than 1% from the previous one: another display mode, whose grid starts over (feedback for
    // frames shown before the switch must not anchor it; the caller filters those).
    bool setPeriod(uint64_t periodNs);

    // A frame reached the display at `shownNs`. Older than the anchor: ignored.
    void noteShown(uint64_t shownNs);

    // Forgets the grid (the source changed, the surface went away).
    void reset();

    uint64_t period() const { return period_.load(std::memory_order_relaxed); }

    // The grid while it is fresh at `nowNs`.
    bool grid(uint64_t nowNs, uint64_t& anchor, uint64_t& period) const;

    // For xcore (RendererApi::displayClock) and Java: {period, phase error, sequence}. The phase
    // error is 0 (nothing in the renderer paces the game's frames); the sequence changes with
    // every new anchor.
    bool get(uint64_t nowNs, int64_t out[3]) const;

private:
    std::atomic<uint64_t> period_{0};
    std::atomic<uint64_t> anchor_{0};
    std::atomic<uint64_t> seq_{0};
};
