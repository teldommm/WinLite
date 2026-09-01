// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#include "aifg_chain.hpp"
#include "aifg_shaders.hpp"

#include <algorithm>

namespace aifg {

namespace {

constexpr uint32_t FIXED_DESCRIPTOR_SETS = 64;
constexpr uint32_t DESCRIPTOR_SETS_PER_SLOT = 112;
[[nodiscard]] constexpr bool HasDelta(size_t i) {
    return i >= AIFG_FIRST_DELTA_LEVEL && i <= AIFG_LAST_DELTA_LEVEL;
}

}

AifgChain::AifgChain(const Device& device, const AifgShaders& shaders, VkExtent2D extent,
                     VkFormat format, float flow_scale)
    : resources{device, flow_scale}, owner{device.Handle()} {
    descriptor_pool = CreateAifgDescriptorPool(
        device, FIXED_DESCRIPTOR_SETS +
                    DESCRIPTOR_SETS_PER_SLOT * static_cast<uint32_t>(AIFG_GENERATION_SLOTS));
    if (descriptor_pool == VK_NULL_HANDLE) return;

    for (auto& image : frames) {
        image = AifgImage(device, extent, format);
        if (!image.Valid()) return;
    }

    mipmaps = AifgMipmaps(device, shaders, resources, descriptor_pool, frames, flow_scale);
    if (!mipmaps.Valid()) return;

    alpha_passes = AifgAlphaPasses(device, shaders);
    if (!alpha_passes.Valid()) return;

    for (size_t i = 0; i < AIFG_MIP_LEVELS; ++i) {
        alpha[i] = AifgAlpha(device, alpha_passes, resources, descriptor_pool, mipmaps.Output(i));
        if (!alpha[i].Valid()) return;
    }

    beta = AifgBeta(device, shaders, resources, descriptor_pool, alpha[0].Outputs());
    if (!beta.Valid()) return;

    for (size_t i = 0; i < AIFG_MIP_LEVELS; ++i) {
        const size_t level = AIFG_MIP_LEVELS - 1 - i;
        gamma[i] = AifgGamma(device, shaders, resources, descriptor_pool, alpha[level].Outputs(),
                             beta.Output(std::min(level, AIFG_BETA_OUTPUTS - 1)),
                             i == 0 ? nullptr : &gamma[i - 1].Output());
        if (!gamma[i].Valid()) return;

        if (!HasDelta(i)) {
            continue;
        }

        const size_t index = i - AIFG_FIRST_DELTA_LEVEL;
        const bool first = i == AIFG_FIRST_DELTA_LEVEL;
        delta[index] = AifgDelta(device, shaders, resources, descriptor_pool,
                                 alpha[level].Outputs(), beta.Output(level),
                                 first ? nullptr : &gamma[i - 1].Output(),
                                 first ? nullptr : &delta[index - 1].Output1(),
                                 first ? nullptr : &delta[index - 1].Output2());
        if (!delta[index].Valid()) return;
    }

    generate = AifgGenerate(device, shaders, resources, descriptor_pool, frames,
                            gamma[AIFG_MIP_LEVELS - 1].Output(),
                            delta[AIFG_DELTA_INSTANCES - 1].Output1(),
                            delta[AIFG_DELTA_INSTANCES - 1].Output2());
    if (!generate.Valid()) return;

    valid = true;
}

AifgChain::~AifgChain() {
    if (descriptor_pool != VK_NULL_HANDLE) {
        vkd.DestroyDescriptorPool(owner, descriptor_pool, nullptr);
        descriptor_pool = VK_NULL_HANDLE;
    }
}

void AifgChain::DispatchShared(VkCommandBuffer cmdbuf, uint64_t frame_count) {
    resources.PrepareDummies(cmdbuf);

    mipmaps.Dispatch(cmdbuf, frame_count);

    for (size_t stage = 0; stage < AIFG_ALPHA_STAGES; ++stage) {
        AifgBarriers barriers(cmdbuf);
        for (auto& level : alpha) {
            level.PushBarriers(barriers, frame_count, stage);
        }
        barriers.Build();

        alpha_passes.Get(stage).BindPipeline(cmdbuf);
        for (auto& level : alpha) {
            level.DispatchStage(cmdbuf, frame_count, stage);
        }
    }

    beta.Dispatch(cmdbuf, frame_count);
}

void AifgChain::DispatchGeneration(VkCommandBuffer cmdbuf, uint64_t frame_count,
                                   size_t generation_count, size_t generation, uint32_t target,
                                   VkImage image, VkExtent2D extent) {
    const size_t slot = AifgGenerationSlot(generation_count, generation);
    constexpr size_t PAIRED_STEPS =
        AIFG_GAMMA_STAGES > AIFG_DELTA_STAGES ? AIFG_GAMMA_STAGES : AIFG_DELTA_STAGES;

    for (size_t i = 0; i < AIFG_MIP_LEVELS; ++i) {
        if (!HasDelta(i)) {
            gamma[i].Dispatch(cmdbuf, frame_count, slot);
            continue;
        }

        AifgDelta& paired = delta[i - AIFG_FIRST_DELTA_LEVEL];
        for (size_t step = 0; step < PAIRED_STEPS; ++step) {
            AifgBarriers barriers(cmdbuf);
            if (step < AIFG_GAMMA_STAGES) {
                gamma[i].PushStepBarriers(barriers, frame_count, step);
            }
            if (step < AIFG_DELTA_STAGES) {
                paired.PushStepBarriers(barriers, frame_count, step);
            }
            barriers.Build();

            if (step < AIFG_GAMMA_STAGES) {
                gamma[i].DispatchStep(cmdbuf, frame_count, slot, step);
            }
            if (step < AIFG_DELTA_STAGES) {
                paired.DispatchStep(cmdbuf, frame_count, slot, step);
            }
        }
    }
    generate.Dispatch(cmdbuf, frame_count, slot, target, image, extent);
}

}
