#pragma once
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <vulkan/vulkan.h>
#include <cstdint>

#include "lsfg_dll.h"

struct VkTable;

namespace lsfg {

// What the physical device OFFERS. Queried before vkCreateDevice; what we
// actually enable is recorded in Caps below.
struct FeatureSupport {
    bool queried                     = false;  // vkGetPhysicalDeviceFeatures2 resolved
    bool apiAtLeast12                = false;
    bool vulkanMemoryModel           = false;
    bool vulkanMemoryModelDeviceScope= false;
    bool storageImageWriteWithoutFormat = false;
    bool storageImageExtendedFormats = false;
    bool shaderFloat16               = false;
    bool timelineSemaphore           = false;  // core 1.2 (used by the compositor, not LSFG)
    uint32_t deviceApiVersion        = 0;

    // Gates every variant needs (the precompiled SPIR-V 1.0 sets).
    bool nativeGatesPass() const {
        return queried && storageImageWriteWithoutFormat && storageImageExtendedFormats;
    }
    // Additional gates of the DXVK-translated set (SPIR-V 1.5 + memory model).
    bool translatedGatesPass() const {
        return nativeGatesPass() && apiAtLeast12 && vulkanMemoryModel;
    }
};

struct Caps {
    FeatureSupport features;
    bool featuresEnabled     = false;  // storage-image features passed to vkCreateDevice
    bool memoryModelEnabled  = false;  // vulkanMemoryModel passed to vkCreateDevice
    bool float16Enabled      = false;  // shaderFloat16 passed to vkCreateDevice
    bool storageOnSwapchainFormat = false;
    bool linearBlitOnSwapchainFormat = false;
    VkFormat probedFormat  = VK_FORMAT_UNDEFINED;
    char reason[160]       = "not probed";

    // Can this device run a cache of the given variant? Variant::None (not
    // known yet) is judged by the strictest set, the translated one.
    bool supported(Variant v) const {
        if (!featuresEnabled || !features.nativeGatesPass() || !storageOnSwapchainFormat)
            return false;
        switch (v) {
            case Variant::SpirvFp32: return true;
            case Variant::SpirvFp16: return float16Enabled;
            default:                 return memoryModelEnabled && features.translatedGatesPass();
        }
    }
};

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd);

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

// Fill caps.reason for the given variant (default: what this device can run
// at best, i.e. the precompiled fp32 set, noting if translation is out).
void explain(Caps& caps, Variant v = Variant::None);

} // namespace lsfg
