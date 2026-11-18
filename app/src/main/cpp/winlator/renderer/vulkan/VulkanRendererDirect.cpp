// The renderer's side of the Direct output (DirectServer): the candidate the window tree yields,
// the frames that go to SurfaceFlinger instead of the composite, and the Fx pass of Direct + FX.
#include "VulkanRendererContext.h"

#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace {

constexpr uint32_t kRingSlots = 3;

int32_t roundPx(float v) { return (int32_t)std::lround(v); }

}  // namespace

// ---------------------------------------------------------------- settings / status

void VulkanRendererContext::setDirectOutput(int mode, const char* socketPath) {
    if (!direct_) return;
    direct_->setMode(mode);
    direct_->listen(socketPath ? socketPath : "");
}

DirectServer::Status VulkanRendererContext::directStatus() const {
    return direct_ ? direct_->status() : DirectServer::Status{};
}

// ---------------------------------------------------------------- what DirectServer gives back

void VulkanRendererContext::directBufferReleased(AHardwareBuffer* ahb) {
    std::lock_guard<std::mutex> lk(renderMutex);
    // No longer on a layer: back to xcore once no window shows it and no frame reads it.
    noteDisplacedLocked(ahb);
}

void VulkanRendererContext::directRingReleased(uint32_t gen, uint32_t slot, int fenceFd) {
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        if (gen == ringGen && slot < ring.size()) {
            RingSlot& r = ring[slot];
            if (r.releaseFd >= 0) close(r.releaseFd);
            r.releaseFd = fenceFd;   // the next Fx pass into it waits for it on the GPU
            r.inSf = false;
            fenceFd = -1;
        }
    }
    if (fenceFd >= 0) close(fenceFd);
    requestRender();   // a pass may have waited for a free buffer
}

void VulkanRendererContext::directClientGone(const std::vector<AHardwareBuffer*>& images) {
    std::lock_guard<std::mutex> lk(renderMutex);
    for (AHardwareBuffer* ahb : images) {
        auto it = directImports.find(ahb);
        if (it == directImports.end()) continue;
        deleteQueue.push_back(it->second);   // freed once the frames that sampled it are done
        directImports.erase(it);
        hasRetired.store(true);
    }
}

// ---------------------------------------------------------------- the candidate

