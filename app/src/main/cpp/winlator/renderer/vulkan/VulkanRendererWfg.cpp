// SPDX-FileCopyrightText: Copyright 2026 qwertypower (DEVAR Entertainment LLC)
// SPDX-License-Identifier: MIT
//
// WFG (WinNative/Winlator Frame Generator, MIT) in the Vulkan renderer.
//
// With WFG on, the scene is drawn into a composite image instead of the swapchain image. Each
// new game frame (a window content update) goes to WFG, which interpolates frames between the
// previous composite and this one straight into extra swapchain images; those are presented
// first, then the composite is copied into the real image and presented. With
// VK_GOOGLE_display_timing every image carries a target time that splits the game's frame
// interval evenly (30 fps x2 at 120 Hz: 16.7 ms each); without it FIFO shows the batch on
// consecutive refreshes, which at a refresh rate above the output rate is uneven.

#include "VulkanRendererContext.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>

#define WLOG_I(...) __android_log_print(ANDROID_LOG_INFO, WLOG_TAG, __VA_ARGS__)

namespace {
// A generated frame waits for a free swapchain image at most about one refresh interval,
// so a full present queue drops generated frames instead of stalling the game's.
constexpr uint64_t kWfgAcquireTimeoutNs    = 3000000ull;
constexpr uint64_t kWfgAcquireTimeoutMaxNs = 12000000ull;
// The game counts as driving the frames while its window changed this recently.
constexpr uint64_t kGameActiveNs           = 100000000ull;

uint64_t nowNs() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

uint64_t VulkanRendererContext::wfgPeriodNs() const {
    const uint32_t mhz = wfgRefreshMhz.load();
    const uint64_t java = mhz > 1000 ? (uint64_t)(1.0e12 / (double)mhz) : 16666667ull;
    // The swapchain's own refresh cycle is exact (Display.getRefreshRate is rounded), but it is
    // taken only while it agrees with the mode Java reports: some drivers keep the value the
    // swapchain was created with after a mode switch.
    if (wfgRefreshNs && wfgRefreshNs * 100 >= java * 97 && wfgRefreshNs * 100 <= java * 103)
        return wfgRefreshNs;
    return java;
}

bool VulkanRendererContext::displayGrid(uint64_t now, uint64_t& anchor, uint64_t& period) const {
    if (!wfgVsyncAnchorNs || now <= wfgVsyncAnchorNs || now - wfgVsyncAnchorNs >= 1000000000ull)
        return false;
    anchor = wfgVsyncAnchorNs;
    period = wfgPeriodNs();
    return true;
}

uint64_t VulkanRendererContext::snapToRefreshes(uint64_t interval, uint64_t period) {
    if (!period) return interval;
    const uint64_t k = (interval + period / 2) / period;
    if (k == 0) return interval;
    const uint64_t whole = k * period;
    const uint64_t diff = whole > interval ? whole - interval : interval - whole;
    return diff * 100 <= interval ? whole : interval;
}

bool VulkanRendererContext::getDisplayClock(int64_t out[3]) const {
    const uint64_t stamp = clockStampNs.load(std::memory_order_acquire);
    const uint64_t now = nowNs();
    if (!stamp || now < stamp || now - stamp > 1000000000ull) return false;
    out[0] = (int64_t)clockPeriodNs.load(std::memory_order_relaxed);
    out[1] = clockArrivalErrNs.load(std::memory_order_relaxed);
    out[2] = (int64_t)clockErrSeq.load(std::memory_order_relaxed);
    return out[0] > 0;
}

// Reads VK_GOOGLE_display_timing feedback (every frame, from renderFrame): the exact refresh
// cycle and when images really reached the display. The latest such time anchors the refresh
// grid; the grid is published for the limiters while it is fresh.
void VulkanRendererContext::wfgPollDisplayTiming(uint64_t javaPeriod) {
    if (getRefreshCycleGOOGLE_) {
        VkRefreshCycleDurationGOOGLE rc{};
        if (getRefreshCycleGOOGLE_(device, swapchain, &rc) == VK_SUCCESS && rc.refreshDuration > 0) {
            if (wfgRefreshNs && (rc.refreshDuration * 100 < wfgRefreshNs * 99 ||
                                 rc.refreshDuration * 100 > wfgRefreshNs * 101))
                wfgVsyncAnchorNs = 0;   // a new mode: the old grid no longer holds
            wfgRefreshNs = rc.refreshDuration;
        }
    }
    // The period in use differs from Java's mode (a switch in progress, or a swapchain cycle
    // that lags it): the grid belongs to the old mode, drop it until feedback arrives again.
    const uint64_t period = wfgPeriodNs();
    if (javaPeriod && (period * 100 < javaPeriod * 97 || period * 100 > javaPeriod * 103))
        wfgVsyncAnchorNs = 0;
    VkPastPresentationTimingGOOGLE past[16];
    for (int round = 0; getPastPresentTimingGOOGLE_ && round < 4; round++) {
        uint32_t count = 16;
        const VkResult r = getPastPresentTimingGOOGLE_(device, swapchain, &count, past);
        if (r != VK_SUCCESS && r != VK_INCOMPLETE) break;
        for (uint32_t i = 0; i < count; i++) {
            const VkPastPresentationTimingGOOGLE& t = past[i];
            if (!t.actualPresentTime) continue;
            if (t.actualPresentTime > wfgVsyncAnchorNs) wfgVsyncAnchorNs = t.actualPresentTime;
            if (t.desiredPresentTime) {   // a paced image: count it against its plan
                wfgPresentTimed++;
                // Planned for the refresh at desired + half a period.
                wfgShowLagNs = (int64_t)t.actualPresentTime -
                               (int64_t)(t.desiredPresentTime + period / 2);
                // The target was the refresh at desired + half a period.
                if (t.actualPresentTime > t.desiredPresentTime + period) wfgPresentLate++;
            }
        }
        if (r != VK_INCOMPLETE) break;
    }
    uint64_t anchor = 0, gridPeriod = 0;
    const uint64_t now = nowNs();
    if (displayGrid(now, anchor, gridPeriod)) {
        clockPeriodNs.store(gridPeriod, std::memory_order_relaxed);
        clockStampNs.store(now, std::memory_order_release);
    }
}

// Before the swapchain image is acquired: a render that carries no new game frame is skipped
// while the game is drawing (what changed shows with its next frame); renderLoop re-renders
// after kGameActiveNs if no game frame comes.
bool VulkanRendererContext::wfgSkipRender() {
    if (!wfg || wfgUnavailable || !wfgSwapchain || !swapchainTransferDst || fbResized.load())
        return false;
    if (contentFrames.load() != wfgSeenFrames) return false;
    const uint64_t last = lastGameFrameNs.load();
    if (!last || nowNs() - last >= kGameActiveNs) return false;
    wfgSkippedRenders++;
    wfgDeferredRender.store(true);
    return true;
}

// After the presents of a WFG frame: advances the queue model by the images presented, one
// refresh each. A real acquire that blocked means at least one more image than modelled waits.
void VulkanRendererContext::wfgNotePresented(const WfgFrame& wf, uint32_t count, uint64_t acquireWaitNs) {
    const uint64_t period = wfgPeriodNs();
    const uint64_t now = nowNs();
    if (wf.newFrame) wfgAcqWaitSum += acquireWaitNs;
    if (wf.paced) {
        // Paced images leave the queue at their target times, not one per refresh.
        wfgQueueEndNs = std::max(now + period, wf.presentAt[wf.genCount]);
        return;
    }
    if (acquireWaitNs > period / 2 && wfgQueueEndNs < now + 2 * period)
        wfgQueueEndNs = now + 2 * period;
    for (uint32_t i = 0; i < count; i++)
        wfgQueueEndNs = std::max(now + period, wfgQueueEndNs + period);
}

void VulkanRendererContext::setWfgEnabled(bool enabled) {
    if (wfgRequested.exchange(enabled) == enabled) return;
    WLOG_I("WFG frame generation %s", enabled ? "on" : "off");
    // The swapchain gains or loses the generation images and FIFO pacing.
    fbResized.store(true);
    needsRender.store(true);
    wakeRender();
}

void VulkanRendererContext::setWfgFpsLimit(int fps) {
    const uint32_t v = (uint32_t)std::clamp(fps, 0, 1000);
    if (wfgFpsLimit.exchange(v) != v) WLOG_I("WFG pacing: game FPS limit %u", v);
}

void VulkanRendererContext::setWfgConfig(int flowSide, bool fastInterp, int targetFps, int multiplier,
                                         float refreshHz) {
    wfgFlowSide.store((uint32_t)std::clamp(flowSide, 64, 1080));
    if (wfgFastInterp.exchange(fastInterp) != fastInterp)
        WLOG_I("WFG interpolation: %s", fastInterp ? "fast (bilinear warp, one source step)"
                                                   : "balanced (Catmull-Rom warp, two source steps)");
    wfgTargetFps.store((uint32_t)std::max(0, targetFps));
    wfgMultiplier.store((uint32_t)std::clamp(multiplier, 0, (int)VKR_DIS_MAX_GENERATIONS + 1));
    const uint32_t mhz = refreshHz > 0.f ? (uint32_t)std::lround(refreshHz * 1000.f) : 0u;
    if (wfgRefreshMhz.exchange(mhz) != mhz)
        WLOG_I("WFG display refresh %.1f Hz, flow side %d, target %d fps, multiplier x%d",
               refreshHz, flowSide, targetFps, multiplier);
}

// Under renderMutex, for every window content update. Counts it as a game frame only when the
// window is the largest of those that changed within the last quarter second.
void VulkanRendererContext::noteContentUpdate(int64_t id, int w, int h) {
    constexpr uint64_t kRecentNs = 250000000ull, kForgetNs = 2000000000ull;
    const uint64_t now = nowNs();
    ContentRate& self = contentRates[id];
    self.area = (int64_t)std::max(w, 0) * std::max(h, 0);
    self.lastNs = now;

    bool largest = true;
    for (auto it = contentRates.begin(); it != contentRates.end();) {
        if (now - it->second.lastNs > kForgetNs) { it = contentRates.erase(it); continue; }
        if (it->first != id && now - it->second.lastNs <= kRecentNs && it->second.area > self.area)
            largest = false;
        ++it;
    }
    if (largest) {
        contentFrames.fetch_add(1, std::memory_order_relaxed);
        lastGameFrameNs.store(now, std::memory_order_relaxed);
    }
}

void VulkanRendererContext::wfgCreateSems() {
    VkSemaphoreCreateInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (auto& slot : wfgGenSems)
        for (auto& sem : slot)
            if (sem == VK_NULL_HANDLE && vk_.CreateSemaphore(device, &si, nullptr, &sem) != VK_SUCCESS)
                throw std::runtime_error("WFG acquire semaphore");
}

// Only with the device idle: an acquire whose submit failed leaves its semaphore signaled.
void VulkanRendererContext::wfgDestroySems() {
    for (auto& slot : wfgGenSems)
        for (auto& sem : slot)
            if (sem != VK_NULL_HANDLE) { vk_.DestroySemaphore(device, sem, nullptr); sem = VK_NULL_HANDLE; }
}

// Runs on the render thread with the swapchain current. Creates or drops the engine as
// requested; false when the swapchain has to be rebuilt for the new state first.
bool VulkanRendererContext::wfgSync() {
    const bool want = wfgRequested.load();
    if (want && !wfg && !wfgUnavailable) {
        if (!vkr_dis_bind_dispatch(gipa, instance)) {
            RLOG_E("WFG: the driver's Vulkan entry points could not be resolved");
            wfgUnavailable = true;
        } else if ((wfg = vkr_dis_create(device, physicalDevice)) == nullptr) {
            RLOG_E("WFG: engine creation failed; frame generation stays off");
            wfgUnavailable = true;
        } else {
            wfgSeenFrames = contentFrames.load();
            wfgIdle = false;
            wfgRealFrames = wfgLogReal = wfgLogMade = 0;
            wfgPace.restart();
            WLOG_I("WFG engine ready");
        }
    } else if (!want) {
        if (wfg) {
            vk_.DeviceWaitIdle(device);
            vkr_dis_destroy(wfg);
            wfg = nullptr;
            wfgDestroyComposite();
        }
        wfgUnavailable = false;
    }
    if (want) {
        try {
            wfgCreateSems();
        } catch (const std::exception& e) {
            RLOG_E("WFG: %s; frame generation stays off", e.what());
            vk_.DeviceWaitIdle(device);
            if (wfg) { vkr_dis_destroy(wfg); wfg = nullptr; }
            wfgDestroyComposite();
            wfgDestroySems();
            wfgUnavailable = true;
        }
    }
    return want == wfgSwapchain;
}

bool VulkanRendererContext::wfgEnsureComposite() {
    if (wfgComposite != VK_NULL_HANDLE && wfgCompositeExt.width == swapchainExt.width &&
        wfgCompositeExt.height == swapchainExt.height)
        return true;
    if (wfgComposite != VK_NULL_HANDLE) {
        vk_.DeviceWaitIdle(device);
        wfgDestroyComposite();
    }

    if (wfgPass == VK_NULL_HANDLE) {
        // Compatible with renderPass (same attachment), so the window pipelines draw into it;
        // it leaves the composite in GENERAL, where WFG reads it.
        VkAttachmentDescription att{};
        att.format = swapchainFmt;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;
        VkSubpassDependency deps[2]{};
        // The previous frame's WFG passes (copies, compute, and the interpolation's fragment pass,
        // which samples it outside the content rect) and its copy read the composite before it
        // is cleared.
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                               VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        deps[0].srcAccessMask = 0;
        deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].srcSubpass = 0;
        deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        deps[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
        VkRenderPassCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        ci.attachmentCount = 1;
        ci.pAttachments = &att;
        ci.subpassCount = 1;
        ci.pSubpasses = &sub;
        ci.dependencyCount = 2;
        ci.pDependencies = deps;
        if (vk_.CreateRenderPass(device, &ci, nullptr, &wfgPass) != VK_SUCCESS) {
            wfgPass = VK_NULL_HANDLE;
            RLOG_E("WFG: composite render pass creation failed");
            return false;
        }
    }

    VkImageCreateInfo ic{};
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = swapchainFmt;
    ic.extent = {swapchainExt.width, swapchainExt.height, 1};
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
               VK_IMAGE_USAGE_SAMPLED_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    try {
        if (vk_.CreateImage(device, &ic, nullptr, &wfgComposite) != VK_SUCCESS)
            throw std::runtime_error("image");
        VkMemoryRequirements req;
        vk_.GetImageMemoryRequirements(device, wfgComposite, &req);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device, &ai, nullptr, &wfgCompositeMem) != VK_SUCCESS)
            throw std::runtime_error("memory");
        vk_.BindImageMemory(device, wfgComposite, wfgCompositeMem, 0);

        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = wfgComposite;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = swapchainFmt;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vk_.CreateImageView(device, &vi, nullptr, &wfgCompositeView) != VK_SUCCESS)
            throw std::runtime_error("view");

        VkFramebufferCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass = wfgPass;
        fi.attachmentCount = 1;
        fi.pAttachments = &wfgCompositeView;
        fi.width = swapchainExt.width;
        fi.height = swapchainExt.height;
        fi.layers = 1;
        if (vk_.CreateFramebuffer(device, &fi, nullptr, &wfgCompositeFB) != VK_SUCCESS)
            throw std::runtime_error("framebuffer");
    } catch (const std::exception& e) {
        RLOG_E("WFG: composite %ux%u unavailable (%s)", swapchainExt.width, swapchainExt.height, e.what());
        wfgDestroyComposite();
        return false;
    }
    wfgCompositeExt = swapchainExt;
    WLOG_I("WFG composite %ux%u", swapchainExt.width, swapchainExt.height);
    return true;
}

