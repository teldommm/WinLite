// SPDX-FileCopyrightText: Copyright 2026 qwertypower (DEVAR Entertainment LLC)
// SPDX-License-Identifier: MIT
//
// WFG frame generation: a Vulkan compute realisation of Dense Inverse Search
// optical flow. The algorithm and its reference implementation come from
// OpenCV's DISOpticalFlow, which adopted Till Kroeger's original OF_DIS.
// See LICENSE in the wfg directory for the full attribution.

#pragma once

#include <stdbool.h>
#include <stdint.h>

// Plain Vulkan types only: the renderer that includes this header calls Vulkan through its own
// table, and vk_dispatch.h would redirect its bare vkFoo names to WFG's.
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_DIS_MAX_GENERATIONS 7u

typedef struct VkrDis VkrDis;

typedef struct VkrDisContentRect {
    int32_t x;
    int32_t y;
    uint32_t width;
    uint32_t height;
} VkrDisContentRect;

// Resolves WFG's Vulkan entry points through the renderer's driver (the adrenotools driver or
// the system loader). Call once the instance exists, before vkr_dis_create.
bool vkr_dis_bind_dispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance);

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device);
void vkr_dis_destroy(VkrDis* dis);

// multiplier >= 2 fixes the output at that many frames per game frame (x2 = one generated
// frame); the target is then ignored. 0 derives the count from target_fps (0 = refresh rate).
void vkr_dis_configure(VkrDis* dis, uint32_t flow_min_side, uint32_t target_fps,
                       uint32_t multiplier, float refresh_rate);

bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height,
                           VkFormat format, VkrDisContentRect content);

bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content);

uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames);

// The game's smoothed frame interval as vkr_dis_plan measures it, in nanoseconds; 0 while the
// estimate is not settled yet. The renderer spaces the generated and real presents by it.
uint64_t vkr_dis_source_interval_ns(const VkrDis* dis);

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     uint32_t width, uint32_t height, uint32_t generations);

void vkr_dis_generate_into(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation,
                           VkImage target_image, uint32_t width, uint32_t height,
                           VkImage base_image);

// As vkr_dis_generate_into, with the frame's time between the previous game frame (0) and the
// current one (1) given by the caller: the renderer passes where the frame lands on the display,
// which differs from the even split when the output does not divide the refresh rate.
void vkr_dis_generate_into_at(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation, float t,
                              VkImage target_image, uint32_t width, uint32_t height,
                              VkImage base_image);

#ifdef __cplusplus
}
#endif
