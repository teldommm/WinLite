// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "lsfg_common.hpp"

namespace lsfg {

class LsfgShaders;

constexpr size_t LSFG_BETA_STAGES = 5;
constexpr size_t LSFG_BETA_OUTPUTS = 6;

class LsfgBeta {
public:
    LsfgBeta() = default;
    // passes: the engine's beta pipelines (LsfgPasses), shared by every chain it builds.
    LsfgBeta(const Device& device, const std::array<LsfgPass, LSFG_BETA_STAGES>& passes,
             LsfgResources& resources, VkDescriptorPool descriptor_pool,
             LsfgImageHistory& inputs);

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count);

    [[nodiscard]] LsfgImage& Output(size_t level) {
        return out_images[level];
    }

    [[nodiscard]] bool Valid() const {
        return allocated;
    }

private:
    LsfgImageHistory* inputs{};

    const std::array<LsfgPass, LSFG_BETA_STAGES>* passes{};
    std::array<VkDescriptorSet, LSFG_HISTORY_SLOTS> first_descriptor_sets{};
    std::array<VkDescriptorSet, LSFG_BETA_STAGES - 1> descriptor_sets{};

    LsfgImagePair temp1;
    LsfgImagePair temp2;
    std::array<LsfgImage, LSFG_BETA_OUTPUTS> out_images;
    bool allocated{};
};

}
