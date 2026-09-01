// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

class AifgMipmaps {
public:
    AifgMipmaps() = default;
    AifgMipmaps(const Device& device, const AifgShaders& shaders, AifgResources& resources,
                VkDescriptorPool descriptor_pool, AifgImagePair& frames, float flow_scale);

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count);

    [[nodiscard]] AifgImage& Output(size_t level) {
        return out_images[level];
    }

    [[nodiscard]] VkExtent2D FlowExtent() const {
        return flow_extent;
    }

    [[nodiscard]] bool Valid() const {
        return pass.Valid() && descriptor_sets[0] != VK_NULL_HANDLE;
    }

private:
    AifgImagePair* frames{};

    AifgPass pass;
    std::array<VkDescriptorSet, 2> descriptor_sets{};

    VkExtent2D flow_extent{};
    std::array<AifgImage, AIFG_MIP_LEVELS> out_images;
};

}