void VulkanRendererContext::wfgDestroyComposite() {
    if (wfgCompositeFB != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, wfgCompositeFB, nullptr);
    if (wfgCompositeView != VK_NULL_HANDLE) vk_.DestroyImageView(device, wfgCompositeView, nullptr);
    if (wfgComposite != VK_NULL_HANDLE) vk_.DestroyImage(device, wfgComposite, nullptr);
    if (wfgCompositeMem != VK_NULL_HANDLE) vk_.FreeMemory(device, wfgCompositeMem, nullptr);
    wfgCompositeFB = VK_NULL_HANDLE;
    wfgCompositeView = VK_NULL_HANDLE;
    wfgComposite = VK_NULL_HANDLE;
    wfgCompositeMem = VK_NULL_HANDLE;
    wfgCompositeExt = {0, 0};
}

// With the device idle.
void VulkanRendererContext::wfgDestroy() {
    if (wfg) { vkr_dis_destroy(wfg); wfg = nullptr; }
    wfgDestroyComposite();
    wfgDestroySems();
    if (wfgPass != VK_NULL_HANDLE) { vk_.DestroyRenderPass(device, wfgPass, nullptr); wfgPass = VK_NULL_HANDLE; }
}

// Where the windows land in the swapchain image (identity orientation): WFG analyses only
// that rect and fills the letterbox around it from the composite.
VkrDisContentRect VulkanRendererContext::wfgContentRect(const std::vector<DrawEntry>& draws,
        float ox, float oy, float sx, float sy, float cw, float ch) const {
    const VkrDisContentRect full{0, 0, swapchainExt.width, swapchainExt.height};
    if (cw <= 0.f || ch <= 0.f) return full;
    float x0 = 1.f, y0 = 1.f, x1 = 0.f, y1 = 0.f;
    for (const auto& d : draws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        x0 = std::min(x0, (ox + (float)d.x * sx) / cw);
        x1 = std::max(x1, (ox + (float)(d.x + d.w) * sx) / cw);
        y0 = std::min(y0, (oy + (float)d.y * sy) / ch);
        y1 = std::max(y1, (oy + (float)(d.y + d.h) * sy) / ch);
    }
    if (stretchMode == 1) { x0 = 0.f; x1 = 1.f; }
    x0 = std::clamp(x0, 0.f, 1.f); x1 = std::clamp(x1, 0.f, 1.f);
    y0 = std::clamp(y0, 0.f, 1.f); y1 = std::clamp(y1, 0.f, 1.f);
    if (x1 <= x0 || y1 <= y0) return full;

    const bool sideways = surfaceTransform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ||
                          surfaceTransform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
    const float lw = (float)(sideways ? swapchainExt.height : swapchainExt.width);
    const float lh = (float)(sideways ? swapchainExt.width : swapchainExt.height);
    const int lx0 = (int)std::floor(x0 * lw), lx1 = (int)std::ceil(x1 * lw);
    const int ly0 = (int)std::floor(y0 * lh), ly1 = (int)std::ceil(y1 * lh);
    VkRect2D r = rotateRectLogicalToIdentity({{lx0, ly0}, {(uint32_t)(lx1 - lx0), (uint32_t)(ly1 - ly0)}});

    const int W = (int)swapchainExt.width, H = (int)swapchainExt.height;
    const int rx0 = std::clamp(r.offset.x, 0, W), ry0 = std::clamp(r.offset.y, 0, H);
    const int rx1 = std::clamp(r.offset.x + (int)r.extent.width, 0, W);
    const int ry1 = std::clamp(r.offset.y + (int)r.extent.height, 0, H);
    if (rx1 - rx0 < 16 || ry1 - ry0 < 16) return full;
    return {rx0, ry0, (uint32_t)(rx1 - rx0), (uint32_t)(ry1 - ry0)};
}

