
#pragma once

#include <array>

#include "aifg_common.hpp"

namespace aifg {

class AifgShaders;

constexpr size_t AIFG_ALPHA_STAGES = 4;

class AifgAlphaPasses {
public:
    AifgAlphaPasses() = default;
    AifgAlphaPasses(const Device& device, const AifgShaders& shaders);

    [[nodiscard]] const AifgPass& Get(size_t stage) const {
        return passes[stage];
    }

    [[nodiscard]] bool Valid() const;

private:
    std::array<AifgPass, AIFG_ALPHA_STAGES> passes;
};

class AifgAlpha {
public:
    AifgAlpha() = default;
    AifgAlpha(const Device& device, const AifgAlphaPasses& passes, AifgResources& resources,
              VkDescriptorPool descriptor_pool, AifgImage& input);

    void PushBarriers(AifgBarriers& barriers, uint64_t frame_count, size_t stage);
    void DispatchStage(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t stage);

    [[nodiscard]] AifgImageHistory& Outputs() {
        return out_images;
    }

    [[nodiscard]] bool Valid() const {
        return allocated;
    }

private:
    const AifgAlphaPasses* passes{};
    AifgImage* input{};

    std::array<VkDescriptorSet, AIFG_ALPHA_STAGES - 1> descriptor_sets{};
    std::array<VkDescriptorSet, AIFG_HISTORY_SLOTS> last_descriptor_sets{};

    AifgImage temp1;
    AifgImage temp2;
    AifgImagePair temp3;
    AifgImageHistory out_images;
    bool allocated{};
};

}
