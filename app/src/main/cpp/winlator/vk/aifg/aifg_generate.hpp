// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

class AifgGenerate {
public:
    AifgGenerate() = default;
    AifgGenerate(const Device& device, const AifgShaders& shaders, AifgResources& resources,
                 VkDescriptorPool descriptor_pool, AifgImagePair& frames, AifgImage& motion,
                 AifgImage& detail1, AifgImage& detail2);

    void SetTarget(const Device& device, size_t slot, uint32_t target, VkImageView view);

    void ForgetTargets();

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t slot, uint32_t target,
                  VkImage image, VkExtent2D extent);

    [[nodiscard]] bool Valid() const {
        return pass.Valid() && allocated;
    }

private:
    struct Target {
        std::array<VkDescriptorSet, 2> descriptor_sets{};
        VkImageView view{VK_NULL_HANDLE};
    };

    struct Generation {
        std::array<Target, AIFG_MAX_TARGETS> targets{};
        VkBuffer buffer{VK_NULL_HANDLE};
    };

    AifgImagePair* frames{};
    AifgImage* motion{};
    AifgImage* detail1{};
    AifgImage* detail2{};
    VkSampler sampler{VK_NULL_HANDLE};
    VkSampler edge_sampler{VK_NULL_HANDLE};

    AifgPass pass;
    std::array<Generation, AIFG_GENERATION_SLOTS> generations{};
    bool allocated{};
};

}
