// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "aifg_alpha.hpp"
#include "aifg_beta.hpp"
#include "aifg_common.hpp"
#include "aifg_delta.hpp"
#include "aifg_gamma.hpp"
#include "aifg_generate.hpp"
#include "aifg_mipmaps.hpp"

namespace aifg {

class AifgShaders;

constexpr size_t AIFG_FIRST_DELTA_LEVEL = 4;
constexpr size_t AIFG_LAST_DELTA_LEVEL = AIFG_MIP_LEVELS - 1;
constexpr size_t AIFG_DELTA_INSTANCES = AIFG_LAST_DELTA_LEVEL + 1 - AIFG_FIRST_DELTA_LEVEL;

class AifgChain {
public:
    AifgChain(const Device& device, const AifgShaders& shaders, VkExtent2D extent, VkFormat format,
              float flow_scale);
    ~AifgChain();

    AifgChain(const AifgChain&) = delete;
    AifgChain& operator=(const AifgChain&) = delete;

    void DispatchShared(VkCommandBuffer cmdbuf, uint64_t frame_count);

    void DispatchGeneration(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t generation_count,
                            size_t generation, uint32_t target, VkImage image, VkExtent2D extent);

    void SetTarget(const Device& device, size_t generation_count, size_t generation,
                   uint32_t target, VkImageView view) {
        generate.SetTarget(device, AifgGenerationSlot(generation_count, generation), target, view);
    }

    void ForgetTargets() {
        generate.ForgetTargets();
    }

    [[nodiscard]] AifgImage& Input(uint64_t frame_count) {
        return frames[frame_count % frames.size()];
    }

    [[nodiscard]] AifgImage& FlowLevel(size_t level) {
        return mipmaps.Output(level);
    }

    [[nodiscard]] bool Valid() const {
        return valid;
    }

private:
    AifgResources resources;
    VkDevice owner{VK_NULL_HANDLE};
    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};

    AifgImagePair frames;
    AifgMipmaps mipmaps;
    AifgAlphaPasses alpha_passes;
    std::array<AifgAlpha, AIFG_MIP_LEVELS> alpha;
    AifgBeta beta;
    std::array<AifgGamma, AIFG_MIP_LEVELS> gamma;
    std::array<AifgDelta, AIFG_DELTA_INSTANCES> delta;
    AifgGenerate generate;
    bool valid{};
};

}
