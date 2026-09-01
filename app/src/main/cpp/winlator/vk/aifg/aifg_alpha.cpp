// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 aifg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#include "aifg_alpha.hpp"
#include "aifg_shaders.hpp"

#include <vector>

namespace aifg {

namespace {

constexpr uint32_t DISPATCH_TILE_SHIFT = 3;

[[nodiscard]] uint32_t GroupCount(uint32_t size) {
    return (size + (1u << DISPATCH_TILE_SHIFT) - 1) >> DISPATCH_TILE_SHIFT;
}

[[nodiscard]] VkExtent2D HalveExtent(VkExtent2D extent) {
    return VkExtent2D{
        (extent.width + 1) >> 1,
        (extent.height + 1) >> 1,
    };
}

}

AifgAlphaPasses::AifgAlphaPasses(const Device& device, const AifgShaders& shaders) {
    passes[0] = AifgPass(device, shaders, AIFG_ALPHA_SHADERS[0],
                         {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    passes[1] = AifgPass(device, shaders, AIFG_ALPHA_SHADERS[1],
                         {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    passes[2] = AifgPass(device, shaders, AIFG_ALPHA_SHADERS[2],
                         {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    passes[3] = AifgPass(device, shaders, AIFG_ALPHA_SHADERS[3],
                         {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
}

bool AifgAlphaPasses::Valid() const {
    for (const auto& pass : passes) {
        if (!pass.Valid()) return false;
    }
    return true;
}

AifgAlpha::AifgAlpha(const Device& device, const AifgAlphaPasses& passes_,
                     AifgResources& resources, VkDescriptorPool descriptor_pool, AifgImage& input_)
    : passes{&passes_}, input{&input_} {
    if (!passes->Valid()) return;

    const VkExtent2D half_extent = HalveExtent(input->Extent());
    const VkExtent2D quarter_extent = HalveExtent(half_extent);

    temp1 = AifgImage(device, half_extent);
    temp2 = AifgImage(device, half_extent);
    if (!temp1.Valid() || !temp2.Valid()) return;

    for (size_t i = 0; i < temp3.size(); ++i) {
        temp3[i] = AifgImage(device, quarter_extent);
        if (!temp3[i].Valid()) return;

        for (size_t j = 0; j < AIFG_HISTORY_SLOTS; ++j) {
            out_images[j][i] = AifgImage(device, quarter_extent);
            if (!out_images[j][i].Valid()) return;
        }
    }

    std::vector<VkDescriptorSetLayout> layouts;
    for (size_t i = 0; i < AIFG_ALPHA_STAGES - 1; ++i) {
        layouts.push_back(passes->Get(i).SetLayout());
    }
    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        layouts.push_back(passes->Get(3).SetLayout());
    }

    const std::vector<VkDescriptorSet> sets =
        AllocateAifgDescriptorSets(device, descriptor_pool, layouts);
    if (sets.size() != layouts.size()) return;

    for (size_t i = 0; i < AIFG_ALPHA_STAGES - 1; ++i) {
        descriptor_sets[i] = sets[i];
    }
    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        last_descriptor_sets[i] = sets[AIFG_ALPHA_STAGES - 1 + i];
    }

    const VkSampler sampler = resources.GetSampler();

    AifgDescriptorWriter(descriptor_sets[0])
        .AddSampler(sampler)
        .AddSampledImage(*input)
        .AddStorageImage(temp1)
        .Build(device);
    AifgDescriptorWriter(descriptor_sets[1])
        .AddSampler(sampler)
        .AddSampledImage(temp1)
        .AddStorageImage(temp2)
        .Build(device);
    AifgDescriptorWriter(descriptor_sets[2])
        .AddSampler(sampler)
        .AddSampledImage(temp2)
        .AddStorageImages(temp3)
        .Build(device);
    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        AifgDescriptorWriter(last_descriptor_sets[i])
            .AddSampler(sampler)
            .AddSampledImages(temp3)
            .AddStorageImages(out_images[i])
            .Build(device);
    }
    allocated = true;
}

void AifgAlpha::PushBarriers(AifgBarriers& barriers, uint64_t frame_count, size_t stage) {
    switch (stage) {
    case 0:
        barriers.WriteToRead(*input).ReadToWrite(temp1);
        break;
    case 1:
        barriers.WriteToRead(temp1).ReadToWrite(temp2);
        break;
    case 2:
        barriers.WriteToRead(temp2).ReadToWriteAll(temp3);
        break;
    default:
        barriers.WriteToReadAll(temp3).ReadToWriteAll(out_images[frame_count % AIFG_HISTORY_SLOTS]);
        break;
    }
}

void AifgAlpha::DispatchStage(VkCommandBuffer cmdbuf, uint64_t frame_count, size_t stage) {
    const VkExtent2D extent = stage < 2 ? temp1.Extent() : temp3[0].Extent();
    const VkDescriptorSet set = stage < AIFG_ALPHA_STAGES - 1
                                    ? descriptor_sets[stage]
                                    : last_descriptor_sets[frame_count % AIFG_HISTORY_SLOTS];

    passes->Get(stage).BindSet(cmdbuf, set);
    vkd.CmdDispatch(cmdbuf, GroupCount(extent.width), GroupCount(extent.height), 1);
}

}
