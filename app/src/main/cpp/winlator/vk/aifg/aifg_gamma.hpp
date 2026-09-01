// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

constexpr size_t AIFG_GAMMA_STAGES = 5;
constexpr size_t AIFG_GAMMA_TEMPS = 3;

class AifgGamma {
public:
    AifgGamma() = default;
    AifgGamma(const Device& device, const AifgShaders& shaders, AifgResources& resources,
              VkDescriptorPool descriptor_pool, AifgImageHistory& inputs, AifgImage& flow_input,
              AifgImage* previous);

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t slot);

    void PushStepBarriers(AifgBarriers& barriers, uint64_t frame_count, size_t step);

    void DispatchStep(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t slot,
                      size_t step);

    [[nodiscard]] AifgImage& Output() {
        return out_image;
    }

    [[nodiscard]] bool Valid() const {
        return allocated;
    }

private:
    struct Generation {
        std::array<VkDescriptorSet, AIFG_HISTORY_SLOTS> first_descriptor_sets{};
        std::array<VkDescriptorSet, AIFG_GAMMA_STAGES - 1> descriptor_sets{};
    };

    AifgImageHistory* inputs{};
    AifgImage* flow_input{};
    AifgImage* previous{};

    std::array<AifgPass, AIFG_GAMMA_STAGES> passes;
    std::array<Generation, AIFG_GENERATION_SLOTS> generations{};

    std::array<AifgImage, AIFG_GAMMA_TEMPS> temp1;
    AifgImagePair temp2;
    AifgImage out_image;
    bool allocated{};
};

}
