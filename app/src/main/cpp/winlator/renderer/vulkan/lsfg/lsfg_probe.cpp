// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
// See lsfg_probe.h. Capability gate for native compositor-side LSFG.

#include "lsfg_probe.h"
#include "../VulkanRendererContext.h"

#include <cstdio>
#include <cstring>

namespace lsfg {

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd) {
    FeatureSupport fs{};
    if (pd == VK_NULL_HANDLE || !vk.GetPhysicalDeviceProperties) return fs;

    VkPhysicalDeviceProperties props{};
    vk.GetPhysicalDeviceProperties(pd, &props);
    fs.deviceApiVersion = props.apiVersion;
    fs.apiAtLeast12 = props.apiVersion >= VK_API_VERSION_1_2;

    // Features2 is core 1.1. The storage-image features (all that the
    // precompiled SPIR-V needs) are plain 1.0 features and are read on any
    // device; VkPhysicalDeviceVulkan12Features is only chained on 1.2+.
    if (!vk.GetPhysicalDeviceFeatures2) return fs;

    VkPhysicalDeviceVulkan12Features v12{};
    v12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;

    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = fs.apiAtLeast12 ? &v12 : nullptr;

    vk.GetPhysicalDeviceFeatures2(pd, &f2);

    fs.queried = true;
    fs.storageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat == VK_TRUE;
    fs.storageImageExtendedFormats    = f2.features.shaderStorageImageExtendedFormats == VK_TRUE;
    if (fs.apiAtLeast12) {
        fs.vulkanMemoryModel            = v12.vulkanMemoryModel == VK_TRUE;
        fs.vulkanMemoryModelDeviceScope = v12.vulkanMemoryModelDeviceScope == VK_TRUE;
        fs.shaderFloat16                = v12.shaderFloat16 == VK_TRUE;
        fs.timelineSemaphore            = v12.timelineSemaphore == VK_TRUE;
    }
    return fs;
}

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt) {
    if (pd == VK_NULL_HANDLE || fmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;

    VkFormatProperties fp{};
    vk.GetPhysicalDeviceFormatProperties(pd, fmt, &fp);

    // The composite target is COLOR_ATTACHMENT (effect chain writes it),
    // SAMPLED (next frame's LSFG input reads it), STORAGE (generate writes it)
    // and both blit ends (it is copied into the swapchain image).
    const VkFormatFeatureFlags need =
          VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
        | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
        | VK_FORMAT_FEATURE_BLIT_SRC_BIT
        | VK_FORMAT_FEATURE_BLIT_DST_BIT;

    return (fp.optimalTilingFeatures & need) == need;
}

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt) {
    if (pd == VK_NULL_HANDLE || fmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;
    VkFormatProperties fp{};
    vk.GetPhysicalDeviceFormatProperties(pd, fmt, &fp);
    return (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
}

void explain(Caps& caps, Variant v) {
    const FeatureSupport& f = caps.features;
    const char* why = nullptr;
    const bool translated = v == Variant::DxbcTranslated;

    if (!f.queried) {
        why = "vkGetPhysicalDeviceFeatures2 unavailable";
    } else if (!f.storageImageWriteWithoutFormat) {
        why = "driver lacks shaderStorageImageWriteWithoutFormat";
    } else if (!f.storageImageExtendedFormats) {
        why = "driver lacks shaderStorageImageExtendedFormats";
    } else if (!caps.featuresEnabled) {
        why = "required features not enabled at device creation";
    } else if (!caps.storageOnSwapchainFormat) {
        why = "swapchain format is not storage-image capable";
    } else if (translated && !f.apiAtLeast12) {
        why = "device Vulkan version below 1.2 (translated SPIR-V 1.5 will not load; "
              "a Lossless.dll with precompiled SPIR-V shaders would)";
    } else if (translated && (!f.vulkanMemoryModel || !caps.memoryModelEnabled)) {
        why = "driver lacks vulkanMemoryModel (needed by translated shaders; "
              "a Lossless.dll with precompiled SPIR-V shaders would not need it)";
    } else if (v == Variant::SpirvFp16 && !caps.float16Enabled) {
        why = "driver lacks shaderFloat16 (needed by the fp16 shader set)";
    }

    if (why) {
        snprintf(caps.reason, sizeof(caps.reason), "unsupported: %s", why);
    } else {
        snprintf(caps.reason, sizeof(caps.reason),
                 "supported (device Vulkan %u.%u.%u, fmt %d)",
                 VK_VERSION_MAJOR(f.deviceApiVersion),
                 VK_VERSION_MINOR(f.deviceApiVersion),
                 VK_VERSION_PATCH(f.deviceApiVersion),
                 (int)caps.probedFormat);
    }
}

} // namespace lsfg