// Target display times for this batch: the generated frames and the real one split the game's
// frame interval into equal steps, continuing from the previous batch's real frame. FIFO alone
// would show the batch on consecutive refreshes and then hold the last image: at 120 Hz a
// 30 fps x2 batch reads 8.3 ms + 25 ms, a cadence that feels no smoother than plain 30 fps and
// moves the interpolated frame (t = 0.5) a quarter interval off its own time.
void VulkanRendererContext::wfgPlanPresentTimes(WfgFrame& wf) {
    wf.paced = false;
    auto unpaced = [&] { wfgPace.restart(); };
    if (!displayTiming_ || wf.genCount == 0) { unpaced(); return; }
    const uint64_t measured = vkr_dis_source_interval_ns(wfg);
    if (measured == 0) { unpaced(); return; }

    // The interval to pace on. The measured one is a smoothed estimate that reads a steady
    // 30 fps anywhere from 27.6 to 31.6; when the game holds its FPS limit (measured within
    // 10% of it) the limiter's own interval is exact, so the steps come out even.
    uint64_t interval = measured;
    const uint32_t limit = wfgFpsLimit.load(std::memory_order_relaxed);
    if (limit > 0) {
        const uint64_t limitNs = 1000000000ull / limit;
        if (measured * 10 >= limitNs * 9 && measured * 10 <= limitNs * 11) {
            interval = limitNs;
            wfgLimitPaced++;
        }
    }

    const uint32_t n = wf.genCount + 1;
    uint64_t disp[VKR_DIS_MAX_GENERATIONS + 1];
    const uint64_t prevReal = wfgPace.lastRealDisp;
    paceSchedule(wfgPace, n, interval, lastGameFrameNs.load(std::memory_order_relaxed),
                 wf.presentAt, disp);

    // Interpolation phase from the landing times: a frame shown a third of the way from the
    // previous real frame to this one shows the motion a third of the way (40 fps x2 at 120 Hz
    // alternates 1 and 2 refreshes: t = 1/3 and 2/3, not 0.5 for both). Without a previous
    // real frame on the schedule, or across a gap (a hitch), the even split.
    const uint64_t real = disp[n - 1];
    const bool timed = prevReal && real > prevReal && real - prevReal <= 2 * interval;
    for (uint32_t g = 0; g < wf.genCount; g++) {
        float t = 0.0f;
        if (timed)
            t = std::clamp((float)((double)(disp[g] - prevReal) / (double)(real - prevReal)),
                           0.05f, 0.95f);
        wf.phase[g] = t;   // 0: vkr_dis_generate_into_at uses the even split
    }
    wf.paced = true;
    wfgPacedFrames++;
}