// The admission rule, from the composite's own view of the screen: the top-most window with
// content that covers the X screen (1 px of slack at the edges, as GameScope's), with nothing
// drawn over it bigger than a pixel. Always ignores those overlapping windows and prefers the
// full-screen window that has a WL swapchain; without one it falls back to the top-most content.
DirectServer::Candidate VulkanRendererContext::directCandidateLocked() {
    DirectServer::Candidate c;
    const int64_t watch = direct_->watchWindow();
    if (watch) {
        auto it = contentInfo.find(watch);
        c.watchGen = it != contentInfo.end() ? it->second.gen : 0;
    }
    if (surfaceDetached.load(std::memory_order_acquire) || !window) { c.reason = WL_REASON_DETACHED; return c; }
    if (hasCustomScissor) { c.reason = WL_REASON_SCISSOR; return c; }

    struct Shown { int64_t id; int x, y, w, h; const WinTex* t; };
    std::vector<Shown> shown;
    for (const auto& re : renderList) {
        auto it = texMap.find(re.id);
        if (it == texMap.end() || it->second.ds == VK_NULL_HANDLE) continue;
        shown.push_back({re.id, re.x, re.y, it->second.w, it->second.h, &it->second});
    }
    if (shown.empty()) { c.reason = WL_REASON_NO_WINDOW; return c; }

    const bool always = direct_->mode() == WL_OUTPUT_ALWAYS;
    const int W = treeScreenW, H = treeScreenH;
    auto covers = [&](const Shown& s) {
        return s.x <= 1 && s.y <= 1 && s.x + s.w >= W - 1 && s.y + s.h >= H - 1;
    };
    int cand = -1;
    // Always ignores windows over the game; it must not let an overlay or helper window above
    // the game become the Direct candidate and leave the game's swapchain at NOT_TOP.
    if (always) {
        for (int i = (int)shown.size() - 1; i >= 0; i--) {
            if (covers(shown[i]) && direct_->hasClient(shown[i].id)) { cand = i; break; }
        }
        if (cand < 0) {
            for (int i = (int)shown.size() - 1; i >= 0; i--) {
                if (direct_->hasClient(shown[i].id)) { cand = i; break; }
            }
        }
    }
    if (cand < 0) {
        for (int i = (int)shown.size() - 1; i >= 0; i--) {
            if (covers(shown[i])) { cand = i; break; }
        }
    }
    if (cand < 0 && always) cand = (int)shown.size() - 1;
    if (cand < 0) { c.reason = WL_REASON_WINDOWED; return c; }
    const Shown& g = shown[cand];
    if (!always) {
        for (size_t j = (size_t)cand + 1; j < shown.size(); j++) {
            const Shown& o = shown[j];
            const int ix = std::min(g.x + g.w, o.x + o.w) - std::max(g.x, o.x);
            const int iy = std::min(g.y + g.h, o.y + o.h) - std::max(g.y, o.y);
            if (ix > 1 && iy > 1) { c.reason = WL_REASON_OVERLAP; return c; }
        }
    }

    // Where the composite would draw it, in the surface's own (logical) pixels: SurfaceFlinger
    // turns the layers with the display, so no pre-rotation here.
    const float cw = (float)containerWidth, ch = (float)containerHeight;
    const float sw = (float)surfaceWidth, sh = (float)surfaceHeight;
    float x0 = (sceneOffsetX + (float)g.x * sceneScaleX) / cw * sw;
    float x1 = (sceneOffsetX + (float)(g.x + g.w) * sceneScaleX) / cw * sw;
    const float y0 = (sceneOffsetY + (float)g.y * sceneScaleY) / ch * sh;
    const float y1 = (sceneOffsetY + (float)(g.y + g.h) * sceneScaleY) / ch * sh;
    if (fx.stretchMode == 1) { x0 = 0.f; x1 = sw; }   // stretch fills the width (Fx does it)
    c.dst.x = roundPx(x0);
    c.dst.y = roundPx(y0);
    c.dst.w = roundPx(x1) - c.dst.x;
    c.dst.h = roundPx(y1) - c.dst.y;
    if (c.dst.w <= 0 || c.dst.h <= 0) { c.reason = WL_REASON_WINDOWED; return c; }

    c.window = g.id;
    c.reason = WL_REASON_OK;
    c.needsFx = fx.needed();
    c.fxPossible = externalSemaphoreFd_ && vk_.GetAndroidHardwareBufferPropertiesANDROID;
    c.xBuffer = g.t->ahb;
    c.xW = g.t->w;
    c.xH = g.t->h;
    auto ci = contentInfo.find(g.id);
    // A GPU client's buffers (allocated as render targets, not the server's own CPU pixels),
    // presented in turn.
    const bool gpu = (g.t->usage & AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER) != 0;
    c.xSwapped = gpu && ci != contentInfo.end() && ci->second.swaps > 0 && ci->second.repeats == 0;
    return c;
}

// ---------------------------------------------------------------- a frame without the composite

void VulkanRendererContext::renderDirect(const DirectServer::Plan& plan, const DirectServer::Candidate& cand) {
    if (plan.route == DirectServer::Route::DirectX && !plan.fx && cand.xBuffer)
        direct_->pushX(cand.xBuffer, cand.xW, cand.xH);
    if (plan.fx) runDirectFx(plan, cand);
    else fxLastInput = nullptr;

    // The cursor is a layer of its own, never touched by the effects.
    bool visible;
    DirectServer::Rect r;
    std::vector<uint32_t> pixels;
    int32_t w, h;
    uint64_t serial;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        visible = cursorVisible.load();
        w = cursorPendW;
        h = cursorPendH;
        serial = cursorImageSerial;
        if (serial != directCursorSent && w > 0 && h > 0 && cursorPixels.size() >= (size_t)w * h)
            pixels.assign(cursorPixels.begin(), cursorPixels.begin() + (size_t)w * h);
        const float cw = (float)containerWidth, ch = (float)containerHeight;
        const float sw = (float)surfaceWidth, sh = (float)surfaceHeight;
        const float cx = (float)(pointerX.load() - cursorPendHotX);
        const float cy = (float)(pointerY.load() - cursorPendHotY);
        r.x = roundPx((sceneOffsetX + cx * sceneScaleX) / cw * sw);
        r.y = roundPx((sceneOffsetY + cy * sceneScaleY) / ch * sh);
        r.w = std::max(1, roundPx((sceneOffsetX + (cx + w) * sceneScaleX) / cw * sw) - r.x);
        r.h = std::max(1, roundPx((sceneOffsetY + (cy + h) * sceneScaleY) / ch * sh) - r.y);
    }
    if (!pixels.empty()) directCursorSent = serial;
    direct_->updateCursor(visible && w > 0 && h > 0, r, pixels.empty() ? nullptr : pixels.data(), w, h, serial);
}

