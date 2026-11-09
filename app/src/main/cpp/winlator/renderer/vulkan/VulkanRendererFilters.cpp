#include "VulkanRendererContext.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "window_vert.h"
#include "window_frag.h"
#include "window_sgsr_frag.h"
#include "window_fsr1_frag.h"
#include "window_lanczos_frag.h"
#include "window_sgsr_hq_frag.h"
#include "window_nis_frag.h"
#include "window_stretch_frag.h"
#include "window_postfx_frag.h"

// Pipelines and the pipeline cache. Every window pipeline is the shared vertex shader plus one
// fragment shader; buildPipeline() is the only place that fills in the fixed-function state.

namespace {

struct PipelineSource {
    const uint32_t* code;
    size_t          size;
    const char*     name;
};

// Indexed by VulkanRendererContext::PipelineKind.
const PipelineSource kPipelineSources[] = {
    { window_sgsr_frag_code,    sizeof(window_sgsr_frag_code),    "SGSR"    },
    { window_fsr1_frag_code,    sizeof(window_fsr1_frag_code),    "FSR"     },
    { window_lanczos_frag_code, sizeof(window_lanczos_frag_code), "Lanczos" },
    { window_sgsr_hq_frag_code, sizeof(window_sgsr_hq_frag_code), "SGSR HQ" },
    { window_nis_frag_code,     sizeof(window_nis_frag_code),     "NIS"     },
    { window_stretch_frag_code, sizeof(window_stretch_frag_code), "stretch" },
    { window_postfx_frag_code,  sizeof(window_postfx_frag_code),  "postfx"  },
};
static_assert(sizeof(kPipelineSources) / sizeof(kPipelineSources[0]) == 7,
              "one source per VulkanRendererContext::PipelineKind");

// A cache this large was not written by us (or is corrupt); the driver never sees it.
constexpr size_t kMaxCacheFileBytes = 32u * 1024u * 1024u;

// The header the Vulkan spec puts at the start of every pipeline cache blob.
bool cacheHeaderMatches(const std::vector<uint8_t>& data, const VkPhysicalDeviceProperties& props) {
    if (data.size() < 16 + VK_UUID_SIZE) return false;
    uint32_t headerSize, headerVersion, vendorId, deviceId;
    memcpy(&headerSize,    data.data() + 0,  4);
    memcpy(&headerVersion, data.data() + 4,  4);
    memcpy(&vendorId,      data.data() + 8,  4);
    memcpy(&deviceId,      data.data() + 12, 4);
    return headerSize >= 16 + VK_UUID_SIZE &&
           headerSize <= data.size() &&
           headerVersion == VK_PIPELINE_CACHE_HEADER_VERSION_ONE &&
           vendorId == props.vendorID && deviceId == props.deviceID &&
           memcmp(data.data() + 16, props.pipelineCacheUUID, VK_UUID_SIZE) == 0;
}

std::vector<uint8_t> readFile(const std::string& path) {
    std::vector<uint8_t> out;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return out;
    if (fseek(f, 0, SEEK_END) == 0) {
        const long len = ftell(f);
        if (len > 0 && (size_t)len <= kMaxCacheFileBytes && fseek(f, 0, SEEK_SET) == 0) {
            out.resize((size_t)len);
            if (fread(out.data(), 1, out.size(), f) != out.size()) out.clear();
        }
    }
    fclose(f);
    return out;
}

}  // namespace

