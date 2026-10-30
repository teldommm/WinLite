// WFG (WinNative/Winlator Frame Generator, MIT; see wfg/LICENSE) in the Vulkan renderer.
//
// With WFG on, the scene is drawn into a composite image instead of the swapchain image. Each new
// game frame (a content update of the game window) goes to WFG, which interpolates frames between
// the previous composite and this one straight into extra swapchain images; those are presented
// first, then the composite is copied into the real image and presented. The batch is
// (generated frames + the real one) images, one refresh each, so the game's frame is shown
// (generated frames) later than without generation.
//
// What the batch is paced by is the renderer's own machinery (VulkanRendererContext.cpp):
//  * limiter: a frame held for its vsync slot is composited just before it, and the batch starts
//    at that slot. The distance to the next frame's slot (PendingPresent::outputs: 3,3,2 for 45 on
//    120 Hz) is the number of images the batch holds, so the output stays on the refresh grid;
//  * FIFO queue: every image presented advances presentSlotEndNs_ (notePresentedForPacing), the
//    next composite waits on it (pacingWaitNs), and a queue that has grown trims the batch;
//  * cursor: a render without a new game frame is left to the next game frame while the game is
//    drawing - one more image would only push the batch's frames a refresh late;
//  * ADPF: the cycle is the batch interval (adpfCycleNs), the session is told when generation
//    starts or stops.

#include "VulkanRendererContext.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>

#define WLOG_I(...) __android_log_print(ANDROID_LOG_INFO, WLOG_TAG, __VA_ARGS__)

