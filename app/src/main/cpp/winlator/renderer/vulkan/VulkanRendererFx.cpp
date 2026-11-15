#include "VulkanRendererFx.h"

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

// Every window pipeline is the shared vertex shader plus one fragment shader; buildPipeline() is
// the only place that fills in the fixed-function state.

namespace {

struct PipelineSource {
    const uint32_t* code;
    size_t          size;
    const char*     name;
};

// Indexed by VulkanRendererFx::Kind.
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
              "one source per VulkanRendererFx::Kind");

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

// ---------------------------------------------------------------- lifetime

void VulkanRendererFx::create(VkTable* vk, VkDevice device, VkPhysicalDevice physicalDevice,
                              VkRenderPass renderPass, VkDescriptorSetLayout dsLayout,
                              const std::string& cacheDir) {
    vk_ = vk;
    device_ = device;
    physical_ = physicalDevice;
    renderPass_ = renderPass;
    dsLayout_ = dsLayout;
    createCache(cacheDir);
    createLayout();
    createBasePipelines();
}

void VulkanRendererFx::destroy() {
    if (!vk_) return;
    for (VkPipeline p : {sgsrPipeline_, fsr1Pipeline_, lanczosPipeline_, sgsrHqPipeline_, nisPipeline_,
                         stretchPipeline_, postfxPipeline_, opaquePipeline_, pipeline_})
        if (p != VK_NULL_HANDLE) vk_->DestroyPipeline(device_, p, nullptr);
    sgsrPipeline_ = fsr1Pipeline_ = lanczosPipeline_ = sgsrHqPipeline_ = nisPipeline_ = VK_NULL_HANDLE;
    stretchPipeline_ = postfxPipeline_ = opaquePipeline_ = pipeline_ = VK_NULL_HANDLE;
    if (pipeLayout_ != VK_NULL_HANDLE) vk_->DestroyPipelineLayout(device_, pipeLayout_, nullptr);
    pipeLayout_ = VK_NULL_HANDLE;
    saveCache();
    if (pipelineCache_ != VK_NULL_HANDLE) vk_->DestroyPipelineCache(device_, pipelineCache_, nullptr);
    pipelineCache_ = VK_NULL_HANDLE;
}

// ---------------------------------------------------------------- pipeline cache

void VulkanRendererFx::createCache(const std::string& cacheDir) {
    // Never fatal: without a cache the pipelines are just compiled from scratch every run.
    if (!vk_->CreatePipelineCache || !vk_->GetPipelineCacheData || !vk_->DestroyPipelineCache) return;

    VkPhysicalDeviceProperties props{};
    vk_->GetPhysicalDeviceProperties(physical_, &props);

    std::vector<uint8_t> data;
    if (!cacheDir.empty()) {
        // A cache is only valid for the device and driver build that wrote it: both are in the name.
        char name[96];
        snprintf(name, sizeof(name), "winlite_vkpc_%08x_%08x_%02x%02x%02x%02x.bin",
                 props.vendorID, props.deviceID,
                 props.pipelineCacheUUID[0], props.pipelineCacheUUID[1],
                 props.pipelineCacheUUID[2], props.pipelineCacheUUID[3]);
        pipelineCachePath_ = cacheDir + "/" + name;
        data = readFile(pipelineCachePath_);
        if (!data.empty() && !cacheHeaderMatches(data, props)) {
            RLOG("pipeline cache %s does not match this driver: starting empty", pipelineCachePath_.c_str());
            data.clear();
        }
    }

    VkPipelineCacheCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    ci.initialDataSize = data.size();
    ci.pInitialData = data.empty() ? nullptr : data.data();
    VkResult r = vk_->CreatePipelineCache(device_, &ci, nullptr, &pipelineCache_);
    if (r != VK_SUCCESS && !data.empty()) {
        RLOG_E("pipeline cache rejected by the driver (%d): starting empty", (int)r);
        data.clear();
        ci.initialDataSize = 0;
        ci.pInitialData = nullptr;
        r = vk_->CreatePipelineCache(device_, &ci, nullptr, &pipelineCache_);
    }
    if (r != VK_SUCCESS) {
        RLOG_E("vkCreatePipelineCache failed (%d): building pipelines without a cache", (int)r);
        pipelineCache_ = VK_NULL_HANDLE;
        return;
    }
    pipelineCacheSavedSize_ = data.size();
    RLOG("pipeline cache: %zu bytes loaded from %s", data.size(),
         pipelineCachePath_.empty() ? "(not persisted)" : pipelineCachePath_.c_str());
}

