// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "lsfg_chain.hpp"
#include "lsfg_dll.h"
#include "lsfg_shaders.hpp"

#include <algorithm>

namespace lsfg {

namespace {

constexpr uint32_t FIXED_DESCRIPTOR_SETS = 64;
constexpr uint32_t DESCRIPTOR_SETS_PER_SLOT = 112;
[[nodiscard]] constexpr bool HasDelta(size_t i) {
    return i >= LSFG_FIRST_DELTA_LEVEL && i <= LSFG_LAST_DELTA_LEVEL;
}

}

LsfgPasses::LsfgPasses(const Device& device, const LsfgShaders& shaders)
    : alpha{device, shaders} {
    // Bindings exactly as each stage declared them when it built its own passes.
    mipmaps = LsfgPass(device, shaders, LSFG_SHADER_MIPMAPS,
                       {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                        {1, VK_DESCRIPTOR_TYPE_SAMPLER},
                        {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                        {LSFG_MIP_LEVELS, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

    beta[0] = LsfgPass(device, shaders, LSFG_BETA_SHADERS[0],
                       {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                        {6, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                        {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    for (size_t i = 1; i < LSFG_BETA_STAGES - 1; ++i) {
        beta[i] = LsfgPass(device, shaders, LSFG_BETA_SHADERS[i],
                           {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                            {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                            {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    }
    beta[4] = LsfgPass(device, shaders, LSFG_BETA_SHADERS[4],
                       {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                        {1, VK_DESCRIPTOR_TYPE_SAMPLER},
                        {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                        {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

    gamma[0] = LsfgPass(device, shaders, LSFG_GAMMA_SHADERS[0],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    gamma[1] = LsfgPass(device, shaders, LSFG_GAMMA_SHADERS[1],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    gamma[2] = LsfgPass(device, shaders, LSFG_GAMMA_SHADERS[2],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    gamma[3] = LsfgPass(device, shaders, LSFG_GAMMA_SHADERS[3],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    gamma[4] = LsfgPass(device, shaders, LSFG_GAMMA_SHADERS[4],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

    delta[0] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[0],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    delta[1] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[1],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    delta[2] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[2],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    delta[3] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[3],
                        {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    delta[4] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[4],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    delta[5] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[5],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {6, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    for (size_t i = 6; i < LSFG_DELTA_STAGES - 1; ++i) {
        delta[i] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[i],
                            {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                             {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                             {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    }
    delta[9] = LsfgPass(device, shaders, LSFG_DELTA_SHADERS[9],
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

    generate = LsfgPass(device, shaders, LSFG_SHADER_GENERATE,
                        {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                         {2, VK_DESCRIPTOR_TYPE_SAMPLER},
                         {5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                         {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
}

bool LsfgPasses::Valid() const {
    if (!mipmaps.Valid() || !alpha.Valid() || !generate.Valid()) return false;
    for (const auto& pass : beta) if (!pass.Valid()) return false;
    for (const auto& pass : gamma) if (!pass.Valid()) return false;
    for (const auto& pass : delta) if (!pass.Valid()) return false;
    return true;
}

LsfgChain::LsfgChain(const Device& device, const LsfgPasses& passes, VkExtent2D extent,
                     VkFormat format, float flow_scale)
    : resources{device, flow_scale}, owner{device.Handle()} {
    descriptor_pool = CreateLsfgDescriptorPool(
        device, FIXED_DESCRIPTOR_SETS +
                    DESCRIPTOR_SETS_PER_SLOT * static_cast<uint32_t>(LSFG_GENERATION_SLOTS));
    if (descriptor_pool == VK_NULL_HANDLE) return;

    for (auto& image : frames) {
        image = LsfgImage(device, extent, format);
        if (!image.Valid()) return;
    }

    mipmaps = LsfgMipmaps(device, passes.mipmaps, resources, descriptor_pool, frames, flow_scale);
    if (!mipmaps.Valid()) return;

    alpha_passes = &passes.alpha;
    if (!alpha_passes->Valid()) return;

    for (size_t i = 0; i < LSFG_MIP_LEVELS; ++i) {
        alpha[i] = LsfgAlpha(device, *alpha_passes, resources, descriptor_pool, mipmaps.Output(i));
        if (!alpha[i].Valid()) return;
    }

    beta = LsfgBeta(device, passes.beta, resources, descriptor_pool, alpha[0].Outputs());
    if (!beta.Valid()) return;

    for (size_t i = 0; i < LSFG_MIP_LEVELS; ++i) {
        const size_t level = LSFG_MIP_LEVELS - 1 - i;
        gamma[i] = LsfgGamma(device, passes.gamma, resources, descriptor_pool, alpha[level].Outputs(),
                             beta.Output(std::min(level, LSFG_BETA_OUTPUTS - 1)),
                             i == 0 ? nullptr : &gamma[i - 1].Output());
        if (!gamma[i].Valid()) return;

        if (!HasDelta(i)) {
            continue;
        }

        const size_t index = i - LSFG_FIRST_DELTA_LEVEL;
        const bool first = i == LSFG_FIRST_DELTA_LEVEL;
        delta[index] = LsfgDelta(device, passes.delta, resources, descriptor_pool,
                                 alpha[level].Outputs(), beta.Output(level),
                                 first ? nullptr : &gamma[i - 1].Output(),
                                 first ? nullptr : &delta[index - 1].Output1(),
                                 first ? nullptr : &delta[index - 1].Output2());
        if (!delta[index].Valid()) return;
    }

    generate = LsfgGenerate(device, passes.generate, resources, descriptor_pool, frames,
                            gamma[LSFG_MIP_LEVELS - 1].Output(),
                            delta[LSFG_DELTA_INSTANCES - 1].Output1(),
                            delta[LSFG_DELTA_INSTANCES - 1].Output2());
    if (!generate.Valid()) return;

    valid = true;
}

LsfgChain::~LsfgChain() {
    if (descriptor_pool != VK_NULL_HANDLE) {
        vkd.DestroyDescriptorPool(owner, descriptor_pool, nullptr);
        descriptor_pool = VK_NULL_HANDLE;
    }
}

void LsfgChain::DispatchShared(VkCommandBuffer cmdbuf, uint64_t frame_count) {
    resources.PrepareDummies(cmdbuf);

    mipmaps.Dispatch(cmdbuf, frame_count);

    for (size_t stage = 0; stage < LSFG_ALPHA_STAGES; ++stage) {
        LsfgBarriers barriers(cmdbuf);
        for (auto& level : alpha) {
            level.PushBarriers(barriers, frame_count, stage);
        }
        barriers.Build();

        alpha_passes->Get(stage).BindPipeline(cmdbuf);
        for (auto& level : alpha) {
            level.DispatchStage(cmdbuf, frame_count, stage);
        }
    }

    beta.Dispatch(cmdbuf, frame_count);
}

void LsfgChain::DispatchGeneration(VkCommandBuffer cmdbuf, uint64_t frame_count,
                                   size_t generation_count, size_t generation, uint32_t target,
                                   VkImage image, VkExtent2D extent) {
    const size_t slot = LsfgGenerationSlot(generation_count, generation);
    constexpr size_t PAIRED_STEPS =
        LSFG_GAMMA_STAGES > LSFG_DELTA_STAGES ? LSFG_GAMMA_STAGES : LSFG_DELTA_STAGES;

    for (size_t i = 0; i < LSFG_MIP_LEVELS; ++i) {
        if (!HasDelta(i)) {
            gamma[i].Dispatch(cmdbuf, frame_count, slot);
            continue;
        }

        LsfgDelta& paired = delta[i - LSFG_FIRST_DELTA_LEVEL];
        for (size_t step = 0; step < PAIRED_STEPS; ++step) {
            LsfgBarriers barriers(cmdbuf);
            if (step < LSFG_GAMMA_STAGES) {
                gamma[i].PushStepBarriers(barriers, frame_count, step);
            }
            if (step < LSFG_DELTA_STAGES) {
                paired.PushStepBarriers(barriers, frame_count, step);
            }
            barriers.Build();

            if (step < LSFG_GAMMA_STAGES) {
                gamma[i].DispatchStep(cmdbuf, frame_count, slot, step);
            }
            if (step < LSFG_DELTA_STAGES) {
                paired.DispatchStep(cmdbuf, frame_count, slot, step);
            }
        }
    }
    generate.Dispatch(cmdbuf, frame_count, slot, target, image, extent);
}

}