namespace {
// A generated frame waits for a free swapchain image at most about one refresh interval, so a
// full present queue drops generated frames instead of stalling the game's.
constexpr uint64_t kAcquireTimeoutNs    = 3000000ull;
constexpr uint64_t kAcquireTimeoutMaxNs = 12000000ull;
// The game counts as driving the frames while its window changed this recently.
constexpr int64_t kGameActiveNs = 100000000LL;
// The game window is the largest of the windows that changed within kRecentNs.
constexpr int64_t kRecentNs = 250000000LL;
constexpr int64_t kForgetNs = 2000000000LL;
constexpr uint64_t kImageFenceWaitNs = 50000000ull;

// CLOCK_MONOTONIC, the clock of the vsync grid and of the pacing model.
int64_t nowNs() {
    return (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

void VulkanRendererContext::setWfgEnabled(bool enabled) {
    if (wfgRequested_.exchange(enabled) == enabled) return;
    WLOG_I("WFG frame generation %s", enabled ? "on" : "off");
    // The swapchain gains or loses the generation images, and FIFO pacing is forced.
    fbResized.store(true);
    needsRender.store(true);
    adpfNote(kAdpfResetGpu);   // the GPU does a different amount of work per frame
    wakeRender();
}

void VulkanRendererContext::setWfgConfig(int flowSide, int targetFps) {
    wfgFlowSide_.store((uint32_t)std::clamp(flowSide, 64, 1080));
    wfgTargetFps_.store((uint32_t)std::max(0, targetFps));
}

// Under renderMutex, for every window content update that reaches the screen. Counts it as a game
// frame only when the window is the largest of those that changed within the last quarter second;
// outputs is the limiter's batch length for it (0 = unknown).
void VulkanRendererContext::noteContentUpdateLocked(int64_t id, int w, int h, int outputs) {
    if (!wfgRequested_.load(std::memory_order_relaxed)) return;
    const int64_t now = nowNs();
    ContentRate& self = contentRates_[id];
    self.area = (int64_t)std::max(w, 0) * std::max(h, 0);
    self.lastNs = now;

    bool largest = true;
    for (auto it = contentRates_.begin(); it != contentRates_.end();) {
        if (now - it->second.lastNs > kForgetNs) { it = contentRates_.erase(it); continue; }
        if (it->first != id && now - it->second.lastNs <= kRecentNs && it->second.area > self.area)
            largest = false;
        ++it;
    }
    if (!largest) return;
    wfgSlotOutputs_.store(outputs, std::memory_order_relaxed);
    lastGameFrameNs_.store(now, std::memory_order_relaxed);
    gameFrames_.fetch_add(1, std::memory_order_release);
}

// Render thread, after due presents were applied and before the swapchain image is acquired. A
// render that carries no new game frame (cursor motion, a transform change, a cursor image) is
// skipped while the game is drawing: what changed shows with its next frame, and a render of its
// own would add an image to the FIFO queue in front of the batch, which is how a single cursor
// movement used to push every following generated frame a refresh late. renderLoop re-renders
// once the game has been quiet for kGameActiveNs.
bool VulkanRendererContext::wfgShouldSkipRender() {
    if (!wfg_ || !wfgSwapchain_ || !swapchainTransferDst_ || fbResized.load()) return false;
    if (gameFrames_.load(std::memory_order_acquire) != wfgSeenFrames_) return false;
    const int64_t last = lastGameFrameNs_.load(std::memory_order_relaxed);
    const int64_t now = nowNs();
    if (last == 0 || now - last >= kGameActiveNs) return false;
    wfgSkippedRenders_++;
    wfgRetryAtNs_.store(last + kGameActiveNs + 1000000LL, std::memory_order_relaxed);
    return true;
}

// After the presents of a frame that went through WFG: a real acquire that blocked for more than
// half a refresh means the swapchain is full, so more images wait than the model counted. Then
// notePresentedForPacing adds the batch as usual.
void VulkanRendererContext::wfgNotePresented(uint32_t presents, int64_t acquireWaitNs) {
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    if (period > 0) {
        const int64_t now = nowNs();
        if (acquireWaitNs > period / 2 && presentSlotEndNs_ < now + 2 * period)
            presentSlotEndNs_ = now + 2 * period;
    }
    notePresentedForPacing(presents);
}

void VulkanRendererContext::wfgCreateSems() {
    VkSemaphoreCreateInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (auto& slot : wfgGenSems_)
        for (auto& sem : slot)
            if (sem == VK_NULL_HANDLE && vk_.CreateSemaphore(device, &si, nullptr, &sem) != VK_SUCCESS)
                throw std::runtime_error("WFG acquire semaphore");
}

// Only with the device idle: an acquire whose submit failed leaves its semaphore signaled.
void VulkanRendererContext::wfgDestroySems() {
    for (auto& slot : wfgGenSems_)
        for (auto& sem : slot)
            if (sem != VK_NULL_HANDLE) { vk_.DestroySemaphore(device, sem, nullptr); sem = VK_NULL_HANDLE; }
}

// Runs on the render thread with the swapchain current. Creates or drops the engine as requested;
// false when the swapchain has to be rebuilt for the new state first.
bool VulkanRendererContext::wfgSync() {
    const bool want = wfgRequested_.load();
    if (want && !wfg_ && !wfgUnavailable_.load()) {
        if (!vkr_dis_bind_dispatch(gipa, instance)) {
            RLOG_E("WFG: the driver's Vulkan entry points could not be resolved");
            wfgUnavailable_.store(true);
        } else if ((wfg_ = vkr_dis_create(device, physicalDevice)) == nullptr) {
            RLOG_E("WFG: engine creation failed; frame generation stays off");
            wfgUnavailable_.store(true);
        } else {
            wfgSeenFrames_ = gameFrames_.load();
            wfgRealFrames_ = wfgLogReal_ = wfgLogMade_ = 0;
            WLOG_I("WFG engine ready");
        }
    } else if (!want) {
        if (wfg_) {
            vk_.DeviceWaitIdle(device);
            vkr_dis_destroy(wfg_);
            wfg_ = nullptr;
            wfgDestroyComposite();
        }
        wfgUnavailable_.store(false);
        wfgActive_.store(false, std::memory_order_relaxed);
        wfgOutputs_.store(0, std::memory_order_relaxed);
        wfgSlotOutputs_.store(0, std::memory_order_relaxed);
        wfgRetryAtNs_.store(0, std::memory_order_relaxed);
    }
    if (want) wfgCreateSems();
    return want == wfgSwapchain_;
}

bool VulkanRendererContext::wfgEnsureComposite() {
    if (wfgComposite_ != VK_NULL_HANDLE && wfgCompositeExt_.width == swapchainExt.width &&
        wfgCompositeExt_.height == swapchainExt.height)
        return true;
    if (wfgComposite_ != VK_NULL_HANDLE) {
        vk_.DeviceWaitIdle(device);
        wfgDestroyComposite();
    }

    if (wfgPass_ == VK_NULL_HANDLE) {
        // Compatible with renderPass (same attachment), so the window pipelines draw into it; it
        // leaves the composite in GENERAL, where WFG reads it.
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
        // The previous frame's WFG and copy passes read the composite before it is cleared.
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
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
        if (vk_.CreateRenderPass(device, &ci, nullptr, &wfgPass_) != VK_SUCCESS) {
            wfgPass_ = VK_NULL_HANDLE;
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
        if (vk_.CreateImage(device, &ic, nullptr, &wfgComposite_) != VK_SUCCESS)
            throw std::runtime_error("image");
        VkMemoryRequirements req;
        vk_.GetImageMemoryRequirements(device, wfgComposite_, &req);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device, &ai, nullptr, &wfgCompositeMem_) != VK_SUCCESS)
            throw std::runtime_error("memory");
        vk_.BindImageMemory(device, wfgComposite_, wfgCompositeMem_, 0);

        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = wfgComposite_;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = swapchainFmt;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vk_.CreateImageView(device, &vi, nullptr, &wfgCompositeView_) != VK_SUCCESS)
            throw std::runtime_error("view");

        VkFramebufferCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass = wfgPass_;
        fi.attachmentCount = 1;
        fi.pAttachments = &wfgCompositeView_;
        fi.width = swapchainExt.width;
        fi.height = swapchainExt.height;
        fi.layers = 1;
        if (vk_.CreateFramebuffer(device, &fi, nullptr, &wfgCompositeFB_) != VK_SUCCESS)
            throw std::runtime_error("framebuffer");
    } catch (const std::exception& e) {
        RLOG_E("WFG: composite %ux%u unavailable (%s)", swapchainExt.width, swapchainExt.height, e.what());
        wfgDestroyComposite();
        return false;
    }
    wfgCompositeExt_ = swapchainExt;
    WLOG_I("WFG composite %ux%u", swapchainExt.width, swapchainExt.height);
    return true;
}

void VulkanRendererContext::wfgDestroyComposite() {
    if (wfgCompositeFB_ != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, wfgCompositeFB_, nullptr);
    if (wfgCompositeView_ != VK_NULL_HANDLE) vk_.DestroyImageView(device, wfgCompositeView_, nullptr);
    if (wfgComposite_ != VK_NULL_HANDLE) vk_.DestroyImage(device, wfgComposite_, nullptr);
    if (wfgCompositeMem_ != VK_NULL_HANDLE) vk_.FreeMemory(device, wfgCompositeMem_, nullptr);
    wfgCompositeFB_ = VK_NULL_HANDLE;
    wfgCompositeView_ = VK_NULL_HANDLE;
    wfgComposite_ = VK_NULL_HANDLE;
    wfgCompositeMem_ = VK_NULL_HANDLE;
    wfgCompositeExt_ = {0, 0};
}

// With the device idle.
void VulkanRendererContext::wfgDestroy() {
    if (wfg_) { vkr_dis_destroy(wfg_); wfg_ = nullptr; }
    wfgDestroyComposite();
    wfgDestroySems();
    if (wfgPass_ != VK_NULL_HANDLE) { vk_.DestroyRenderPass(device, wfgPass_, nullptr); wfgPass_ = VK_NULL_HANDLE; }
}

// A swapchain image's last frame has finished on the GPU (its fence), within timeoutNs.
bool VulkanRendererContext::waitImageFence(uint32_t imgIdx, uint64_t timeoutNs) {
    if (imgIdx >= imgInFlight.size()) return true;
    VkFence fence = imgInFlight[imgIdx];
    if (fence == VK_NULL_HANDLE) return true;
    if (vk_.GetFenceStatus && vk_.GetFenceStatus(device, fence) == VK_SUCCESS) return true;
    return vk_.WaitForFences(device, 1, &fence, VK_TRUE, timeoutNs) == VK_SUCCESS;
}

// Where the windows land in the swapchain image (identity orientation): WFG analyses only that
// rect and fills the letterbox around it from the composite.
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

// After the real swapchain image is acquired: decides whether the scene goes to the composite,
// plans the generated frames for a new game frame and acquires their swapchain images.
VulkanRendererContext::WfgFrame VulkanRendererContext::wfgBeginFrame(
        const std::vector<DrawEntry>& draws, float ox, float oy, float sx, float sy, float cw, float ch) {
    WfgFrame wf;
    const auto inactive = [&]() {
        wfgActive_.store(false, std::memory_order_relaxed);
        if (wfgOutputs_.exchange(0, std::memory_order_relaxed) != 0) adpfNote(kAdpfResetGpu);
        return wf;
    };
    if (!wfg_ || !wfgSwapchain_ || !swapchainTransferDst_) return inactive();

    const uint32_t images = (uint32_t)swapchainImages.size();
    // Without a spare image beyond the display's own there is nothing to generate into.
    if (images <= swapchainMinImages_ + 1) {
        if (!wfgUnavailable_.exchange(true))
            RLOG_E("WFG: the swapchain has no spare images (%u of min %u); frame generation stays off",
                   images, swapchainMinImages_);
        return inactive();
    }
    if (!wfgEnsureComposite()) return inactive();

    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    const VkrDisContentRect content = wfgContentRect(draws, ox, oy, sx, sy, cw, ch);
    const float refresh = period > 0 ? (float)(1.0e9 / (double)period) : 0.f;
    vkr_dis_configure(wfg_, wfgFlowSide_.load(), wfgTargetFps_.load(), refresh);
    if (vkr_dis_needs_rebuild(wfg_, swapchainExt.width, swapchainExt.height, swapchainFmt, content))
        vk_.DeviceWaitIdle(device);
    if (!vkr_dis_prepare(wfg_, swapchainExt.width, swapchainExt.height, swapchainFmt, content)) {
        if (!wfgUnavailable_.exchange(true))
            RLOG_E("WFG: resources for %ux%u could not be built; frame generation stays off",
                   swapchainExt.width, swapchainExt.height);
        return inactive();
    }

    wf.active = true;
    wfgActive_.store(true, std::memory_order_relaxed);
    const uint64_t frames = gameFrames_.load(std::memory_order_acquire);
    wf.newFrame = frames != wfgSeenFrames_;
    wfgSeenFrames_ = frames;
    if (!wf.newFrame) return wf;

    const uint32_t capacity = std::min(images - swapchainMinImages_ - 1, VKR_DIS_MAX_GENERATIONS);
    uint32_t planned = vkr_dis_plan(wfg_, capacity, frames);

    // The limiter's slots know better than the rate estimate how many refreshes lie between this
    // game frame and the next one (45 on 120 Hz: 3,3,2), but only while the game keeps up with its
    // slots; the engine's own plan still decides whether to generate at all (a game below ~20 fps,
    // a rate not measured yet).
    const int32_t slotOutputs = wfgSlotOutputs_.load(std::memory_order_relaxed);
    if (planned > 0 && slotOutputs > 0)
        planned = (uint32_t)std::clamp<int32_t>(slotOutputs - 1, 0, (int32_t)capacity);

    // Images still waiting for the display beyond the next refresh, in the queue model. More than
    // two means the queue has grown (a game slightly faster than the output cadence, a burst after
    // a stall): drop one generated frame until it drains, since every image presented now reaches
    // the display that much later. One at a time keeps the motion cadence even.
    if (period > 0) {
        const int64_t now = nowNs();
        const uint64_t ahead = presentSlotEndNs_ > now + period
            ? (uint64_t)((presentSlotEndNs_ - now - period) / period) : 0;
        wfgAheadSum_ += ahead;
        if (ahead > 2 && planned > 0) {
            planned--;
            wfgTrimmedFrames_++;
        }
    }

    // Generation starting or stopping changes what a cycle costs: the hint session starts over.
    const uint32_t outputs = planned > 0 ? planned + 1 : 0;
    if ((wfgOutputs_.exchange(outputs, std::memory_order_relaxed) > 0) != (outputs > 0))
        adpfNote(kAdpfResetGpu);

    uint64_t timeout = kAcquireTimeoutNs;
    if (period > 0)
        timeout = std::clamp((uint64_t)period, kAcquireTimeoutNs, kAcquireTimeoutMaxNs);
    for (uint32_t g = 0; g < planned; g++) {
        uint32_t idx = 0;
        const int64_t from = nowNs();
        VkResult r = vk_.AcquireNextImageKHR(device, swapchain, timeout,
                                             wfgGenSems_[currentFrame][g], VK_NULL_HANDLE, &idx);
        wf.acquireWaitNs += nowNs() - from;
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) fbResized.store(true);
            if (wfgAcquireMisses_++ % 120 == 0)
                RLOG_E("WFG: generated frame %u/%u dropped, acquire returned %d (images=%u)",
                       g + 1, planned, (int)r, images);
            break;
        }
        if (idx >= images) { fbResized.store(true); break; }
        // The image came back from the display, so the frame that drew it is long done; the fence
        // is only the proof, and a frame still running means this image cannot be used now.
        if (!waitImageFence(idx, kImageFenceWaitNs)) { fbResized.store(true); break; }
        if (idx < imgInFlight.size()) imgInFlight[idx] = inFlightFences[currentFrame];
        wf.genIdx[wf.genCount++] = idx;
    }
    return wf;
}