// ---------------------------------------------------------------- Direct + FX

bool VulkanRendererContext::ensureDirectSync() {
    if (directPass == VK_NULL_HANDLE) {
        // Compatible with renderPass (same format, one sample): Fx's pipelines draw into it.
        VkAttachmentDescription att{};
        att.format = VK_FORMAT_R8G8B8A8_UNORM;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        ci.attachmentCount = 1; ci.pAttachments = &att;
        ci.subpassCount = 1; ci.pSubpasses = &sub;
        ci.dependencyCount = 1; ci.pDependencies = &dep;
        if (vk_.CreateRenderPass(device, &ci, nullptr, &directPass) != VK_SUCCESS) {
            directPass = VK_NULL_HANDLE;
            RLOG_E("Direct + FX: render pass creation failed");
            return false;
        }
    }
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (directExportSems[i] == VK_NULL_HANDLE) {
            VkExportSemaphoreCreateInfo ex{};
            ex.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
            ex.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            VkSemaphoreCreateInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            si.pNext = &ex;
            if (vk_.CreateSemaphore(device, &si, nullptr, &directExportSems[i]) != VK_SUCCESS) {
                directExportSems[i] = VK_NULL_HANDLE;
                RLOG_E("Direct + FX: exportable semaphore creation failed");
                return false;
            }
        }
        for (auto& sem : directWaitSems[i]) {
            if (sem != VK_NULL_HANDLE) continue;
            VkSemaphoreCreateInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            if (vk_.CreateSemaphore(device, &si, nullptr, &sem) != VK_SUCCESS) { sem = VK_NULL_HANDLE; return false; }
        }
    }
    return true;
}

bool VulkanRendererContext::importAHBAsTarget(WinTex& wt, AHardwareBuffer* ahb) {
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &fmtP;
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID ||
        vk_.GetAndroidHardwareBufferPropertiesANDROID(device, ahb, &props) != VK_SUCCESS)
        return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext = &emi;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = VK_FORMAT_R8G8B8A8_UNORM;
    ii.extent = {desc.width, desc.height, 1};
    ii.mipLevels = 1; ii.arrayLayers = 1; ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ii, nullptr, &wt.img) != VK_SUCCESS) { wt.img = VK_NULL_HANDLE; return false; }

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer = ahb;
    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext = &imp;
    ded.image = wt.img;
    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &ded;
    mai.allocationSize = props.allocationSize;
    try { mai.memoryTypeIndex = findMemType(props.memoryTypeBits, 0); }
    catch (...) { freeWinTexVk(wt); wt = {}; return false; }
    if (vk_.AllocateMemory(device, &mai, nullptr, &wt.mem) != VK_SUCCESS) { wt.mem = VK_NULL_HANDLE; freeWinTexVk(wt); wt = {}; return false; }
    vk_.BindImageMemory(device, wt.img, wt.mem, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = wt.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = VK_FORMAT_R8G8B8A8_UNORM;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vk_.CreateImageView(device, &vi, nullptr, &wt.view) != VK_SUCCESS) { wt.view = VK_NULL_HANDLE; freeWinTexVk(wt); wt = {}; return false; }

    VkFramebufferCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fi.renderPass = directPass;
    fi.attachmentCount = 1;
    fi.pAttachments = &wt.view;
    fi.width = desc.width;
    fi.height = desc.height;
    fi.layers = 1;
    if (vk_.CreateFramebuffer(device, &fi, nullptr, &wt.fb) != VK_SUCCESS) { wt.fb = VK_NULL_HANDLE; freeWinTexVk(wt); wt = {}; return false; }
    wt.w = (int)desc.width;
    wt.h = (int)desc.height;
    wt.ahb = ahb;
    wt.usage = desc.usage;
    return true;
}

