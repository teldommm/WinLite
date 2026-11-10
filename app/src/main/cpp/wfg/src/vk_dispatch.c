#include "vk_dispatch.h"

#include <android/log.h>
#include <string.h>

#define LOG_TAG "VkDispatch"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

VkDispatch vkd;

static VkInstance vkd_owner_instance;

bool vkd_bind_proc(PFN_vkGetInstanceProcAddr gipa, VkInstance instance) {
    if (!gipa || instance == VK_NULL_HANDLE) return false;
    if (vkd.GetInstanceProcAddr == gipa && vkd_owner_instance == instance) return true;

    memset(&vkd, 0, sizeof(vkd));
    vkd_owner_instance = VK_NULL_HANDLE;
    vkd.GetInstanceProcAddr = gipa;

    #define LOAD(name) \
        vkd.name = (PFN_vk##name)gipa(instance, "vk" #name)
    LOAD(GetPhysicalDeviceProperties);
    LOAD(GetPhysicalDeviceMemoryProperties);
    LOAD(GetPhysicalDeviceFormatProperties);
    LOAD(GetDeviceProcAddr);
    LOAD(AllocateMemory);
    LOAD(FreeMemory);
    LOAD(CreateImage);
    LOAD(DestroyImage);
    LOAD(BindImageMemory);
    LOAD(GetImageMemoryRequirements);
    LOAD(CreateImageView);
    LOAD(DestroyImageView);
    LOAD(CreateSampler);
    LOAD(DestroySampler);
    LOAD(CreateDescriptorSetLayout);
    LOAD(DestroyDescriptorSetLayout);
    LOAD(CreateDescriptorPool);
    LOAD(DestroyDescriptorPool);
    LOAD(AllocateDescriptorSets);
    LOAD(UpdateDescriptorSets);
    LOAD(CreatePipelineLayout);
    LOAD(DestroyPipelineLayout);
    LOAD(CreateComputePipelines);
    LOAD(DestroyPipeline);
    LOAD(CreateShaderModule);
    LOAD(DestroyShaderModule);
    LOAD(CmdBindPipeline);
    LOAD(CmdBindDescriptorSets);
    LOAD(CmdPushConstants);
    LOAD(CmdPipelineBarrier);
    LOAD(CmdBlitImage);
    LOAD(CmdDispatch);
    LOAD(CreateRenderPass);
    LOAD(DestroyRenderPass);
    LOAD(CreateGraphicsPipelines);
    LOAD(CmdBeginRenderPass);
    LOAD(CmdEndRenderPass);
    LOAD(CmdSetViewport);
    LOAD(CmdSetScissor);
    LOAD(CmdDraw);
    #undef LOAD

    if (!vkd.GetDeviceProcAddr || !vkd.CreateComputePipelines || !vkd.CmdDispatch ||
        !vkd.CreateRenderPass || !vkd.DestroyRenderPass || !vkd.CreateGraphicsPipelines ||
        !vkd.CmdBeginRenderPass || !vkd.CmdEndRenderPass || !vkd.CmdSetViewport ||
        !vkd.CmdSetScissor || !vkd.CmdDraw) {
        LOGE("vkd_bind_proc: required entry points missing");
        return false;
    }
    vkd_owner_instance = instance;
    return true;
}
