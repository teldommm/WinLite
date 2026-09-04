
#include "aifg_beta.hpp"
#include "aifg_shaders.hpp"

#include <vector>

namespace aifg {

namespace {

constexpr uint32_t DISPATCH_TILE_SHIFT = 3;
constexpr uint32_t OUTPUT_TILE_SHIFT = 5;

[[nodiscard]] uint32_t GroupCount(uint32_t size, uint32_t shift) {
    return (size + (1u << shift) - 1) >> shift;
}

}

AifgBeta::AifgBeta(const Device& device, const AifgShaders& shaders, AifgResources& resources,
                   VkDescriptorPool descriptor_pool, AifgImageHistory& inputs_)
    : inputs{&inputs_} {
    passes[0] = AifgPass(device, shaders, AIFG_BETA_SHADERS[0],
                         {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {6, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    for (size_t i = 1; i < AIFG_BETA_STAGES - 1; ++i) {
        passes[i] = AifgPass(device, shaders, AIFG_BETA_SHADERS[i],
                             {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                              {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                              {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    }
    passes[4] = AifgPass(device, shaders, AIFG_BETA_SHADERS[4],
                         {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                          {1, VK_DESCRIPTOR_TYPE_SAMPLER},
                          {2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                          {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    for (const auto& pass : passes) {
        if (!pass.Valid()) return;
    }

    const VkExtent2D extent = (*inputs)[0][0].Extent();
    for (size_t i = 0; i < temp1.size(); ++i) {
        temp1[i] = AifgImage(device, extent);
        temp2[i] = AifgImage(device, extent);
        if (!temp1[i].Valid() || !temp2[i].Valid()) return;
    }
    for (size_t i = 0; i < AIFG_BETA_OUTPUTS; ++i) {
        const VkExtent2D level_extent{
            extent.width >> i,
            extent.height >> i,
        };
        out_images[i] = AifgImage(device, level_extent, AIFG_FLOW_FORMAT);
        if (!out_images[i].Valid()) return;
    }

    std::vector<VkDescriptorSetLayout> layouts;
    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        layouts.push_back(passes[0].SetLayout());
    }
    for (size_t i = 1; i < AIFG_BETA_STAGES; ++i) {
        layouts.push_back(passes[i].SetLayout());
    }

    const std::vector<VkDescriptorSet> sets =
        AllocateAifgDescriptorSets(device, descriptor_pool, layouts);
    if (sets.size() != layouts.size()) return;

    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        first_descriptor_sets[i] = sets[i];
    }
    for (size_t i = 0; i < AIFG_BETA_STAGES - 1; ++i) {
        descriptor_sets[i] = sets[AIFG_HISTORY_SLOTS + i];
    }

    const VkSampler sampler = resources.GetSampler();
    const VkSampler border_sampler =
        resources.GetSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_COMPARE_OP_NEVER, true);

    for (size_t i = 0; i < AIFG_HISTORY_SLOTS; ++i) {
        AifgDescriptorWriter(first_descriptor_sets[i])
            .AddSampler(border_sampler)
            .AddSampledImages((*inputs)[(i + 1) % AIFG_HISTORY_SLOTS])
            .AddSampledImages((*inputs)[(i + 2) % AIFG_HISTORY_SLOTS])
            .AddSampledImages((*inputs)[i % AIFG_HISTORY_SLOTS])
            .AddStorageImages(temp1)
            .Build(device);
    }
    AifgDescriptorWriter(descriptor_sets[0])
        .AddSampler(sampler)
        .AddSampledImages(temp1)
        .AddStorageImages(temp2)
        .Build(device);
    AifgDescriptorWriter(descriptor_sets[1])
        .AddSampler(sampler)
        .AddSampledImages(temp2)
        .AddStorageImages(temp1)
        .Build(device);
    AifgDescriptorWriter(descriptor_sets[2])
        .AddSampler(sampler)
        .AddSampledImages(temp1)
        .AddStorageImages(temp2)
        .Build(device);
    AifgDescriptorWriter(descriptor_sets[3])
        .AddUniformBuffer(resources.GetBuffer(0.5f), AifgResources::BufferSize())
        .AddSampler(sampler)
        .AddSampledImages(temp2)
        .AddStorageImages(out_images)
        .Build(device);
    allocated = true;
}

void AifgBeta::Dispatch(VkCommandBuffer cmdbuf, uint64_t frame_count) {
    const VkExtent2D extent = temp1[0].Extent();
    const uint32_t groups_x = GroupCount(extent.width, DISPATCH_TILE_SHIFT);
    const uint32_t groups_y = GroupCount(extent.height, DISPATCH_TILE_SHIFT);

    AifgBarriers barriers(cmdbuf);
    for (auto& slot : *inputs) {
        barriers.WriteToReadAll(slot);
    }
    barriers.ReadToWriteAll(temp1).Build();

    passes[0].Bind(cmdbuf, first_descriptor_sets[frame_count % AIFG_HISTORY_SLOTS]);
    vkd.CmdDispatch(cmdbuf, groups_x, groups_y, 1);

    AifgBarriers(cmdbuf).WriteToReadAll(temp1).ReadToWriteAll(temp2).Build();
    passes[1].Bind(cmdbuf, descriptor_sets[0]);
    vkd.CmdDispatch(cmdbuf, groups_x, groups_y, 1);

    AifgBarriers(cmdbuf).WriteToReadAll(temp2).ReadToWriteAll(temp1).Build();
    passes[2].Bind(cmdbuf, descriptor_sets[1]);
    vkd.CmdDispatch(cmdbuf, groups_x, groups_y, 1);

    AifgBarriers(cmdbuf).WriteToReadAll(temp1).ReadToWriteAll(temp2).Build();
    passes[3].Bind(cmdbuf, descriptor_sets[2]);
    vkd.CmdDispatch(cmdbuf, groups_x, groups_y, 1);

    AifgBarriers(cmdbuf).WriteToReadAll(temp2).ReadToWriteAll(out_images).Build();
    passes[4].Bind(cmdbuf, descriptor_sets[3]);
    vkd.CmdDispatch(cmdbuf, GroupCount(extent.width, OUTPUT_TILE_SHIFT),
                  GroupCount(extent.height, OUTPUT_TILE_SHIFT), 1);
}

}
