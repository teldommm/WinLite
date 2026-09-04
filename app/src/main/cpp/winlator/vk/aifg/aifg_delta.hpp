
#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

constexpr size_t AIFG_DELTA_STAGES = 10;
constexpr size_t AIFG_DELTA_TEMPS = 3;

class AifgDelta {
public:
    AifgDelta() = default;
    AifgDelta(const Device& device, const AifgShaders& shaders, AifgResources& resources,
              VkDescriptorPool descriptor_pool, AifgImageHistory& inputs, AifgImage& flow_input,
              AifgImage* previous_gamma, AifgImage* previous1, AifgImage* previous2);

    void Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t slot);

    void PushStepBarriers(AifgBarriers& barriers, uint64_t frame_count, size_t step);

    void DispatchStep(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t slot,
                      size_t step);

    [[nodiscard]] AifgImage& Output1() {
        return out_image1;
    }

    [[nodiscard]] AifgImage& Output2() {
        return out_image2;
    }

    [[nodiscard]] bool Valid() const {
        return allocated;
    }

private:
    struct Generation {
        std::array<VkDescriptorSet, AIFG_HISTORY_SLOTS> first_descriptor_sets{};
        std::array<VkDescriptorSet, AIFG_HISTORY_SLOTS> sixth_descriptor_sets{};
        std::array<VkDescriptorSet, AIFG_DELTA_STAGES - 2> descriptor_sets{};
    };

    AifgImageHistory* inputs{};
    AifgImage* flow_input{};
    AifgImage* previous_gamma{};
    AifgImage* previous1{};
    AifgImage* previous2{};

    std::array<AifgPass, AIFG_DELTA_STAGES> passes;
    std::array<Generation, AIFG_GENERATION_SLOTS> generations{};

    std::array<AifgImage, AIFG_DELTA_TEMPS> temp1;
    AifgImagePair temp2;
    AifgImage out_image1;
    AifgImage out_image2;
    bool allocated{};
};

}