// After recordCmdBuf drew the scene into the composite (command buffer still open): runs WFG,
// writes the generated frames, copies the composite into the real image and ends the buffer.
void VulkanRendererContext::wfgRecord(VkCommandBuffer cb, uint32_t imgIdx, WfgFrame& wf) {
    const uint32_t w = swapchainExt.width, h = swapchainExt.height;

    if (wf.newFrame) {
        vkr_dis_process(wfg_, cb, wfgComposite_, w, h, wf.genCount);
        for (uint32_t g = 0; g < wf.genCount; g++) {
            const uint32_t idx = wf.genIdx[g];
            vkr_dis_generate_into(wfg_, cb, g, idx, swapchainImages[idx], VK_NULL_HANDLE, w, h, wfgComposite_);
            transition(cb, swapchainImages[idx], VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                       VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
        if (wf.genCount > 0) {
            wfgRealFrames_++;
            const uint64_t made = wfgMadeFrames_.fetch_add(wf.genCount) + wf.genCount;
            if (wfgRealFrames_ % 300 == 0) {
                const uint64_t dReal = wfgRealFrames_ - wfgLogReal_, dMade = made - wfgLogMade_;
                wfgLogReal_ = wfgRealFrames_;
                wfgLogMade_ = made;
                WLOG_I("WFG delivered real=%llu made=%llu ratio=%.2f misses=%llu skipped=%llu "
                       "trimmed=%llu ahead=%.2f",
                       (unsigned long long)wfgRealFrames_, (unsigned long long)made,
                       dReal ? (double)dMade / (double)dReal : 0.0,
                       (unsigned long long)wfgAcquireMisses_, (unsigned long long)wfgSkippedRenders_,
                       (unsigned long long)wfgTrimmedFrames_,
                       dReal ? (double)wfgAheadSum_ / (double)dReal : 0.0);
                wfgAheadSum_ = 0;
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
    vk_.CmdCopyImage(cb, wfgComposite_, VK_IMAGE_LAYOUT_GENERAL,
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
