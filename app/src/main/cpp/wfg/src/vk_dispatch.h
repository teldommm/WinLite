// Function-pointer dispatch for WFG's Vulkan calls.
//
// Required because adrenotools-loaded drivers live in an isolated linker namespace and do
// not share global symbols with the system loader: every call resolves through the driver's
// vkGetInstanceProcAddr, which the renderer hands over (vkd_bind_proc). Only the entry points
// WFG calls are in the table.

#pragma once

#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#include <stdbool.h>
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VkDispatch {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkCmdDispatch CmdDispatch;
} VkDispatch;

extern VkDispatch vkd;

// Resolves every entry point through gipa (device-level ones too: the loader trampolines).
// Repeated calls with the same gipa and instance are free.
bool vkd_bind_proc(PFN_vkGetInstanceProcAddr gipa, VkInstance instance);

#ifdef __cplusplus
}
#endif

// Redirect bare `vkFoo` names to the dispatch table.

#define vkGetInstanceProcAddr vkd.GetInstanceProcAddr
#define vkGetPhysicalDeviceProperties vkd.GetPhysicalDeviceProperties
#define vkGetPhysicalDeviceMemoryProperties vkd.GetPhysicalDeviceMemoryProperties
#define vkGetPhysicalDeviceFormatProperties vkd.GetPhysicalDeviceFormatProperties
#define vkGetDeviceProcAddr vkd.GetDeviceProcAddr
#define vkAllocateMemory vkd.AllocateMemory
#define vkFreeMemory vkd.FreeMemory
#define vkCreateImage vkd.CreateImage
#define vkDestroyImage vkd.DestroyImage
#define vkBindImageMemory vkd.BindImageMemory
#define vkGetImageMemoryRequirements vkd.GetImageMemoryRequirements
#define vkCreateImageView vkd.CreateImageView
#define vkDestroyImageView vkd.DestroyImageView
#define vkCreateSampler vkd.CreateSampler
#define vkDestroySampler vkd.DestroySampler
#define vkCreateDescriptorSetLayout vkd.CreateDescriptorSetLayout
#define vkDestroyDescriptorSetLayout vkd.DestroyDescriptorSetLayout
#define vkCreateDescriptorPool vkd.CreateDescriptorPool
#define vkDestroyDescriptorPool vkd.DestroyDescriptorPool
#define vkAllocateDescriptorSets vkd.AllocateDescriptorSets
#define vkUpdateDescriptorSets vkd.UpdateDescriptorSets
#define vkCreatePipelineLayout vkd.CreatePipelineLayout
#define vkDestroyPipelineLayout vkd.DestroyPipelineLayout
#define vkCreateComputePipelines vkd.CreateComputePipelines
#define vkDestroyPipeline vkd.DestroyPipeline
#define vkCreateShaderModule vkd.CreateShaderModule
#define vkDestroyShaderModule vkd.DestroyShaderModule
#define vkCmdBindPipeline vkd.CmdBindPipeline
#define vkCmdBindDescriptorSets vkd.CmdBindDescriptorSets
#define vkCmdPushConstants vkd.CmdPushConstants
#define vkCmdPipelineBarrier vkd.CmdPipelineBarrier
#define vkCmdBlitImage vkd.CmdBlitImage
#define vkCmdDispatch vkd.CmdDispatch