void VulkanRendererContext::retireRingLocked() {
    for (auto& r : ring) {
        // SurfaceFlinger keeps its own reference to a buffer it still shows; the Vulkan objects
        // go once the frames that drew into them are done.
        if (r.tex.img != VK_NULL_HANDLE || r.tex.fb != VK_NULL_HANDLE) { deleteQueue.push_back(r.tex); hasRetired.store(true); }
        if (r.ahb) AHardwareBuffer_release(r.ahb);
        if (r.releaseFd >= 0) close(r.releaseFd);
    }
    ring.clear();
    ringW = ringH = 0;
}

bool VulkanRendererContext::ensureRingLocked(int32_t w, int32_t h, bool ubwc) {
    if (!ring.empty() && ringW == w && ringH == h && ringUbwc == ubwc) return true;
    retireRingLocked();
    ringGen++;   // releases of the old ring are not this one's
    for (uint32_t i = 0; i < kRingSlots; i++) {
        RingSlot r;
        AHardwareBuffer_Desc desc{};
        desc.width = (uint32_t)w;
        desc.height = (uint32_t)h;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        // UBWC as the game's own buffers have it (the wrapper decides: Qualcomm's driver, RGBA).
        if (ubwc) desc.usage |= AHARDWAREBUFFER_USAGE_VENDOR_0;
        if (AHardwareBuffer_allocate(&desc, &r.ahb) != 0 || !r.ahb) {
            RLOG_E("Direct + FX: cannot allocate a %dx%d buffer", w, h);
            retireRingLocked();
            return false;
        }
        if (!importAHBAsTarget(r.tex, r.ahb)) {
            RLOG_E("Direct + FX: cannot import a %dx%d buffer", w, h);
            AHardwareBuffer_release(r.ahb);
            retireRingLocked();
            return false;
        }
        ring.push_back(r);
    }
    ringW = w;
    ringH = h;
    ringUbwc = ubwc;
    RLOG("Direct + FX: ring of %u buffers %dx%d%s", kRingSlots, w, h, ubwc ? " (UBWC)" : "");
    return true;
}

