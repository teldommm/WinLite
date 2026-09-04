
#include "vkr_aifg.h"

#include "aifg_chain.hpp"
#include "aifg_pacer.hpp"
#include "aifg_shaders.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <string>

#include <android/log.h>

#define AIFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VkrAifg", __VA_ARGS__)
#define AIFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VkrAifg", __VA_ARGS__)

namespace {

constexpr uint64_t AIFG_REQUIRED_FRAMES = 2;
constexpr uint32_t AIFG_RECURRENCE_FRAMES = 2;
constexpr uint64_t AIFG_TELEMETRY_INTERVAL = 120;

constexpr float AIFG_FLOW_SCALE_MIN = 0.25f;
constexpr float AIFG_FLOW_SCALE_MAX = 1.0f;
constexpr float AIFG_FLOW_SCALE_STEPS = 20.0f;

VkImageMemoryBarrier MakeTransitionBarrier(VkImage image, VkAccessFlags src_access,
                                           VkAccessFlags dst_access, VkImageLayout old_layout,
                                           VkImageLayout new_layout) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    return barrier;
}

void CopyPresentedFrame(VkCommandBuffer cmd, VkImage source, aifg::AifgImage& destination,
                        VkExtent2D extent) {
    const VkImageMemoryBarrier before[] = {
        MakeTransitionBarrier(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
        MakeTransitionBarrier(destination.Handle(), VK_ACCESS_SHADER_READ_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT, destination.Layout(),
                              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    vkd.CmdPipelineBarrier(cmd,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, before);

    VkImageCopy region{};
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.dstSubresource.layerCount = 1;
    region.extent = {extent.width, extent.height, 1};
    vkd.CmdCopyImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.Handle(),
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    const VkImageMemoryBarrier after[] = {
        MakeTransitionBarrier(source, VK_ACCESS_TRANSFER_READ_BIT,
                              VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        MakeTransitionBarrier(destination.Handle(), VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              VK_IMAGE_LAYOUT_GENERAL),
    };
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, nullptr, 0, nullptr, 2, after);

    destination.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
}

}

struct VkrAifg {
    aifg::Device device;
    std::string cache_path;
    std::unique_ptr<aifg::AifgShaders> shaders;
    std::unique_ptr<aifg::AifgChain> chain;
    aifg::AifgPacer pacer;
    aifg::AifgPlan plan{};

    VkExtent2D built_extent{};
    VkExtent2D peak_guest_extent{};
    VkFormat built_format{VK_FORMAT_UNDEFINED};
    float built_flow_scale{};
    float flow_scale{1.0f};

    uint64_t frame_count{};
    uint64_t last_count{};
    size_t last_generations{};
    uint64_t plan_calls{};
    uint32_t warm_streak{};
    bool warm{};
    bool generated{};
    bool unavailable{};
};

static float aifg_effective_flow_scale(const VkrAifg* aifg, uint32_t width) {
    if (width == 0 || aifg->peak_guest_extent.width == 0) return aifg->flow_scale;

    const float ratio =
        static_cast<float>(aifg->peak_guest_extent.width) / static_cast<float>(width);
    const float stepped = std::ceil(ratio * AIFG_FLOW_SCALE_STEPS) / AIFG_FLOW_SCALE_STEPS;
    return std::clamp(std::min(stepped, aifg->flow_scale), AIFG_FLOW_SCALE_MIN,
                      AIFG_FLOW_SCALE_MAX);
}

VkrAifg* vkr_aifg_create(VkDevice device, VkPhysicalDevice physical_device,
                         const char* cache_path) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE || cache_path == nullptr) {
        return nullptr;
    }

    auto* aifg = new VkrAifg();
    aifg->device = aifg::Device(device, physical_device);
    aifg->cache_path = cache_path;

    aifg->shaders = std::make_unique<aifg::AifgShaders>(aifg->device, aifg->cache_path.c_str());
    if (!aifg->shaders->IsValid()) {
        AIFG_LOGW("shader cache at %s did not yield all modules", cache_path);
        delete aifg;
        return nullptr;
    }

    AIFG_LOGI("frame generation shaders ready");
    return aifg;
}

void vkr_aifg_destroy(VkrAifg* aifg) {
    delete aifg;
}

void vkr_aifg_configure(VkrAifg* aifg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, float refresh_rate) {
    if (!aifg) return;

    aifg::AifgPacerConfig config = aifg->pacer.Config();
    config.multiplier = multiplier;
    config.target_rate = target_rate;
    config.refresh_rate = refresh_rate;
    aifg->pacer.SetConfig(config);
    aifg->flow_scale = std::clamp(flow_scale, AIFG_FLOW_SCALE_MIN, AIFG_FLOW_SCALE_MAX);
}

void vkr_aifg_set_guest_extent(VkrAifg* aifg, uint32_t width, uint32_t height) {
    if (!aifg || width == 0 || height == 0) return;
    aifg->peak_guest_extent.width = std::max(aifg->peak_guest_extent.width, width);
    aifg->peak_guest_extent.height = std::max(aifg->peak_guest_extent.height, height);
}

void vkr_aifg_set_refresh_rate(VkrAifg* aifg, float refresh_rate) {
    if (!aifg) return;

    aifg::AifgPacerConfig config = aifg->pacer.Config();
    if (config.refresh_rate == refresh_rate) return;
    config.refresh_rate = refresh_rate;
    aifg->pacer.SetConfig(config);
}

