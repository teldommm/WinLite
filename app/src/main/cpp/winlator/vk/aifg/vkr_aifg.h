
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_AIFG_MAX_GENERATIONS 3u
#define VKR_AIFG_MAX_TARGETS 7u

typedef struct VkrAifg VkrAifg;

VkrAifg* vkr_aifg_create(VkDevice device, VkPhysicalDevice physical_device,
                         const char* cache_path);
void vkr_aifg_destroy(VkrAifg* aifg);

void vkr_aifg_configure(VkrAifg* aifg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, float refresh_rate);

void vkr_aifg_set_refresh_rate(VkrAifg* aifg, float refresh_rate);

void vkr_aifg_set_guest_extent(VkrAifg* aifg, uint32_t width, uint32_t height);

bool vkr_aifg_needs_rebuild(const VkrAifg* aifg, uint32_t width, uint32_t height,
                            VkFormat format);

bool vkr_aifg_prepare(VkrAifg* aifg, uint32_t width, uint32_t height, VkFormat format);

uint32_t vkr_aifg_plan(VkrAifg* aifg, uint32_t capacity, uint64_t source_frames);

void vkr_aifg_process(VkrAifg* aifg, VkCommandBuffer cmd, VkImage source,
                      uint32_t width, uint32_t height, uint32_t generations);

void vkr_aifg_generate_into(VkrAifg* aifg, VkCommandBuffer cmd, uint32_t generation,
                            uint32_t target_index, VkImage target_image, VkImageView target_view,
                            uint32_t width, uint32_t height);

void vkr_aifg_forget_targets(VkrAifg* aifg);

void vkr_aifg_reset(VkrAifg* aifg);

#ifdef __cplusplus
}
#endif