// The pacing core shared by WFG (a batch of generated frames plus the real one) and the plain
// FPS-limited path (one frame). One model for both:
//  - the interval snaps to whole refreshes when it is within 1% of them;
//  - the n images are spaced evenly over it, the schedule phase-locked to the game's frames
//    (proportional + integral, so an interval a little off does not drift into resets);
//  - an image lands no sooner than 'need' after its game frame arrived: a refresh for the GPU
//    work and the latch plus extra learned from the display's feedback (each late image adds a
//    quarter refresh, a run of on-time ones gives 0.1 ms back), so a GPU that needs longer gets
//    it and one that does not is not padded. The schedule itself aims a cushion past 'need'
//    (three times the arrivals' jitter), so a frame that comes a little late still makes it;
//  - each image snaps onto the display grid: the first refresh at or after its target less the
//    cushion (an image may come early by at most the slack it has before 'need', never more), so
//    a schedule whose phase cannot lock to the grid (30 fps on 165 Hz: 5.5 refreshes a frame) does
//    not round below 'need' and get pushed a refresh late; never two images on one refresh;
//  - how far the arrival was from the phase that puts need + cushion exactly on a refresh (the
//    least latency that still absorbs the jitter) is published for the FPS limiters, which slew
//    their release of the game toward it.
//   presentAt[k]: desiredPresentTime of image k; disp[k]: the refresh it is planned to land on
void VulkanRendererContext::paceSchedule(PaceState& st, uint32_t n, uint64_t interval,
                                         uint64_t arrival, uint64_t* presentAt, uint64_t* disp) {
    const uint64_t period = wfgPeriodNs();
    const uint64_t half = period / 2;
    const uint64_t now = nowNs();
    uint64_t anchor = 0, gridPeriod = 0;
    const bool grid = displayGrid(now, anchor, gridPeriod);
    interval = snapToRefreshes(interval, period);
    const uint64_t step = interval / n;

    // Lead learned from the display's feedback.
    const uint64_t lateNow = wfgPresentLate, timedNow = wfgPresentTimed;
    if (st.frames > 0) {
        if (lateNow > st.seenLate) {
            st.leadExtraNs = std::min(st.leadExtraNs + period / 4, period);
            st.onTime = 0;
        } else if (timedNow > st.seenTimed) {
            st.onTime += timedNow - st.seenTimed;
            if (st.onTime >= 240) {
                st.leadExtraNs = st.leadExtraNs > 100000 ? st.leadExtraNs - 100000 : 0;
                st.onTime = 0;
            }
        }
    }
    st.seenLate = lateNow;
    st.seenTimed = timedNow;
    st.frames++;

    // The arrivals' jitter around the interval.
    if (!arrival || arrival > now || now - arrival > interval) arrival = now;
    if (st.lastArrival && arrival > st.lastArrival) {
        const int64_t dev = (int64_t)(arrival - st.lastArrival) - (int64_t)interval;
        const uint64_t a = (uint64_t)(dev < 0 ? -dev : dev);
        if (a < interval) st.jitterNs = (uint64_t)((int64_t)st.jitterNs + ((int64_t)a - (int64_t)st.jitterNs) / 8);
    }
    st.lastArrival = arrival;
    const uint64_t cushion = std::clamp<uint64_t>(3 * st.jitterNs, 500000ull, half);
    const uint64_t need = period + st.leadExtraNs;

    // The first image cannot reach the display before the GPU work and the next latch.
    const uint64_t earliest = now + period;
    const uint64_t ideal = arrival + need + cushion;

    uint64_t start;
    if (!st.lastNs) {
        start = ideal;
        st.integ = 0;
    } else {
        const uint64_t chained = st.lastNs + step;
        const int64_t err = (int64_t)chained - (int64_t)ideal;   // > 0: the schedule runs late
        if (err > (int64_t)(interval / 2) || err < -(int64_t)(interval / 2)) {
            // A real break (a hitch, a burst after a stall, a rate change): start over.
            start = ideal;
            st.integ = 0;
            st.resets++;
        } else {
            constexpr int64_t kP = 6;    // 1/6 of the phase error per frame
            constexpr int64_t kI = 64;   // 1/64 of it into the rate correction
            const int64_t integLimit = (int64_t)(interval / 8);
            st.integ = std::clamp(st.integ + err / kI, -integLimit, integLimit);
            start = (uint64_t)((int64_t)chained - err / kP - st.integ);
        }
    }
    if (start < earliest) {
        start = earliest;   // the game frame came late: as soon as possible
        st.late++;
    }

    // Snap onto the grid: the first refresh at or after target - cushion, never before 'need'
    // after the arrival, never on or before the previous real frame's refresh. With the phase
    // locked (target on a refresh) that is the target's own refresh; otherwise the image comes
    // early by less than the cushion rather than past 'need' and a refresh late. SurfaceFlinger
    // shows a buffer at the first refresh at or after its time, so the time handed over is half
    // a refresh before.
    uint64_t floorNs = std::max(now + half, arrival + need);
    if (st.lastRealDisp && st.lastRealDisp + period > floorNs) floorNs = st.lastRealDisp + period;
    for (uint32_t k = 0; k < n; k++) {
        const uint64_t at = start + (uint64_t)k * step;
        uint64_t d = at;
        if (grid) {
            const uint64_t from = at - cushion;   // at >= now + period > anchor + cushion
            d = anchor + ((from - anchor + period - 1) / period) * period;
            while (d < floorNs) d += period;
        } else if (d < floorNs) {
            d = floorNs;
        }
        disp[k] = d;
        presentAt[k] = d > half ? d - half : d;
        floorNs = d + period;
    }
    st.lastNs = start + (uint64_t)(n - 1) * step;   // unsnapped: no rounding drift
    st.lastRealDisp = disp[n - 1];

    // For the limiters: how far this arrival was from the phase that puts need + cushion exactly
    // on a refresh (> 0: it came late).
    if (grid) {
        const uint64_t x = (arrival + need + cushion - anchor) % period;   // past the last refresh
        int64_t e = (int64_t)x;
        if (e >= (int64_t)half) e -= (int64_t)period;
        clockArrivalErrNs.store(e, std::memory_order_relaxed);
        clockErrSeq.fetch_add(1, std::memory_order_release);
    }
}

