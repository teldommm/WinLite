// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "lsfg_alpha.hpp"
#include "lsfg_beta.hpp"
#include "lsfg_common.hpp"
#include "lsfg_delta.hpp"
#include "lsfg_gamma.hpp"
#include "lsfg_generate.hpp"
#include "lsfg_mipmaps.hpp"

namespace lsfg {

class LsfgShaders;

constexpr size_t LSFG_FIRST_DELTA_LEVEL = 4;
constexpr size_t LSFG_LAST_DELTA_LEVEL = LSFG_MIP_LEVELS - 1;
constexpr size_t LSFG_DELTA_INSTANCES = LSFG_LAST_DELTA_LEVEL + 1 - LSFG_FIRST_DELTA_LEVEL;

// Every compute pipeline of the chain, one per stage (26 for the 25 shaders: gamma and delta
// both use shader 280, each with its own layout object). None of them depends on the extent,
// the format or the flow scale - images and descriptor sets do, and storage images are written
// without a format - so the engine builds them once and every chain it builds, and every pyramid
// level of a chain, binds the same ones. Built per chain and per level (gamma x7, delta x3) they
// were 76 pipelines, compiled without a pipeline cache on every chain build: arming frame
// generation, a flow scale change, a swapchain or surface change.
class LsfgPasses {
public:
    LsfgPasses(const Device& device, const LsfgShaders& shaders);

    LsfgPasses(const LsfgPasses&) = delete;
    LsfgPasses& operator=(const LsfgPasses&) = delete;

    [[nodiscard]] bool Valid() const;

    LsfgPass mipmaps;
    LsfgAlphaPasses alpha;
    std::array<LsfgPass, LSFG_BETA_STAGES> beta;
    std::array<LsfgPass, LSFG_GAMMA_STAGES> gamma;
    std::array<LsfgPass, LSFG_DELTA_STAGES> delta;
    LsfgPass generate;
};

class LsfgChain {
public:
    // passes must outlive the chain (the engine owns both and drops the chain first).
    LsfgChain(const Device& device, const LsfgPasses& passes, VkExtent2D extent, VkFormat format,
              float flow_scale);
    ~LsfgChain();

    LsfgChain(const LsfgChain&) = delete;
    LsfgChain& operator=(const LsfgChain&) = delete;

    void DispatchShared(VkCommandBuffer cmdbuf, uint64_t frame_count);

    void DispatchGeneration(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t generation_count,
                            size_t generation, uint32_t target, VkImage image, VkExtent2D extent);

    void SetTarget(const Device& device, size_t generation_count, size_t generation,
                   uint32_t target, VkImageView view) {
        generate.SetTarget(device, LsfgGenerationSlot(generation_count, generation), target, view);
    }

    void ForgetTargets() {
        generate.ForgetTargets();
    }

    void ResetHistory() {
        for (auto& a : alpha) {
            a.ResetHistory();
        }
    }

    [[nodiscard]] LsfgImage& Input(uint64_t frame_count) {
        return frames[frame_count % frames.size()];
    }

    [[nodiscard]] LsfgImage& FlowLevel(size_t level) {
        return mipmaps.Output(level);
    }

    [[nodiscard]] bool Valid() const {
        return valid;
    }

private:
    LsfgResources resources;
    VkDevice owner{VK_NULL_HANDLE};
    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};

    LsfgImagePair frames;
    LsfgMipmaps mipmaps;
    const LsfgAlphaPasses* alpha_passes{};
    std::array<LsfgAlpha, LSFG_MIP_LEVELS> alpha;
    LsfgBeta beta;
    std::array<LsfgGamma, LSFG_MIP_LEVELS> gamma;
    std::array<LsfgDelta, LSFG_DELTA_INSTANCES> delta;
    LsfgGenerate generate;
    bool valid{};
};

}