bool vkr_aifg_needs_rebuild(const VkrAifg* aifg, uint32_t width, uint32_t height,
                            VkFormat format) {
    if (!aifg || aifg->unavailable) return false;
    return !aifg->chain || aifg->built_extent.width != width
        || aifg->built_extent.height != height || aifg->built_format != format
        || aifg->built_flow_scale != aifg_effective_flow_scale(aifg, width);
}

bool vkr_aifg_prepare(VkrAifg* aifg, uint32_t width, uint32_t height, VkFormat format) {
    if (!aifg || aifg->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    if (!vkr_aifg_needs_rebuild(aifg, width, height, format)) {
        return aifg->chain && aifg->chain->Valid();
    }

    const float scale = aifg_effective_flow_scale(aifg, width);

    aifg->chain.reset();
    aifg->chain = std::make_unique<aifg::AifgChain>(
        aifg->device, *aifg->shaders, VkExtent2D{width, height}, format, scale);
    if (!aifg->chain->Valid()) {
        AIFG_LOGW("chain build failed at %ux%u; frame generation unavailable", width, height);
        aifg->chain.reset();
        aifg->unavailable = true;
        return false;
    }

    aifg->built_extent = VkExtent2D{width, height};
    aifg->built_format = format;
    aifg->built_flow_scale = scale;
    aifg->frame_count = 0;
    aifg->plan_calls = 0;
    aifg->warm_streak = 0;
    aifg->warm = false;
    aifg->generated = false;
    aifg->pacer.Reset();
    AIFG_LOGI("chain built at %ux%u, flow %ux%u scale %.2f (preset %.2f, guest %ux%u)", width,
              height, (unsigned)(width * aifg->built_flow_scale),
              (unsigned)(height * aifg->built_flow_scale), (double)aifg->built_flow_scale,
              (double)aifg->flow_scale, aifg->peak_guest_extent.width,
              aifg->peak_guest_extent.height);
    return true;
}

uint32_t vkr_aifg_plan(VkrAifg* aifg, uint32_t capacity, uint64_t source_frames) {
    if (!aifg || aifg->unavailable) return 0;

    aifg->plan = aifg->pacer.Plan(std::min<size_t>(capacity, VKR_AIFG_MAX_GENERATIONS),
                                  source_frames);

    aifg->warm = aifg->plan.warm && aifg->frame_count + 1 >= AIFG_REQUIRED_FRAMES;
    aifg->warm_streak = aifg->warm ? aifg->warm_streak + 1 : 0;
    aifg->generated =
        aifg->warm && aifg->warm_streak >= AIFG_RECURRENCE_FRAMES && aifg->plan.generations > 0;

    if ((aifg->plan_calls++ % AIFG_TELEMETRY_INTERVAL) == 0) {
        const aifg::AifgPacerStats stats = aifg->pacer.Stats();
        const float wanted =
            stats.source_rate * static_cast<float>(aifg->plan.generations + 1);
        AIFG_LOGI("pace gen=%zu max=%zu cap=%u guest=%.1f loop=%.1f refresh=%.1f target=%.0f "
                  "slots=%.2f drawn=%llu needs=%.1fHz%s%s",
                  aifg->plan.generations, aifg->pacer.MaxGenerations(), capacity,
                  (double)stats.source_rate, (double)stats.loop_rate,
                  (double)stats.refresh_rate, (double)stats.target_rate, (double)stats.slots,
                  (unsigned long long)stats.last_drawn, (double)wanted,
                  (stats.refresh_rate > 0.0f && wanted > stats.refresh_rate + 1.0f)
                      ? " PANEL-BOUND"
                      : "",
                  stats.rates_settled ? (aifg->warm ? "" : " cold") : " sampling");
    }

    return aifg->generated ? static_cast<uint32_t>(aifg->plan.generations) : 0;
}

void vkr_aifg_process(VkrAifg* aifg, VkCommandBuffer cmd, VkImage source, uint32_t width,
                      uint32_t height, uint32_t generations) {
    if (!aifg || !aifg->chain || !aifg->chain->Valid()) return;

    const uint64_t count = aifg->frame_count++;
    aifg->last_count = count;
    aifg->last_generations = generations;

    CopyPresentedFrame(cmd, source, aifg->chain->Input(count), VkExtent2D{width, height});
    if (aifg->warm) {
        aifg->chain->DispatchShared(cmd, count);
    }
}

void vkr_aifg_generate_into(VkrAifg* aifg, VkCommandBuffer cmd, uint32_t generation,
                            uint32_t target_index, VkImage target_image, VkImageView target_view,
                            uint32_t width, uint32_t height) {
    if (!aifg || !aifg->chain || !aifg->chain->Valid()) return;
    if (target_index >= aifg::AIFG_MAX_TARGETS) return;

    aifg->chain->SetTarget(aifg->device, aifg->last_generations, generation, target_index,
                           target_view);
    aifg->chain->DispatchGeneration(cmd, aifg->last_count, aifg->last_generations, generation,
                                    target_index, target_image, VkExtent2D{width, height});
}

void vkr_aifg_forget_targets(VkrAifg* aifg) {
    if (!aifg || !aifg->chain) return;
    aifg->chain->ForgetTargets();
}

void vkr_aifg_reset(VkrAifg* aifg) {
    if (!aifg) return;
    aifg->pacer.Reset();
    aifg->peak_guest_extent = VkExtent2D{};
    aifg->warm_streak = 0;
    aifg->warm = false;
    aifg->generated = false;
    aifg->plan = {};
}