void VulkanRendererContext::createPipelineCache() {
    // Never fatal: without a cache the pipelines are just compiled from scratch every run.
    if (!vk_.CreatePipelineCache || !vk_.GetPipelineCacheData || !vk_.DestroyPipelineCache) return;

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);

    std::vector<uint8_t> data;
    if (!cacheDirPath.empty()) {
        // A cache is only valid for the device and driver build that wrote it: both are in the name.
        char name[96];
        snprintf(name, sizeof(name), "winlite_vkpc_%08x_%08x_%02x%02x%02x%02x.bin",
                 props.vendorID, props.deviceID,
                 props.pipelineCacheUUID[0], props.pipelineCacheUUID[1],
                 props.pipelineCacheUUID[2], props.pipelineCacheUUID[3]);
        pipelineCachePath = cacheDirPath + "/" + name;
        data = readFile(pipelineCachePath);
        if (!data.empty() && !cacheHeaderMatches(data, props)) {
            RLOG("pipeline cache %s does not match this driver: starting empty", pipelineCachePath.c_str());
            data.clear();
        }
    }

    VkPipelineCacheCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    ci.initialDataSize = data.size();
    ci.pInitialData = data.empty() ? nullptr : data.data();
    VkResult r = vk_.CreatePipelineCache(device, &ci, nullptr, &pipelineCache);
    if (r != VK_SUCCESS && !data.empty()) {
        RLOG_E("pipeline cache rejected by the driver (%d): starting empty", (int)r);
        data.clear();
        ci.initialDataSize = 0;
        ci.pInitialData = nullptr;
        r = vk_.CreatePipelineCache(device, &ci, nullptr, &pipelineCache);
    }
    if (r != VK_SUCCESS) {
        RLOG_E("vkCreatePipelineCache failed (%d): building pipelines without a cache", (int)r);
        pipelineCache = VK_NULL_HANDLE;
        return;
    }
    pipelineCacheSavedSize = data.size();
    RLOG("pipeline cache: %zu bytes loaded from %s", data.size(),
         pipelineCachePath.empty() ? "(not persisted)" : pipelineCachePath.c_str());
}

void VulkanRendererContext::savePipelineCache() {
    if (pipelineCache == VK_NULL_HANDLE || pipelineCachePath.empty() || !vk_.GetPipelineCacheData) return;
    std::lock_guard<std::mutex> lk(pipelineBuildMutex);

    size_t size = 0;
    if (vk_.GetPipelineCacheData(device, pipelineCache, &size, nullptr) != VK_SUCCESS) return;
    if (size <= 16 + VK_UUID_SIZE || size == pipelineCacheSavedSize) return;   // empty, or nothing new
    std::vector<uint8_t> data(size);
    const VkResult r = vk_.GetPipelineCacheData(device, pipelineCache, &size, data.data());
    if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || size == 0 || size > data.size()) return;
    data.resize(size);

    // Written next to the target and renamed over it: a crash or a full disk mid-write leaves the
    // previous cache intact instead of a truncated one.
    const std::string tmp = pipelineCachePath + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) { RLOG_E("pipeline cache: cannot write %s", tmp.c_str()); return; }
    const bool written = fwrite(data.data(), 1, data.size(), f) == data.size();
    const bool closed = fclose(f) == 0;
    if (!written || !closed || rename(tmp.c_str(), pipelineCachePath.c_str()) != 0) {
        RLOG_E("pipeline cache: saving %s failed", pipelineCachePath.c_str());
        remove(tmp.c_str());
        return;
    }
    pipelineCacheSavedSize = data.size();
    RLOG("pipeline cache saved: %zu bytes", data.size());
}

void VulkanRendererContext::createPipelineLayout() {
    VkPushConstantRange pc{};
    pc.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pc.size = sizeof(WindowPushConstantsSGSR);
    VkPipelineLayoutCreateInfo li{};
    li.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    li.setLayoutCount = 1;
    li.pSetLayouts = &dsLayout;
    li.pushConstantRangeCount = 1;
    li.pPushConstantRanges = &pc;
    if (vk_.CreatePipelineLayout(device, &li, nullptr, &pipeLayout) != VK_SUCCESS)
        throw std::runtime_error("pipelayout");
}