void VulkanRendererContext::runDirectFx(const DirectServer::Plan& plan, const DirectServer::Candidate& cand) {
    const bool wl = plan.route == DirectServer::Route::DirectWl;
    DirectServer::WlFrame wf;
    if (wl && !direct_->takeWlFrame(wf)) return;
    // A WL frame that is not drawn goes back once the client's own rendering into it is done.
    auto giveBackWl = [&](int fd) { if (wl) direct_->releaseWlFrame(wf.client, wf.image, fd); };
    if (!externalSemaphoreFd_ || !ensureDirectSync()) { giveBackWl(wf.acquireFd); return; }

    const DirectServer::Rect dst = cand.dst;
    if (dst.w <= 0 || dst.h <= 0) { giveBackWl(wf.acquireFd); return; }

    WinTex input{};
    uint64_t inputGen = 0;
    uint32_t slotIdx = 0;
    int ringFence = -1;
    AHardwareBuffer* ringAhb = nullptr;
    VkFramebuffer ringFb = VK_NULL_HANDLE;
    VkImage ringImg = VK_NULL_HANDLE;
    uint32_t gen = 0;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        retireDeleteQueueLocked();
        reapRetiredLocked();
        if (!wl) {
            auto it = texMap.find(cand.window);
            if (it == texMap.end() || it->second.ds == VK_NULL_HANDLE) return;
            input = it->second;
            auto ci = contentInfo.find(cand.window);
            inputGen = ci != contentInfo.end() ? ci->second.gen : 0;
            // Nothing new to draw (the frame was woken by the cursor, say).
            if (input.ahb == fxLastInput && inputGen == fxLastGen && fxSerial.load() == fxLastSerial && dst == fxLastDst)
                return;
        } else {
            auto it = directImports.find(wf.ahb);
            if (it == directImports.end()) {
                WinTex t{};
                if (!importAHBToWinTex(t, wf.ahb)) {
                    RLOG_E("Direct + FX: cannot import a client image");
                    giveBackWl(wf.acquireFd);
                    return;
                }
                t.ahb = wf.ahb;
                it = directImports.emplace(wf.ahb, t).first;
            }
            input = it->second;
        }
        if (!ensureRingLocked(dst.w, dst.h, (input.usage & AHARDWAREBUFFER_USAGE_VENDOR_0) != 0)) {
            giveBackWl(wf.acquireFd);
            return;
        }
        int free = -1;
        for (uint32_t i = 0; i < ring.size(); i++) if (!ring[i].inSf) { free = (int)i; break; }
        if (free < 0) {   // SurfaceFlinger has them all: the next release brings this round again
            giveBackWl(wf.acquireFd);
            return;
        }
        slotIdx = (uint32_t)free;
        RingSlot& r = ring[slotIdx];
        r.inSf = true;
        ringFence = r.releaseFd;
        r.releaseFd = -1;
        ringAhb = r.ahb;
        ringFb = r.tex.fb;
        ringImg = r.tex.img;
        gen = ringGen;
        // In use by this pass until it completes (the frame's serial, see renderFrame).
        if (!wl) {
            auto cit = ahbImportCache.find(input.ahb);
            if (cit != ahbImportCache.end()) cit->second.lastUse = submitSerial + 1;
        }
    }

    // The frame slot's previous use must be done (its command buffer is reused).
    if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[currentFrame]) == VK_NOT_READY)
        vk_.WaitForFences(device, 1, &inFlightFences[currentFrame], VK_TRUE, UINT64_MAX);

    VkCommandBuffer cb = cmdBufs[currentFrame];
    vk_.ResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    bool ok = vk_.BeginCommandBuffer(cb, &bi) == VK_SUCCESS;
    if (ok) {
        // The game's buffer, as the composite takes it (recordCmdBuf): from the foreign queue.
        VkImageMemoryBarrier in{};
        in.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        in.oldLayout = input.needsTransition ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        in.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        in.srcQueueFamilyIndex = foreignQueueFamily_ ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
        in.dstQueueFamilyIndex = foreignQueueFamily_ ? graphicsQueueFamilyIndex : VK_QUEUE_FAMILY_IGNORED;
        in.image = input.img;
        in.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        in.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                               0, 0, nullptr, 0, nullptr, 1, &in);

        fx.recordStandalone(cb, VulkanRendererFx::Input{input.ds, input.w, input.h}, directPass, ringFb,
                            VkExtent2D{(uint32_t)dst.w, (uint32_t)dst.h});

        // Both buffers go back to whoever reads them next: the game's to its client, the ring's
        // to SurfaceFlinger / HWC.
        VkImageMemoryBarrier out[2]{};
        uint32_t n = 0;
        if (foreignQueueFamily_) {
            out[n] = in;
            out[n].oldLayout = out[n].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            out[n].srcQueueFamilyIndex = graphicsQueueFamilyIndex;
            out[n].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            out[n].srcAccessMask = 0;
            out[n].dstAccessMask = 0;
            n++;
        }
        out[n].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        out[n].oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        out[n].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        out[n].srcQueueFamilyIndex = foreignQueueFamily_ ? graphicsQueueFamilyIndex : VK_QUEUE_FAMILY_IGNORED;
        out[n].dstQueueFamilyIndex = foreignQueueFamily_ ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
        out[n].image = ringImg;
        out[n].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        out[n].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        out[n].dstAccessMask = 0;
        n++;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                               VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, n, out);
        ok = vk_.EndCommandBuffer(cb) == VK_SUCCESS;
    }

    // Waits: the client's rendering into its image, SurfaceFlinger's reading of the ring buffer.
    VkSemaphore waits[2];
    VkPipelineStageFlags stages[2];
    uint32_t nWaits = 0;
    auto importWait = [&](int fd, VkSemaphore sem, VkPipelineStageFlags stage) {
        if (fd < 0) return;
        VkImportSemaphoreFdInfoKHR imp{};
        imp.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
        imp.semaphore = sem;
        imp.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
        imp.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        imp.fd = fd;
        if (vk_.ImportSemaphoreFdKHR(device, &imp) == VK_SUCCESS) {   // the fd is the driver's now
            waits[nWaits] = sem;
            stages[nWaits] = stage;
            nWaits++;
        } else {
            struct pollfd p{fd, POLLIN, 0};   // rare: wait for it here
            poll(&p, 1, 100);
            close(fd);
        }
    };
    if (ok) {
        importWait(wl ? wf.acquireFd : -1, directWaitSems[currentFrame][0], VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        importWait(ringFence, directWaitSems[currentFrame][1], VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    } else {
        if (wl && wf.acquireFd >= 0) close(wf.acquireFd);
        if (ringFence >= 0) close(ringFence);
    }
    wf.acquireFd = -1;
    ringFence = -1;

    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = nWaits;
    si.pWaitSemaphores = waits;
    si.pWaitDstStageMask = stages;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &directExportSems[currentFrame];
    if (ok) {
        vk_.ResetFences(device, 1, &inFlightFences[currentFrame]);
        ok = vk_.QueueSubmit(graphicsQueue, 1, &si, inFlightFences[currentFrame]) == VK_SUCCESS;
        if (!ok) {
            vk_.DestroyFence(device, inFlightFences[currentFrame], nullptr);
            VkFenceCreateInfo fi{}; fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            vk_.CreateFence(device, &fi, nullptr, &inFlightFences[currentFrame]);
        }
    }
    if (!ok) {
        RLOG_E("Direct + FX: the pass failed");
        {
            std::lock_guard<std::mutex> lk(renderMutex);
            if (gen == ringGen && slotIdx < ring.size()) ring[slotIdx].inSf = false;
        }
        giveBackWl(-1);
        return;
    }
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        slotSerial[currentFrame] = ++submitSerial;
        if (input.needsTransition) {
            for (auto& [id, wt] : texMap) if (wt.img == input.img) wt.needsTransition = false;
            for (auto& [ahb, wt] : ahbImportCache) if (wt.img == input.img) wt.needsTransition = false;
            for (auto& [ahb, wt] : directImports) if (wt.img == input.img) wt.needsTransition = false;
        }
    }

    // The sync file of the pass: the ring buffer's acquire fence for SurfaceFlinger, and the
    // release fence of the client's image.
    int fd = -1;
    VkSemaphoreGetFdInfoKHR gi{};
    gi.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    gi.semaphore = directExportSems[currentFrame];
    gi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    if (vk_.GetSemaphoreFdKHR(device, &gi, &fd) != VK_SUCCESS) {
        fd = -1;
        vk_.WaitForFences(device, 1, &inFlightFences[currentFrame], VK_TRUE, UINT64_MAX);
    }
    if (wl) giveBackWl(fd >= 0 ? dup(fd) : -1);
    else {
        fxLastInput = input.ahb;
        fxLastGen = inputGen;
        fxLastSerial = fxSerial.load();
        fxLastDst = dst;
    }
    direct_->pushFx(gen, slotIdx, ringAhb, dst.w, dst.h, fd);
    currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
}

void VulkanRendererContext::destroyDirect() {
    for (auto& r : ring) {
        freeWinTexVk(r.tex);
        if (r.ahb) AHardwareBuffer_release(r.ahb);
        if (r.releaseFd >= 0) close(r.releaseFd);
    }
    ring.clear();
    for (auto& [ahb, wt] : directImports) freeWinTexVk(wt);
    directImports.clear();
    for (auto& sem : directExportSems) if (sem != VK_NULL_HANDLE) { vk_.DestroySemaphore(device, sem, nullptr); sem = VK_NULL_HANDLE; }
    for (auto& slot : directWaitSems)
        for (auto& sem : slot) if (sem != VK_NULL_HANDLE) { vk_.DestroySemaphore(device, sem, nullptr); sem = VK_NULL_HANDLE; }
    if (directPass != VK_NULL_HANDLE) { vk_.DestroyRenderPass(device, directPass, nullptr); directPass = VK_NULL_HANDLE; }
}