void VulkanRendererFx::saveCache() {
    if (pipelineCache_ == VK_NULL_HANDLE || pipelineCachePath_.empty() || !vk_->GetPipelineCacheData) return;
    std::lock_guard<std::mutex> lk(pipelineBuildMutex_);

    size_t size = 0;
    if (vk_->GetPipelineCacheData(device_, pipelineCache_, &size, nullptr) != VK_SUCCESS) return;
    if (size <= 16 + VK_UUID_SIZE || size == pipelineCacheSavedSize_) return;   // empty, or nothing new
    std::vector<uint8_t> data(size);
    const VkResult r = vk_->GetPipelineCacheData(device_, pipelineCache_, &size, data.data());
    if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || size == 0 || size > data.size()) return;
    data.resize(size);

    // Written next to the target and renamed over it: a crash or a full disk mid-write leaves the
    // previous cache intact instead of a truncated one.
    const std::string tmp = pipelineCachePath_ + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) { RLOG_E("pipeline cache: cannot write %s", tmp.c_str()); return; }
    const bool written = fwrite(data.data(), 1, data.size(), f) == data.size();
    const bool closed = fclose(f) == 0;
    if (!written || !closed || rename(tmp.c_str(), pipelineCachePath_.c_str()) != 0) {
        RLOG_E("pipeline cache: saving %s failed", pipelineCachePath_.c_str());
        remove(tmp.c_str());
        return;
    }
    pipelineCacheSavedSize_ = data.size();
    RLOG("pipeline cache saved: %zu bytes", data.size());
}

// ---------------------------------------------------------------- pipelines

void VulkanRendererFx::createLayout() {
    VkPushConstantRange pc{};
    pc.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pc.size = sizeof(WindowPushConstantsSGSR);
    VkPipelineLayoutCreateInfo li{};
    li.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    li.setLayoutCount = 1;
    li.pSetLayouts = &dsLayout_;
    li.pushConstantRangeCount = 1;
    li.pPushConstantRanges = &pc;
    if (vk_->CreatePipelineLayout(device_, &li, nullptr, &pipeLayout_) != VK_SUCCESS)
        throw std::runtime_error("pipelayout");
}