// The plain path (no WFG) with an FPS limit: each new game frame gets a target on the refresh
// grid, evenly spaced at the limit's interval, instead of the first refresh after its GPU work
// (which flips between two refreshes whenever the frame lands near a latch, and the limiter's
// clock slowly walks the phase across them). Returns the desiredPresentTime, 0 = as soon as
// possible: no limit, WFG on, no display timing, a non-FIFO present mode, a render that carries
// no new game frame (cursor, UI), or a game that does not hold its limit (pacing a slower game
// to the limit's interval would only add latency).
uint64_t VulkanRendererContext::limitPlanPresent() {
    const uint64_t frames = contentFrames.load(std::memory_order_relaxed);
    const bool fresh = frames != lpSeenFrames;
    lpSeenFrames = frames;
    const uint32_t limit = wfgFpsLimit.load(std::memory_order_relaxed);
    auto unpaced = [&] { lpPace.restart(); };
    if (!displayTiming_ || !swapchainFifo || limit == 0 || wfgRequested.load() || wfg) {
        unpaced();
        lpLastArrival = 0;
        lpIntervalEma = 0;
        return 0;
    }
    if (!fresh) return 0;

    // The game's real frame interval from the arrivals of its frames.
    const uint64_t arrival = lastGameFrameNs.load(std::memory_order_relaxed);
    if (lpLastArrival && arrival > lpLastArrival) {
        const uint64_t d = arrival - lpLastArrival;
        if (d < 500000000ull)
            lpIntervalEma = lpIntervalEma ? lpIntervalEma + ((int64_t)d - (int64_t)lpIntervalEma) / 8 : d;
        else
            lpIntervalEma = 0;   // a stall: measure again
    }
    lpLastArrival = arrival;
    lpGameFrames++;

    const uint64_t limitNs = 1000000000ull / limit;
    if (!lpIntervalEma || lpIntervalEma * 10 < limitNs * 9 || lpIntervalEma * 10 > limitNs * 11) {
        unpaced();
        return 0;
    }

    uint64_t at = 0, disp = 0;
    paceSchedule(lpPace, 1, limitNs, arrival, &at, &disp);
    lpPaced++;

    if (lpGameFrames % 300 == 0) {
        const uint64_t timed = wfgPresentTimed - lpLogTimed, lateShown = wfgPresentLate - lpLogLate;
        lpLogTimed = wfgPresentTimed;
        lpLogLate = wfgPresentLate;
        WLOG_I("Pace delivered frames=%llu paced=%llu limit=%u game=%.2ffps resets=%llu late=%llu "
               "refresh=%.3fms grid=%d shownLate=%llu/%llu showLag=%lldus leadExtra=%lluus jitter=%lluus",
               (unsigned long long)lpGameFrames, (unsigned long long)lpPaced, limit,
               1.0e9 / (double)lpIntervalEma, (unsigned long long)lpPace.resets,
               (unsigned long long)lpPace.late, (double)wfgPeriodNs() / 1.0e6,
               wfgVsyncAnchorNs ? 1 : 0, (unsigned long long)lateShown,
               (unsigned long long)timed, (long long)(wfgShowLagNs / 1000),
               (unsigned long long)(lpPace.leadExtraNs / 1000),
               (unsigned long long)(lpPace.jitterNs / 1000));
    }
    return at;
}

