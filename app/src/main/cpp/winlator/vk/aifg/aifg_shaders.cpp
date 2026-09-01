// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "aifg_shaders.hpp"
#include "aifg_common.hpp"
#include "aifg_dll.h"

#include <android/log.h>

#define LOG_TAG "AifgShaders"
#define SHADER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace aifg {

AifgShaders::AifgShaders(const Device& device_, const std::string& cache_path)
    : device{device_.Handle()} {
    AifgModuleSet set{};
    const AifgStatus status = aifg_load_modules(cache_path.c_str(), &set);
    if (status != AIFG_OK) {
        SHADER_LOGE("Shader cache unusable (status %d)", static_cast<int>(status));
        return;
    }

    for (uint32_t i = 0; i < set.count; i++) {
        const AifgModule& module = set.modules[i];

        VkShaderModuleCreateInfo module_ci{};
        module_ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_ci.codeSize = static_cast<size_t>(module.word_count) * sizeof(uint32_t);
        module_ci.pCode = module.words;

        VkShaderModule handle = VK_NULL_HANDLE;
        if (vkd.CreateShaderModule(device, &module_ci, nullptr, &handle) != VK_SUCCESS) {
            SHADER_LOGE("vkCreateShaderModule failed for shader %u", module.id);
            aifg_release_modules(&set);
            Release();
            return;
        }
        modules.emplace(module.id, handle);
    }

    const AifgVariant variant = set.variant;
    aifg_release_modules(&set);
    valid = modules.size() == AIFG_SHADER_COUNT;
    if (valid) {
        const char* variant_name = variant == AIFG_VARIANT_FP16   ? "fp16"
                                   : variant == AIFG_VARIANT_FP32 ? "fp32"
                                   : variant == AIFG_VARIANT_DXBC ? "dxbc-translated"
                                                                  : "unknown";
        SHADER_LOGI("Created %zu AIFG shader modules, variant=%s", modules.size(),
                    variant_name);
    } else {
        SHADER_LOGE("Expected %u shader modules, got %zu", AIFG_SHADER_COUNT, modules.size());
        Release();
    }
}

AifgShaders::~AifgShaders() {
    Release();
}

void AifgShaders::Release() {
    if (device != VK_NULL_HANDLE) {
        for (auto& [id, module] : modules) {
            vkd.DestroyShaderModule(device, module, nullptr);
        }
    }
    modules.clear();
    valid = false;
}

VkShaderModule AifgShaders::Get(uint32_t shader_id) const {
    const auto it = modules.find(shader_id);
    return it == modules.end() ? VK_NULL_HANDLE : it->second;
}

}