VkPipeline VulkanRendererContext::buildPipeline(const uint32_t* fragCode, size_t fragSize,
                                                bool blend, const char* name) {
    VkShaderModule vert = makeShader(window_vert_code, sizeof(window_vert_code));
    VkShaderModule frag = VK_NULL_HANDLE;
    try {
        frag = makeShader(fragCode, fragSize);
    } catch (...) {
        vk_.DestroyShaderModule(device, vert, nullptr);
        throw;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo ia{};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn{};
    dyn.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dyn.dynamicStateCount = 2;
    dyn.pDynamicStates = dynStates;

    VkPipelineViewportStateCreateInfo vp{};
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewportCount = 1;
    vp.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rast{};
    rast.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rast.polygonMode = VK_POLYGON_MODE_FILL;
    rast.lineWidth = 1.0f;
    rast.cullMode = VK_CULL_MODE_NONE;
    rast.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo ms{};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState ba{};
    ba.colorWriteMask = 0xF;
    ba.blendEnable = blend ? VK_TRUE : VK_FALSE;
    if (blend) {
        ba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        ba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        ba.colorBlendOp = VK_BLEND_OP_ADD;
        ba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
        ba.alphaBlendOp = VK_BLEND_OP_ADD;
    }

    VkPipelineColorBlendStateCreateInfo cb{};
    cb.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    cb.attachmentCount = 1;
    cb.pAttachments = &ba;

    VkGraphicsPipelineCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount = 2;
    pi.pStages = stages;
    pi.pVertexInputState = &vi;
    pi.pInputAssemblyState = &ia;
    pi.pViewportState = &vp;
    pi.pRasterizationState = &rast;
    pi.pMultisampleState = &ms;
    pi.pColorBlendState = &cb;
    pi.pDynamicState = &dyn;
    pi.layout = pipeLayout;
    pi.renderPass = renderPass;
    pi.subpass = 0;

    VkPipeline out = VK_NULL_HANDLE;
    VkResult result;
    {
        // The cache is shared with savePipelineCache(), which reads it back.
        std::lock_guard<std::mutex> lk(pipelineBuildMutex);
        result = vk_.CreateGraphicsPipelines(device, pipelineCache, 1, &pi, nullptr, &out);
    }
    vk_.DestroyShaderModule(device, frag, nullptr);
    vk_.DestroyShaderModule(device, vert, nullptr);
    if (result != VK_SUCCESS) throw std::runtime_error(name);
    return out;
}

VkPipeline& VulkanRendererContext::pipelineSlot(PipelineKind kind) {
    switch (kind) {
    case PipelineKind::Sgsr:    return sgsrPipeline;
    case PipelineKind::Fsr1:    return fsr1Pipeline;
    case PipelineKind::Lanczos: return lanczosPipeline;
    case PipelineKind::SgsrHq:  return sgsrHqPipeline;
    case PipelineKind::Nis:     return nisPipeline;
    case PipelineKind::Stretch: return stretchPipeline;
    case PipelineKind::PostFx:  return postfxPipeline;
    }
    return postfxPipeline;   // not reached
}

VkPipeline VulkanRendererContext::prebuildPipeline(PipelineKind kind) {
    if (pipelineSlot(kind) != VK_NULL_HANDLE) return VK_NULL_HANDLE;
    const PipelineSource& src = kPipelineSources[(size_t)kind];
    // The effect setters are called straight from JNI: an exception escaping there aborts the
    // process. A failed build leaves the pipeline unset, and recordCmdBuf falls back to the plain
    // blit; the next switch to that mode tries again.
    try {
        VkPipeline p = buildPipeline(src.code, src.size, false, src.name);
        RLOG("%s pipeline built", src.name);
        return p;
    } catch (const std::exception& e) {
        RLOG_E("%s pipeline build failed (%s): drawing with the plain blit", src.name, e.what());
        return VK_NULL_HANDLE;
    }
}

void VulkanRendererContext::adoptPipeline(PipelineKind kind, VkPipeline built) {
    if (built == VK_NULL_HANDLE) return;
    VkPipeline& slot = pipelineSlot(kind);
    if (slot == VK_NULL_HANDLE) slot = built;
    else vk_.DestroyPipeline(device, built, nullptr);   // lost a race with another builder
}

void VulkanRendererContext::createBasePipelines() {
    // The window pipeline blends: the cursor needs it. Guest buffers are drawn through the same
    // shader without blending (opaque), so a window's alpha channel never shows through.
    pipeline = buildPipeline(window_frag_code, sizeof(window_frag_code), true, "window");
    try {
        opaquePipeline = buildPipeline(window_frag_code, sizeof(window_frag_code), false, "opaque window");
        RLOG("opaque window pipeline built (blend off; cursor alpha isolated)");
    } catch (const std::exception& e) {
        opaquePipeline = VK_NULL_HANDLE;   // recordCmdBuf draws windows with the blending pipeline
        RLOG_E("opaque window pipeline unavailable (%s): windows are drawn with blending", e.what());
    }
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis,
    VkRect2D scissorRect)
{
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb, &bi) != VK_SUCCESS)
        throw std::runtime_error("begin cb");

    ahbTransitions.clear();
    postUpload.clear();

    // A guest buffer is written outside this device (the guest's driver, another API in another
    // process). With VK_EXT_queue_family_foreign it is acquired from the foreign queue family on
    // every frame that samples it and released back after the render pass, as Android's
    // RenderEngine does with its AHB textures; without the acquire the driver does not have to
    // make the producer's writes visible (or resolve the layout they left), and what the shader
    // reads is undefined. The first acquire comes from UNDEFINED, as nothing is known about the
    // image yet; later ones from the layout it was released in. A device without the extension
    // keeps the one-time transition.
    for (auto& d : draws) {
        if (d.img == VK_NULL_HANDLE) continue;
        if (d.isAHB) {
            if (!foreignQueueFamily_ && !d.needsTransition) continue;
            auto dup = std::find_if(ahbTransitions.begin(), ahbTransitions.end(),
                [&](const VkImageMemoryBarrier& o) { return o.image == d.img; });
            if (dup != ahbTransitions.end()) {   // the same buffer drawn twice: one acquire per frame
                if (d.needsTransition) dup->oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                continue;
            }
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = d.needsTransition ? VK_IMAGE_LAYOUT_UNDEFINED
                                            : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            if (foreignQueueFamily_) {
                b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
                b.dstQueueFamilyIndex = graphicsQueueFamilyIndex;
            } else {
                b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            }
            b.image = d.img;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.srcAccessMask = 0;
            b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        }
    }

    if (!ahbTransitions.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    }
    const bool hasCursorCopy = hasCursorUpload && cursorImg != VK_NULL_HANDLE &&
                               cursorUpload != VK_NULL_HANDLE;
    // Not drawn before its first upload: a fresh texture holds nothing yet.
    const bool cursorDrawn = curVis && cursorImg != VK_NULL_HANDLE && cursorDS != VK_NULL_HANDLE &&
                             (cursorImgInit || hasCursorCopy);
    if (hasCursorCopy) {
        const bool fresh = !cursorImgInit;   // never written: UNDEFINED, nothing to wait for
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = cursorImg;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = fresh ? 0 : VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, fresh ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);

        VkBufferImageCopy r{};
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)curW, (uint32_t)curH, 1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg,
                                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);

        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)postUpload.size(), postUpload.data());
    }

    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = recordPass != VK_NULL_HANDLE ? recordPass : renderPass;
    rpi.framebuffer = recordFB != VK_NULL_HANDLE ? recordFB : swapchainFBs[imgIdx];
    rpi.renderArea = {{0, 0}, swapchainExt};
    VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clear;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{0, 0, (float)swapchainExt.width, (float)swapchainExt.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &viewport);

    int32_t scX = std::max(0, scissorRect.offset.x);
    int32_t scY = std::max(0, scissorRect.offset.y);
    uint32_t maxW = swapchainExt.width > (uint32_t)scX ? swapchainExt.width - (uint32_t)scX : 0u;
    uint32_t maxH = swapchainExt.height > (uint32_t)scY ? swapchainExt.height - (uint32_t)scY : 0u;
    VkRect2D scissor{{scX, scY},
        {std::min(scissorRect.extent.width, maxW), std::min(scissorRect.extent.height, maxH)}};
    vk_.CmdSetScissor(cb, 0, 1, &scissor);

    float rotCosR, rotSinR;
    getPreRotationCosSin(rotCosR, rotSinR);

    const bool useSgsr = filterMode == 2 && sgsrPipeline != VK_NULL_HANDLE;
    const bool useFsr = filterMode == 3 && fsr1Pipeline != VK_NULL_HANDLE;
    const bool useLanczos = filterMode == 4 && lanczosPipeline != VK_NULL_HANDLE;
    const bool useSgsrHq = filterMode == 6 && sgsrHqPipeline != VK_NULL_HANDLE;
    const bool useNis = filterMode == 7 && nisPipeline != VK_NULL_HANDLE;
    const bool useColorBoost = filterMode == 5 && postfxPipeline != VK_NULL_HANDLE;
    const bool usePostFX = postFXMode > 0 && postfxPipeline != VK_NULL_HANDLE;
    const bool useStretch = stretchMode == 1 && stretchPipeline != VK_NULL_HANDLE;

    VkPipeline activePipeline = useNis ? nisPipeline
                              : useSgsrHq ? sgsrHqPipeline
                              : useSgsr ? sgsrPipeline
                              : useFsr ? fsr1Pipeline
                              : useLanczos ? lanczosPipeline
                              : useColorBoost ? postfxPipeline
                              : usePostFX ? postfxPipeline
                              : useStretch ? stretchPipeline
                              : (opaquePipeline != VK_NULL_HANDLE ? opaquePipeline : pipeline);
    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, activePipeline);

    for (auto& d : draws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout,
                                  0, 1, &d.ds, 0, nullptr);

        if (useSgsr || useSgsrHq || useNis) {
            WindowPushConstantsSGSR pc{};
            if (useStretch) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
            else {
                pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
                pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            }
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            float srcW = (float)std::max(d.w, 1);
            float srcH = (float)std::max(d.h, 1);
            pc.invSrcW = 1.0f / srcW;
            pc.invSrcH = 1.0f / srcH;
            pc.srcW = srcW;
            pc.srcH = srcH;
            pc.effectId = postFXMode;
            pc.resW = 0.0f;
            pc.sharpness = sharpness;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useFsr || useLanczos) {
            WindowPushConstantsFSR1 pc{};
            if (useStretch) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
            else {
                pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
                pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            }
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.srcW = (float)std::max(d.w, 1);
            pc.srcH = (float)std::max(d.h, 1);
            pc.outW = std::max(1.0f, std::abs(pc.ndcX1 - pc.ndcX0) * 0.5f *
                                       (float)swapchainExt.width);
            pc.outH = std::max(1.0f, std::abs(pc.ndcY1 - pc.ndcY0) * 0.5f *
                                       (float)swapchainExt.height);
            pc.effectId = postFXMode;
            pc.sharpness = sharpness;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useColorBoost || usePostFX) {
            WindowPushConstantsPostFX pc{};
            pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.effectId = useColorBoost ? 21 : postFXMode;
            pc.postFxId = useColorBoost ? postFXMode : 0;
            pc.sharpness = sharpness;
            pc.resW = (float)std::max(d.w, 1);
            pc.resH = (float)std::max(d.h, 1);
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useStretch) {
            WindowPushConstantsStretch pc{};
            pc.ndcX0 = -1.0f;
            pc.ndcX1 = 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.strength = stretchStrength;
            pc.profile = stretchProfile;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else {
            WindowPushConstants pc{};
            pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        }
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {
        vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout,
                                  0, 1, &cursorDS, 0, nullptr);
        // The image goes at pointer - hot spot even when that is off the top or left edge, as
        // an X server draws it; clamping it to 0 moved a cursor whose hot spot is not its corner
        // (I-beam, crosshair) away from the pointer near those edges.
        float cx = (float)((int)ptrX - curHotX);
        float cy = (float)((int)ptrY - curHotY);
        WindowPushConstants pc{};
        pc.ndcX0 = (ox + cx * sx) / cw * 2.0f - 1.0f;
        pc.ndcY0 = (oy + cy * sy) / ch * 2.0f - 1.0f;
        pc.ndcX1 = (ox + (cx + curW) * sx) / cw * 2.0f - 1.0f;
        pc.ndcY1 = (oy + (cy + curH) * sy) / ch * 2.0f - 1.0f;
        pc.useTexAlpha = 1;
        pc.cosR = rotCosR;
        pc.sinR = rotSinR;
        vk_.CmdPushConstants(cb, pipeLayout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    vk_.CmdEndRenderPass(cb);
    if (foreignQueueFamily_ && !ahbTransitions.empty()) {
        // Hand the guest buffers back to the foreign queue family, layout unchanged. Reads need
        // no access mask; the fragment-stage dependency orders the release after the sampling.
        for (auto& b : ahbTransitions) {
            b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex = graphicsQueueFamilyIndex;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            b.srcAccessMask = 0;
            b.dstAccessMask = 0;
        }
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    }

    if (recordLeaveOpen) return;

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed status=%d draws=%zu mode=%d",
               (int)endStatus, draws.size(), filterMode);
        throw std::runtime_error("end cb");
    }
}