// After the real swapchain image is acquired: decides whether the scene goes to the composite,
// plans the generated frames for a new game frame and acquires their swapchain images.
VulkanRendererContext::WfgFrame VulkanRendererContext::wfgBeginFrame(
        const std::vector<DrawEntry>& draws, float ox, float oy, float sx, float sy, float cw, float ch) {
    WfgFrame wf;
    if (!wfg || !wfgSwapchain || !swapchainTransferDst) return wf;
    if (!wfgEnsureComposite()) return wf;

    const VkrDisContentRect content = wfgContentRect(draws, ox, oy, sx, sy, cw, ch);
    const float refresh = (float)wfgRefreshMhz.load() / 1000.f;
    vkr_dis_configure(wfg, wfgFlowSide.load(), wfgFastInterp.load(), wfgTargetFps.load(),
                      wfgMultiplier.load(), refresh);
    if (vkr_dis_needs_rebuild(wfg, swapchainExt.width, swapchainExt.height, swapchainFmt, content,
                              wfgCompositeView))
        vk_.DeviceWaitIdle(device);
    if (!vkr_dis_prepare(wfg, swapchainExt.width, swapchainExt.height, swapchainFmt, content,
                         wfgCompositeView))
        return wf;

    wf.active = true;
    const uint64_t frames = contentFrames.load();
    wf.newFrame = frames != wfgSeenFrames;
    wfgSeenFrames = frames;
    if (!wf.newFrame) {
        // A render without a new game frame (cursor, UI) follows the last plan's choice.
        if (wfgIdle) wf.active = false;
        return wf;
    }

    const uint32_t images = (uint32_t)swapchainImages.size();
    uint32_t capacity = images > swapchainMinImages + 1 ? images - swapchainMinImages - 1 : 0;
    capacity = std::min(capacity, VKR_DIS_MAX_GENERATIONS);
    uint32_t planned = vkr_dis_plan(wfg, capacity, frames);

    // Nothing to generate, and WFG does not need this frame: draw it to the swapchain directly,
    // without the composite, its copy into the real image and WFG's per-frame copies. The plan
    // still runs on every game frame and asks for frames again when there is something to make.
    if (vkr_dis_idle(wfg)) {
        if (!wfgIdle) WLOG_I("WFG idle: nothing to generate, frames go straight to the display");
        wfgIdle = true;
        vkr_dis_skip_frame(wfg);
        wfgPace.restart();   // the next paced batch starts its schedule over
        return WfgFrame{};
    }
    if (wfgIdle) WLOG_I("WFG resumes generating");
    wfgIdle = false;

    // Images still waiting for the display beyond the next refresh. More than two means the
    // queue has grown (a game slightly faster than the output cadence, a burst after a stall):
    // drop one generated frame per pair until it drains, since every image presented now
    // reaches the display that much later. One at a time keeps the motion cadence even.
    const uint64_t period = wfgPeriodNs();
    const uint64_t now = nowNs();
    const uint64_t ahead = wfgQueueEndNs > now + period ? (wfgQueueEndNs - now - period) / period : 0;
    wfgAheadSum += ahead;
    if (displayTiming_ && wfgPace.lastRealDisp && wfgPace.lastNs) {
        // Paced: the queue is the display chain itself. Each image owns a refresh, so a game
        // frame that comes early, or an image the display showed late (feedback), pushes the
        // chain behind the schedule; the coarse queue model below cannot tell that from the
        // designed lead and drops frames the schedule has room for. One generated frame is
        // dropped only once the chain is more than a refresh and a half behind: a refresh of
        // jitter is left for the next frame to absorb.
        int64_t lag = (int64_t)wfgPace.lastRealDisp - (int64_t)wfgPace.lastNs;
        if (wfgShowLagNs > 0) lag += wfgShowLagNs;
        wfgLagSum += lag;
        if (lag > (int64_t)(period + period / 2) && planned > 0) {
            planned--;
            wfgTrimmedFrames++;
            wfgTrimLag++;
        }
    } else if (ahead > 2 && planned > 0) {
        planned--;
        wfgTrimmedFrames++;
        wfgTrimQueue++;
    }

    uint64_t timeout = kWfgAcquireTimeoutNs;
    if (refresh > 1.f)
        timeout = std::clamp((uint64_t)(1.0e9f / refresh), kWfgAcquireTimeoutNs, kWfgAcquireTimeoutMaxNs);
    for (uint32_t g = 0; g < planned; g++) {
        uint32_t idx = 0;
        VkResult r = vk_.AcquireNextImageKHR(device, swapchain, timeout,
                                             wfgGenSems[currentFrame][g], VK_NULL_HANDLE, &idx);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) fbResized.store(true);
            if (wfgAcquireMisses++ % 120 == 0)
                RLOG_E("WFG: generated frame %u/%u dropped, acquire returned %d (images=%u)",
                       g + 1, planned, (int)r, images);
            break;
        }
        if (idx >= images || idx >= swapchainFBs.size()) break;
        if (idx < imgInFlight.size()) imgInFlight[idx] = inFlightFences[currentFrame];
        wf.genIdx[wf.genCount++] = idx;
    }
    wfgPlanPresentTimes(wf);
    return wf;
}

