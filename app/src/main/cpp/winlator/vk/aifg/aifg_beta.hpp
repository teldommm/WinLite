
#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

constexpr size_t AIFG_BETA_STAGES = 5;
constexpr size_t AIFG_BETA_OUTPUTS = 6;

class AifgBeta {
public:
    AifgBeta() = default;
    AifgBeta(const Device& device, const AifgShaders& shaders, AifgResources& resources,
             VkDescriptorPool descriptor_pool, AifgImageHistory& inputs);

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count);

    [[nodiscard]] AifgImage& Output(size_t level) {
        return out_images[level];
    }

    [[nodiscard]] bool Valid() const {
        return allocated;
    }

private:
    AifgImageHistory* inputs{};

    std::array<AifgPass, AIFG_BETA_STAGES> passes;
    std::array<VkDescriptorSet, AIFG_HISTORY_SLOTS> first_descriptor_sets{};
    std::array<VkDescriptorSet, AIFG_BETA_STAGES - 1> descriptor_sets{};

    AifgImagePair temp1;
    AifgImagePair temp2;
    std::array<AifgImage, AIFG_BETA_OUTPUTS> out_images;
    bool allocated{};
};

}
