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

// double_frames selects x2 (one generated frame per game frame). Otherwise the count is
// derived from target_fps (0 = refresh rate).
// fast_interp: the Fast preset's interpolation - a bilinear warp instead of Catmull-Rom and one
// step to the source point instead of two; cheaper per output pixel, a little softer. It applies
// from the next generated frame, without a rebuild.
void vkr_dis_configure(VkrDis* dis, uint32_t flow_min_side, bool fast_interp, uint32_t target_fps,
                       bool double_frames, float refresh_rate);

// base_view: a view of the composite (the image vkr_dis_process reads, in GENERAL), sampled by
// the interpolation outside the content rect. A different view means a rebuild: wait for the
// device first, as for any other change these report.
bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height,
                           VkFormat format, VkrDisContentRect content, VkImageView base_view);

bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content, VkImageView base_view);

// arrival_ns is the monotonic timestamp of the game frame being rendered.
uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames, uint64_t arrival_ns);

// After vkr_dis_plan: it generates nothing for this frame and does not need the frame either (the
// game already reaches the target, or there are no spare images).
// The renderer may then draw the frame straight to the swapchain and skip vkr_dis_process, calling
// vkr_dis_skip_frame instead. The plan keeps running on every new game frame; when it wants
// frames again, its first answer is 0 (and not idle) while it takes one frame in.
bool vkr_dis_idle(const VkrDis* dis);
void vkr_dis_skip_frame(VkrDis* dis);

// The game's smoothed frame interval as vkr_dis_plan measures it, in nanoseconds; 0 while the
// estimate is not settled yet. The renderer spaces the generated and real presents by it.
uint64_t vkr_dis_source_interval_ns(const VkrDis* dis);

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     uint32_t width, uint32_t height, uint32_t generations);

// Writes generated frame `generation` into target_fb in one fragment pass. t is its time
// between the previous game frame (0) and the current one (1). An unset time uses an even split.
// target_fb must be of a render pass compatible with WFG's: one attachment of the
// format given to vkr_dis_prepare, one sample, one subpass and the single dependency
// EXTERNAL -> 0, COLOR_ATTACHMENT_OUTPUT (no access) -> COLOR_ATTACHMENT_OUTPUT
// (COLOR_ATTACHMENT_WRITE) - the renderer's swapchain pass. The image needs
// no layout beforehand and is left in PRESENT_SRC_KHR; it must have been acquired with a wait at
// VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT. Called outside a render pass.
// Returns false, recording nothing, when there is no such generated frame (no pair processed with
// that many, or the engine is unusable): the image is then untouched and still the caller's.
bool vkr_dis_generate_into_at(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation, float t,
                              VkFramebuffer target_fb, uint32_t width, uint32_t height);

#ifdef __cplusplus
}
#endif