// After recordCmdBuf drew the scene into the composite (command buffer still open): runs WFG,
// writes the generated frames, copies the composite into the real image and ends the buffer.
void VulkanRendererContext::wfgRecord(VkCommandBuffer cb, uint32_t imgIdx, WfgFrame& wf) {
    const uint32_t w = swapchainExt.width, h = swapchainExt.height;

    if (wf.newFrame) {
        vkr_dis_process(wfg, cb, wfgComposite, w, h, wf.genCount);
        // Each generated frame is one fragment pass straight into its swapchain image, through the
        // renderer's own framebuffer for it; the pass leaves the image in PRESENT_SRC_KHR. Should
        // WFG not write one, the image is acquired and will be presented regardless, so it gets
        // the real frame (a repeat) rather than whatever it held.
        for (uint32_t g = 0; g < wf.genCount; g++) {
            const uint32_t idx = wf.genIdx[g];
            if (vkr_dis_generate_into_at(wfg, cb, g, wf.paced ? wf.phase[g] : 0.0f,
                                         swapchainFBs[idx], w, h))
                continue;
            if (wfgGenFallbacks++ % 120 == 0)
                RLOG_E("WFG: generated frame %u/%u not written; showing the real frame", g + 1,
                       wf.genCount);
            VkImage gdst = swapchainImages[idx];
            transition(cb, gdst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       0, VK_ACCESS_TRANSFER_WRITE_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageCopy gcopy{};
            gcopy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            gcopy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            gcopy.extent = {w, h, 1};
            vk_.CmdCopyImage(cb, wfgComposite, VK_IMAGE_LAYOUT_GENERAL,
                             gdst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &gcopy);
            transition(cb, gdst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                       VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
        if (wf.genCount > 0) {
            wfgRealFrames++;
            const uint64_t made = wfgMadeFrames.fetch_add(wf.genCount) + wf.genCount;
            if (wfgRealFrames % 300 == 0) {
                const uint64_t dReal = wfgRealFrames - wfgLogReal, dMade = made - wfgLogMade;
                wfgLogReal = wfgRealFrames;
                wfgLogMade = made;
                WLOG_I("WFG delivered real=%llu made=%llu ratio=%.2f misses=%llu skipped=%llu "
                       "trimmed=%llu ahead=%.2f paced=%llu limitPaced=%llu paceResets=%llu paceLate=%llu integ=%lldus "
                       "refresh=%.3fms grid=%d shownLate=%llu/%llu trimLag=%llu trimQueue=%llu "
                       "lag=%.2fv showLag=%lldus acqWait=%.2fms leadExtra=%lluus jitter=%lluus",
                       (unsigned long long)wfgRealFrames, (unsigned long long)made,
                       dReal ? (double)dMade / (double)dReal : 0.0,
                       (unsigned long long)wfgAcquireMisses, (unsigned long long)wfgSkippedRenders,
                       (unsigned long long)wfgTrimmedFrames,
                       dReal ? (double)wfgAheadSum / (double)dReal : 0.0,
                       (unsigned long long)wfgPacedFrames, (unsigned long long)wfgLimitPaced,
                       (unsigned long long)wfgPace.resets, (unsigned long long)wfgPace.late,
                       (long long)(wfgPace.integ / 1000),
                       (double)wfgPeriodNs() / 1.0e6, wfgVsyncAnchorNs ? 1 : 0,
                       (unsigned long long)wfgPresentLate, (unsigned long long)wfgPresentTimed,
                       (unsigned long long)wfgTrimLag, (unsigned long long)wfgTrimQueue,
                       dReal ? (double)wfgLagSum / (double)dReal / (double)wfgPeriodNs() : 0.0,
                       (long long)(wfgShowLagNs / 1000),
                       dReal ? (double)wfgAcqWaitSum / (double)dReal / 1.0e6 : 0.0,
                       (unsigned long long)(wfgPace.leadExtraNs / 1000),
                       (unsigned long long)(wfgPace.jitterNs / 1000));
                wfgLagSum = 0;
                wfgAcqWaitSum = 0;
                wfgAheadSum = 0;
            }
        }
    }

    VkImage dst = swapchainImages[imgIdx];
    transition(cb, dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               0, VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.extent = {w, h, 1};
    vk_.CmdCopyImage(cb, wfgComposite, VK_IMAGE_LAYOUT_GENERAL,
                     dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    transition(cb, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_ACCESS_TRANSFER_WRITE_BIT, 0,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("WFG: EndCommandBuffer failed status=%d", (int)endStatus);
        throw std::runtime_error("end cb");
    }
}