VkShaderModule VulkanRendererFx::makeShader(const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = size; ci.pCode = code; VkShaderModule m;
    if (vk_->CreateShaderModule(device_, &ci, nullptr, &m) != VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}

VkPipeline VulkanRendererFx::buildPipeline(const uint32_t* fragCode, size_t fragSize,
                                           bool blend, const char* name) {
    VkShaderModule vert = makeShader(window_vert_code, sizeof(window_vert_code));
    VkShaderModule frag = VK_NULL_HANDLE;
    try {
        frag = makeShader(fragCode, fragSize);
    } catch (...) {
        vk_->DestroyShaderModule(device_, vert, nullptr);
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
    pi.layout = pipeLayout_;
    pi.renderPass = renderPass_;
    pi.subpass = 0;

    VkPipeline out = VK_NULL_HANDLE;
    VkResult result;
    {
        // The cache is shared with saveCache(), which reads it back.
        std::lock_guard<std::mutex> lk(pipelineBuildMutex_);
        result = vk_->CreateGraphicsPipelines(device_, pipelineCache_, 1, &pi, nullptr, &out);
    }
    vk_->DestroyShaderModule(device_, frag, nullptr);
    vk_->DestroyShaderModule(device_, vert, nullptr);
    if (result != VK_SUCCESS) throw std::runtime_error(name);
    return out;
}

void VulkanRendererFx::createBasePipelines() {
    // The window pipeline blends: the cursor needs it. Guest buffers are drawn through the same
    // shader without blending (opaque), so a window's alpha channel never shows through.
    pipeline_ = buildPipeline(window_frag_code, sizeof(window_frag_code), true, "window");
    try {
        opaquePipeline_ = buildPipeline(window_frag_code, sizeof(window_frag_code), false, "opaque window");
        RLOG("opaque window pipeline built (blend off; cursor alpha isolated)");
    } catch (const std::exception& e) {
        opaquePipeline_ = VK_NULL_HANDLE;   // windows are then drawn with the blending pipeline
        RLOG_E("opaque window pipeline unavailable (%s): windows are drawn with blending", e.what());
    }
}

VkPipeline& VulkanRendererFx::slot(Kind kind) {
    switch (kind) {
    case Kind::Sgsr:    return sgsrPipeline_;
    case Kind::Fsr1:    return fsr1Pipeline_;
    case Kind::Lanczos: return lanczosPipeline_;
    case Kind::SgsrHq:  return sgsrHqPipeline_;
    case Kind::Nis:     return nisPipeline_;
    case Kind::Stretch: return stretchPipeline_;
    case Kind::PostFx:  return postfxPipeline_;
    }
    return postfxPipeline_;   // not reached
}

bool VulkanRendererFx::has(Kind kind) const {
    return const_cast<VulkanRendererFx*>(this)->slot(kind) != VK_NULL_HANDLE;
}

bool VulkanRendererFx::upscalerKindFor(int mode, Kind& kind) {
    switch (mode) {
    case 2: kind = Kind::Sgsr;    return true;
    case 3: kind = Kind::Fsr1;    return true;
    case 4: kind = Kind::Lanczos; return true;
    case 6: kind = Kind::SgsrHq;  return true;
    case 7: kind = Kind::Nis;     return true;
    default: return false;
    }
}

const char* VulkanRendererFx::filterName(int m) {
    return m==1?"NEAREST":m==2?"SGSR":m==3?"FSR":m==4?"LANCZOS2":m==5?"COLOR_BOOST":m==6?"SGSR_HQ":m==7?"NIS":"LINEAR";
}

VkPipeline VulkanRendererFx::prebuild(Kind kind) {
    if (slot(kind) != VK_NULL_HANDLE) return VK_NULL_HANDLE;
    const PipelineSource& src = kPipelineSources[(size_t)kind];
    // The effect setters are called straight from JNI: an exception escaping there aborts the
    // process. A failed build leaves the pipeline unset, and the frame falls back to the plain
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

void VulkanRendererFx::adopt(Kind kind, VkPipeline built) {
    if (built == VK_NULL_HANDLE) return;
    VkPipeline& s = slot(kind);
    if (s == VK_NULL_HANDLE) s = built;
    else vk_->DestroyPipeline(device_, built, nullptr);   // lost a race with another builder
}

bool VulkanRendererFx::upscalerActive() const {
    switch (filterMode) {
    case 2: return sgsrPipeline_ != VK_NULL_HANDLE;
    case 3: return fsr1Pipeline_ != VK_NULL_HANDLE;
    case 4: return lanczosPipeline_ != VK_NULL_HANDLE;
    case 6: return sgsrHqPipeline_ != VK_NULL_HANDLE;
    case 7: return nisPipeline_ != VK_NULL_HANDLE;
    default: return false;
    }
}

void VulkanRendererFx::syncPostFX() {
    // The upscalers are single pass and apply the PostFX effect inline, so the postfx pipeline
    // is bound only for ColorBoost (mode 5) and for an effect on the plain blit. The setters have
    // built it (outside the lock) when it is needed, and a build that failed leaves the plain blit.
    const bool needed = filterMode == 5 || (postFXMode > 0 && !upscalerActive());
    if (!needed && postfxPipeline_ != VK_NULL_HANDLE) {
        vk_->DeviceWaitIdle(device_);
        vk_->DestroyPipeline(device_, postfxPipeline_, nullptr);
        postfxPipeline_ = VK_NULL_HANDLE;
        RLOG("postfx pipeline released");
    }
}

// ---------------------------------------------------------------- recording

void VulkanRendererFx::bind(VkCommandBuffer cb) {
    useSgsr_ = filterMode == 2 && sgsrPipeline_ != VK_NULL_HANDLE;
    useFsr_ = filterMode == 3 && fsr1Pipeline_ != VK_NULL_HANDLE;
    useLanczos_ = filterMode == 4 && lanczosPipeline_ != VK_NULL_HANDLE;
    useSgsrHq_ = filterMode == 6 && sgsrHqPipeline_ != VK_NULL_HANDLE;
    useNis_ = filterMode == 7 && nisPipeline_ != VK_NULL_HANDLE;
    useColorBoost_ = filterMode == 5 && postfxPipeline_ != VK_NULL_HANDLE;
    usePostFX_ = postFXMode > 0 && postfxPipeline_ != VK_NULL_HANDLE;
    useStretch_ = stretchMode == 1 && stretchPipeline_ != VK_NULL_HANDLE;

    VkPipeline active = useNis_ ? nisPipeline_
                      : useSgsrHq_ ? sgsrHqPipeline_
                      : useSgsr_ ? sgsrPipeline_
                      : useFsr_ ? fsr1Pipeline_
                      : useLanczos_ ? lanczosPipeline_
                      : useColorBoost_ ? postfxPipeline_
                      : usePostFX_ ? postfxPipeline_
                      : useStretch_ ? stretchPipeline_
                      : (opaquePipeline_ != VK_NULL_HANDLE ? opaquePipeline_ : pipeline_);
    vk_->CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, active);
}

void VulkanRendererFx::draw(VkCommandBuffer cb, const Input& in, const Target& dst) {
    if (in.ds == VK_NULL_HANDLE) return;
    vk_->CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout_,
                               0, 1, &in.ds, 0, nullptr);

    const VkShaderStageFlags stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    if (useSgsr_ || useSgsrHq_ || useNis_) {
        WindowPushConstantsSGSR pc{};
        if (useStretch_) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
        else { pc.ndcX0 = dst.x0; pc.ndcX1 = dst.x1; }
        pc.ndcY0 = dst.y0;
        pc.ndcY1 = dst.y1;
        pc.useTexAlpha = 0;
        float srcW = (float)std::max(in.w, 1);
        float srcH = (float)std::max(in.h, 1);
        pc.invSrcW = 1.0f / srcW;
        pc.invSrcH = 1.0f / srcH;
        pc.srcW = srcW;
        pc.srcH = srcH;
        pc.effectId = postFXMode;
        pc.resW = 0.0f;
        pc.sharpness = sharpness;
        pc.cosR = dst.cosR;
        pc.sinR = dst.sinR;
        vk_->CmdPushConstants(cb, pipeLayout_, stages, 0, sizeof(pc), &pc);
    } else if (useFsr_ || useLanczos_) {
        WindowPushConstantsFSR1 pc{};
        if (useStretch_) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
        else { pc.ndcX0 = dst.x0; pc.ndcX1 = dst.x1; }
        pc.ndcY0 = dst.y0;
        pc.ndcY1 = dst.y1;
        pc.useTexAlpha = 0;
        pc.srcW = (float)std::max(in.w, 1);
        pc.srcH = (float)std::max(in.h, 1);
        pc.outW = std::max(1.0f, std::abs(pc.ndcX1 - pc.ndcX0) * 0.5f * (float)dst.extent.width);
        pc.outH = std::max(1.0f, std::abs(pc.ndcY1 - pc.ndcY0) * 0.5f * (float)dst.extent.height);
        pc.effectId = postFXMode;
        pc.sharpness = sharpness;
        pc.cosR = dst.cosR;
        pc.sinR = dst.sinR;
        vk_->CmdPushConstants(cb, pipeLayout_, stages, 0, sizeof(pc), &pc);
    } else if (useColorBoost_ || usePostFX_) {
        WindowPushConstantsPostFX pc{};
        pc.ndcX0 = dst.x0;
        pc.ndcY0 = dst.y0;
        pc.ndcX1 = dst.x1;
        pc.ndcY1 = dst.y1;
        pc.effectId = useColorBoost_ ? 21 : postFXMode;
        pc.postFxId = useColorBoost_ ? postFXMode : 0;
        pc.sharpness = sharpness;
        pc.resW = (float)std::max(in.w, 1);
        pc.resH = (float)std::max(in.h, 1);
        pc.cosR = dst.cosR;
        pc.sinR = dst.sinR;
        vk_->CmdPushConstants(cb, pipeLayout_, stages, 0, sizeof(pc), &pc);
    } else if (useStretch_) {
        WindowPushConstantsStretch pc{};
        pc.ndcX0 = -1.0f;
        pc.ndcX1 = 1.0f;
        pc.ndcY0 = dst.y0;
        pc.ndcY1 = dst.y1;
        pc.useTexAlpha = 0;
        pc.strength = stretchStrength;
        pc.profile = stretchProfile;
        pc.cosR = dst.cosR;
        pc.sinR = dst.sinR;
        vk_->CmdPushConstants(cb, pipeLayout_, stages, 0, sizeof(pc), &pc);
    } else {
        WindowPushConstants pc{};
        pc.ndcX0 = dst.x0;
        pc.ndcY0 = dst.y0;
        pc.ndcX1 = dst.x1;
        pc.ndcY1 = dst.y1;
        pc.useTexAlpha = 0;
        pc.cosR = dst.cosR;
        pc.sinR = dst.sinR;
        vk_->CmdPushConstants(cb, pipeLayout_, stages, 0, sizeof(pc), &pc);
    }
    vk_->CmdDraw(cb, 4, 1, 0, 0);
}

void VulkanRendererFx::drawCursor(VkCommandBuffer cb, VkDescriptorSet ds, const Target& dst) {
    vk_->CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vk_->CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout_,
                               0, 1, &ds, 0, nullptr);
    WindowPushConstants pc{};
    pc.ndcX0 = dst.x0;
    pc.ndcY0 = dst.y0;
    pc.ndcX1 = dst.x1;
    pc.ndcY1 = dst.y1;
    pc.useTexAlpha = 1;
    pc.cosR = dst.cosR;
    pc.sinR = dst.sinR;
    vk_->CmdPushConstants(cb, pipeLayout_,
        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
    vk_->CmdDraw(cb, 4, 1, 0, 0);
}

void VulkanRendererFx::recordStandalone(VkCommandBuffer cb, const Input& in, VkRenderPass renderPass,
                                        VkFramebuffer framebuffer, VkExtent2D extent) {
    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = framebuffer;
    rpi.renderArea = {{0, 0}, extent};
    VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clear;
    vk_->CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport{0, 0, (float)extent.width, (float)extent.height, 0, 1};
    vk_->CmdSetViewport(cb, 0, 1, &viewport);
    VkRect2D scissor{{0, 0}, extent};
    vk_->CmdSetScissor(cb, 0, 1, &scissor);
    bind(cb);
    Target t{};
    t.x0 = -1.0f; t.y0 = -1.0f; t.x1 = 1.0f; t.y1 = 1.0f;
    t.extent = extent;
    t.cosR = 1.0f; t.sinR = 0.0f;
    draw(cb, in, t);
    vk_->CmdEndRenderPass(cb);
}
