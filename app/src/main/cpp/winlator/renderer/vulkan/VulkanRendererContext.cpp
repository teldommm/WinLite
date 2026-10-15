#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "VulkanRendererContext.h"
#include "lsfg/lsfg_engine.h"
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <utility>
#include <chrono>
#include <inttypes.h>
#include <dlfcn.h>
#include <cerrno>
#include <sys/resource.h>
#include <unistd.h>
#include "window_vert.h"
#include "window_frag.h"
#include "window_sgsr_frag.h"
#include "window_fsr1_frag.h"
#include "window_stretch_frag.h"
#include "window_postfx_frag.h"

namespace {
VKAPI_ATTR VkBool32 VKAPI_CALL logValidationMessage(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT,
    const VkDebugUtilsMessengerCallbackDataEXT* data,
    void*) {
    const int priority = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_print(priority, "Winlator_VulkanValidation", "%s",
                        data && data->pMessage ? data->pMessage : "(no message)");
    return VK_FALSE;
}
}

// CLOCK_MONOTONIC, the clock of System.nanoTime() and Choreographer.
static int64_t steadyNowNs() {
    return (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

VulkanRendererContext::VulkanRendererContext(ANativeWindow* win, int cW, int cH,
                                             void* aHandle, bool enableValidation)
    : window(win), surfaceWidth(cW), surfaceHeight(cH),
      containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle), validationRequested(enableValidation)
{
    createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
    createSwapchain(); createRenderPass(); createDSLayout();
    createPipeline(true, pipeline);
    createFramebuffers(); createCmdPool(); createSampler();
    createWinTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; wakeRender(true);
    if (renderThread.joinable()) renderThread.join();
    std::lock_guard<std::mutex> lk(renderMutex);
    vk_.DeviceWaitIdle(device);
    for (auto& [id, wt] : texMap) destroyWinTex(wt);
    texMap.clear();
    // Every import still holds an AHardwareBuffer reference of its own (this was never freed
    // before: cleanupAllAHBCache() had no caller).
    destroyAllSourceImports();

    for (auto& retired : deleteQueue) destroyTexNow(retired);
    deleteQueue.clear();
    cleanupSwapchain(); cleanupCursorTex();

    vk_.DestroySampler(device, sampler, nullptr);
    vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
    if (sgsrPipeline != VK_NULL_HANDLE) vk_.DestroyPipeline(device, sgsrPipeline, nullptr);
    if (fsr1Pipeline != VK_NULL_HANDLE) vk_.DestroyPipeline(device, fsr1Pipeline, nullptr);
    if (stretchPipeline != VK_NULL_HANDLE) vk_.DestroyPipeline(device, stretchPipeline, nullptr);
    if (postfxPipeline != VK_NULL_HANDLE) vk_.DestroyPipeline(device, postfxPipeline, nullptr);
    vk_.DestroyPipeline(device, pipeline, nullptr);
    vk_.DestroyPipelineLayout(device, pipeLayout, nullptr);
    vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        vk_.DestroyFence(device, inFlightFences[i], nullptr);
    }
    for (auto sem : imgAvailSems) vk_.DestroySemaphore(device, sem, nullptr);
    for (auto sem : renderDoneSems) vk_.DestroySemaphore(device, sem, nullptr);
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
    vk_.DestroyDevice(device, nullptr);
    vk_.DestroySurfaceKHR(instance, surface, nullptr);
    if (validationMessenger != VK_NULL_HANDLE) {
        auto destroyMessenger = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
            gipa(instance, "vkDestroyDebugUtilsMessengerEXT"));
        if (destroyMessenger) destroyMessenger(instance, validationMessenger, nullptr);
    }
    vk_.DestroyInstance(instance, nullptr);
    if (adrenotoolsHandle) { dlclose(adrenotoolsHandle); adrenotoolsHandle = nullptr; }
}

void VulkanRendererContext::loadInstanceDispatch() {
    auto i = [&](const char* name) { return gipa ? gipa(instance, name) : nullptr; };
#define LOAD_I2(fn) vk_.fn = (PFN_vk##fn)i("vk"#fn)
    LOAD_I2(DestroyInstance);
    LOAD_I2(EnumeratePhysicalDevices);
    LOAD_I2(GetPhysicalDeviceProperties);
    LOAD_I2(GetPhysicalDeviceFeatures2);
    LOAD_I2(GetPhysicalDeviceFormatProperties);
    LOAD_I2(GetPhysicalDeviceMemoryProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_I2(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_I2(GetPhysicalDeviceSurfacePresentModesKHR);
    LOAD_I2(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_I2(CreateDevice);
    LOAD_I2(DestroySurfaceKHR);
    LOAD_I2(CreateAndroidSurfaceKHR);
    LOAD_I2(GetDeviceProcAddr);
}

void VulkanRendererContext::loadDeviceDispatch() {
    auto d = [&](const char* name) -> PFN_vkVoidFunction {
        return vk_.GetDeviceProcAddr ? vk_.GetDeviceProcAddr(device, name) : nullptr;
    };
#define LOAD_D2(fn) vk_.fn = (PFN_vk##fn)d("vk"#fn)
    LOAD_D2(DestroyDevice);
    LOAD_D2(GetDeviceQueue);
    LOAD_D2(CreateComputePipelines);
    LOAD_D2(CmdDispatch);
    LOAD_D2(UnmapMemory);
    LOAD_D2(CmdClearColorImage);
    LOAD_D2(ResetDescriptorPool);
    LOAD_D2(CreateQueryPool);
    LOAD_D2(DestroyQueryPool);
    LOAD_D2(GetQueryPoolResults);
    LOAD_D2(CmdResetQueryPool);
    LOAD_D2(CmdWriteTimestamp);
    LOAD_D2(DeviceWaitIdle);
    LOAD_D2(CreateSwapchainKHR);
    LOAD_D2(DestroySwapchainKHR);
    LOAD_D2(GetSwapchainImagesKHR);
    LOAD_D2(AcquireNextImageKHR);
    LOAD_D2(QueuePresentKHR);
    LOAD_D2(QueueSubmit);
    LOAD_D2(CreateRenderPass);
    LOAD_D2(DestroyRenderPass);
    LOAD_D2(CreateFramebuffer);
    LOAD_D2(DestroyFramebuffer);
    LOAD_D2(CreateImageView);
    LOAD_D2(DestroyImageView);
    LOAD_D2(CreateImage);
    LOAD_D2(DestroyImage);
    LOAD_D2(CreateBuffer);
    LOAD_D2(DestroyBuffer);
    LOAD_D2(AllocateMemory);
    LOAD_D2(FreeMemory);
    LOAD_D2(MapMemory);
    LOAD_D2(FlushMappedMemoryRanges);
    LOAD_D2(BindBufferMemory);
    LOAD_D2(BindImageMemory);
    LOAD_D2(GetBufferMemoryRequirements);
    LOAD_D2(GetImageMemoryRequirements);
    LOAD_D2(CreateDescriptorSetLayout);
    LOAD_D2(DestroyDescriptorSetLayout);
    LOAD_D2(CreateDescriptorPool);
    LOAD_D2(DestroyDescriptorPool);
    LOAD_D2(AllocateDescriptorSets);
    LOAD_D2(FreeDescriptorSets);
    LOAD_D2(UpdateDescriptorSets);
    LOAD_D2(CreatePipelineLayout);
    LOAD_D2(DestroyPipelineLayout);
    LOAD_D2(CreateShaderModule);
    LOAD_D2(DestroyShaderModule);
    LOAD_D2(CreateGraphicsPipelines);
    LOAD_D2(DestroyPipeline);
    LOAD_D2(CreateCommandPool);
    LOAD_D2(DestroyCommandPool);
    LOAD_D2(AllocateCommandBuffers);
    LOAD_D2(FreeCommandBuffers);
    LOAD_D2(BeginCommandBuffer);
    LOAD_D2(EndCommandBuffer);
    LOAD_D2(ResetCommandBuffer);
    LOAD_D2(CmdBeginRenderPass);
    LOAD_D2(CmdEndRenderPass);
    LOAD_D2(CmdBindPipeline);
    LOAD_D2(CmdBindDescriptorSets);
    LOAD_D2(CmdDraw);
    LOAD_D2(CmdPushConstants);
    LOAD_D2(CmdSetViewport);
    LOAD_D2(CmdSetScissor);
    LOAD_D2(CmdPipelineBarrier);
    LOAD_D2(CmdCopyImage);
    LOAD_D2(CmdBlitImage);
    LOAD_D2(CmdCopyBufferToImage);
    LOAD_D2(CreateSampler);
    LOAD_D2(DestroySampler);
    LOAD_D2(CreateSemaphore);
    LOAD_D2(DestroySemaphore);
    LOAD_D2(CreateFence);
    LOAD_D2(DestroyFence);
    LOAD_D2(WaitForFences);
    LOAD_D2(ResetFences);
    LOAD_D2(GetFenceStatus);

    vk_.GetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)d("vkGetAndroidHardwareBufferPropertiesANDROID");
}

void VulkanRendererContext::createInstance() {
    RLOG("createInstance: adrenotoolsHandle=%p (custom driver %s)",
        adrenotoolsHandle, adrenotoolsHandle?"ACTIVE":"NOT SET - using stock driver");

    if (adrenotoolsHandle) {
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(adrenotoolsHandle, "vkGetInstanceProcAddr");
    }
    if (!gipa) {
        void* loaderLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (loaderLib)
            gipa = (PFN_vkGetInstanceProcAddr)dlsym(loaderLib, "vkGetInstanceProcAddr");
    }

    if (!gipa) throw std::runtime_error("Vulkan loader unavailable");
    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    if (!vk_.CreateInstance) throw std::runtime_error("vkCreateInstance unavailable");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface",
                       VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;

    const char* validationLayer = "VK_LAYER_KHRONOS_validation";
    bool enableValidation = false;
    VkDebugUtilsMessengerCreateInfoEXT debugInfo{};
    if (validationRequested) {
        auto enumerateLayers = reinterpret_cast<PFN_vkEnumerateInstanceLayerProperties>(
            gipa(nullptr, "vkEnumerateInstanceLayerProperties"));
        uint32_t count = 0;
        if (enumerateLayers && enumerateLayers(&count, nullptr) == VK_SUCCESS) {
            std::vector<VkLayerProperties> layers(count);
            if (enumerateLayers(&count, layers.data()) == VK_SUCCESS) {
                enableValidation = std::any_of(layers.begin(), layers.end(),
                    [&](const VkLayerProperties& layer) {
                        return strcmp(layer.layerName, validationLayer) == 0;
                    });
            }
        }
        if (enableValidation) {
            ci.enabledLayerCount = 1;
            ci.ppEnabledLayerNames = &validationLayer;

            auto enumerateExtensions = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
                gipa(nullptr, "vkEnumerateInstanceExtensionProperties"));
            auto hasDebugUtils = [&](const char* layerName) {
                uint32_t extensionCount = 0;
                if (!enumerateExtensions ||
                    enumerateExtensions(layerName, &extensionCount, nullptr) != VK_SUCCESS)
                    return false;
                std::vector<VkExtensionProperties> extensions(extensionCount);
                return enumerateExtensions(layerName, &extensionCount, extensions.data()) == VK_SUCCESS &&
                    std::any_of(extensions.begin(), extensions.end(), [](const VkExtensionProperties& e) {
                        return strcmp(e.extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0;
                    });
            };
            if (hasDebugUtils(nullptr) || hasDebugUtils(validationLayer)) {
                ci.enabledExtensionCount = 3;
                debugInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
                debugInfo.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
                debugInfo.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
                debugInfo.pfnUserCallback = logValidationMessage;
                ci.pNext = &debugInfo;
            }
        } else {
            RLOG_E("WINLATOR_VULKAN_VALIDATION requested, but VK_LAYER_KHRONOS_validation is unavailable");
        }
    }

    VkResult result = vk_.CreateInstance(&ci, nullptr, &instance);
    if (result != VK_SUCCESS && enableValidation) {
        RLOG_E("validation instance creation failed (%d), retrying without the layer", result);
        enableValidation = false;
        ci.enabledLayerCount = 0;
        ci.ppEnabledLayerNames = nullptr;
        ci.enabledExtensionCount = 2;
        ci.pNext = nullptr;
        result = vk_.CreateInstance(&ci, nullptr, &instance);
    }
    if (result != VK_SUCCESS) throw std::runtime_error("instance");

    loadInstanceDispatch();
    if (enableValidation) {
        RLOG_E("VK_LAYER_KHRONOS_validation enabled for Vulkan renderer (debug_utils=%d)",
               ci.pNext ? 1 : 0);
        if (ci.pNext) {
            auto createMessenger = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                gipa(instance, "vkCreateDebugUtilsMessengerEXT"));
            if (!createMessenger ||
                createMessenger(instance, &debugInfo, nullptr, &validationMessenger) != VK_SUCCESS) {
                RLOG_E("VK_EXT_debug_utils messenger unavailable; check Android VALIDATION logs");
            }
        }
    }
}

void VulkanRendererContext::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window=window;
    if (vk_.CreateAndroidSurfaceKHR(instance,&ci,nullptr,&surface)!=VK_SUCCESS) throw std::runtime_error("surface");
}

void VulkanRendererContext::pickPhysicalDevice() {
    uint32_t n=0; vk_.EnumeratePhysicalDevices(instance,&n,nullptr);
    std::vector<VkPhysicalDevice> devs(n); vk_.EnumeratePhysicalDevices(instance,&n,devs.data());
    physicalDevice = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex = 0;
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physicalDevice = d;
                graphicsQueueFamilyIndex = i;
                fgTimestampsOk_ = qProps[i].timestampValidBits > 0;
                return;
            }
        }
    }
    if (n > 0) physicalDevice = devs[0];
}

void VulkanRendererContext::createLogicalDevice() {
    float p=1.f;
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=1; qi.pQueuePriorities=&p;

    PFN_vkEnumerateDeviceExtensionProperties enumDevExts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    bool hasForeignQueue = false;
    bool hasDisplayTiming = false;
    const char* globalPriorityExt = nullptr;
    if (enumDevExts) {
        uint32_t extCount = 0;
        if (enumDevExts(physicalDevice, nullptr, &extCount, nullptr) == VK_SUCCESS && extCount > 0) {
            std::vector<VkExtensionProperties> availableExts(extCount);
            if (enumDevExts(physicalDevice, nullptr, &extCount, availableExts.data()) == VK_SUCCESS) {
                for (const auto& ext : availableExts) {
                    RLOG("Available Vulkan device extension: %s (specVersion=%u)",
                         ext.extensionName, ext.specVersion);
                    if (strcmp(ext.extensionName, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0)
                        hasForeignQueue = true;
                    if (strcmp(ext.extensionName, "VK_GOOGLE_display_timing") == 0)
                        hasDisplayTiming = true;
                    // KHR is the promoted name of EXT; same queue create struct and values.
                    if (strcmp(ext.extensionName, "VK_KHR_global_priority") == 0)
                        globalPriorityExt = "VK_KHR_global_priority";
                    else if (!globalPriorityExt && strcmp(ext.extensionName, "VK_EXT_global_priority") == 0)
                        globalPriorityExt = "VK_EXT_global_priority";
                }
            }
        }
    }
    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    // The AHB extension depends on VK_EXT_queue_family_foreign, so it has to be enabled with it
    // whenever the device offers it. Unlike upstream (which refuses to start without it), a device
    // that doesn't advertise it keeps working exactly as before.
    if (hasForeignQueue) {
        extList.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    } else {
        RLOG_E("AHB imports want VK_EXT_queue_family_foreign, but the Vulkan device does not advertise it; continuing without it");
    }
    // The compositor's work per frame is tiny but sits behind the game's long submissions on the
    // same GPU; with a HIGH priority queue the driver can schedule (or preempt for) it first, so
    // a composite does not miss its vsync because the guest is mid-frame. A driver may refuse
    // the level (VK_ERROR_NOT_PERMITTED): the device is then created exactly as before.
    // Desired presentation times for frame generation's presents (the Android loader implements
    // it on top of the native window's buffer timestamps).
    if (hasDisplayTiming) extList.push_back("VK_GOOGLE_display_timing");
    displayTimingOk_ = hasDisplayTiming;
    VkDeviceQueueGlobalPriorityCreateInfoEXT queuePriority{};
    queuePriority.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT;
    queuePriority.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_EXT;
    if (globalPriorityExt) {
        extList.push_back(globalPriorityExt);
        qi.pNext = &queuePriority;
    }
    for (const char* ext : extList)
        RLOG("Enabled Vulkan device extension: %s", ext);
    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();

    // --- Native LSFG frame generation: enable the features its shaders need.
    // The renderer has historically enabled NO features at all
    // (pEnabledFeatures = nullptr, no pNext). The cache variant is not known
    // yet (the DLL is picked later), so enable everything the device offers
    // for any variant:
    //   * every variant: shaderStorageImageWriteWithoutFormat/ExtendedFormats
    //   * DXBC-translated: + vulkanMemoryModel (and Vulkan 1.2 for SPIR-V 1.5)
    //   * precompiled fp16: + shaderFloat16
    // A device that fails even the common gate is untouched: vkCreateDevice
    // is called exactly as it always has been.
    lsfgCaps_ = lsfg::Caps{};
    lsfgCaps_.features = lsfg::queryFeatures(vk_, physicalDevice);

    VkPhysicalDeviceVulkan12Features lsfgV12{};
    VkPhysicalDeviceFeatures2        lsfgF2{};
    if (lsfgCaps_.features.nativeGatesPass()) {
        lsfgF2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        lsfgF2.features.shaderStorageImageWriteWithoutFormat = VK_TRUE;
        lsfgF2.features.shaderStorageImageExtendedFormats    = VK_TRUE;

        const bool wantMemoryModel = lsfgCaps_.features.translatedGatesPass();
        const bool wantFloat16     = lsfgCaps_.features.shaderFloat16;
        if (lsfgCaps_.features.apiAtLeast12 && (wantMemoryModel || wantFloat16)) {
            lsfgV12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
            if (wantMemoryModel) {
                lsfgV12.vulkanMemoryModel = VK_TRUE;
                // Device scope is a separate SPIR-V capability; enable it only
                // when offered, so a driver without it still gets the base model.
                lsfgV12.vulkanMemoryModelDeviceScope =
                    lsfgCaps_.features.vulkanMemoryModelDeviceScope ? VK_TRUE : VK_FALSE;
            }
            if (wantFloat16) lsfgV12.shaderFloat16 = VK_TRUE;
            lsfgF2.pNext = &lsfgV12;
            lsfgCaps_.memoryModelEnabled = wantMemoryModel;
            lsfgCaps_.float16Enabled     = wantFloat16;
        }

        // pEnabledFeatures MUST stay null while a VkPhysicalDeviceFeatures2 is
        // chained - the two are mutually exclusive.
        ci.pNext = &lsfgF2;
        lsfgCaps_.featuresEnabled = true;
    }

    VkResult deviceResult = vk_.CreateDevice(physicalDevice,&ci,nullptr,&device);
    if (deviceResult!=VK_SUCCESS && globalPriorityExt) {
        RLOG_E("createLogicalDevice: HIGH queue priority refused (%d); retrying at default priority",
               (int)deviceResult);
        qi.pNext = nullptr;
        extList.pop_back();
        ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();
        globalPriorityExt = nullptr;
        deviceResult = vk_.CreateDevice(physicalDevice,&ci,nullptr,&device);
    }
    globalPriorityHigh_.store(globalPriorityExt != nullptr);
    __android_log_print(ANDROID_LOG_INFO, WLOG_TAG,
           "createLogicalDevice: compositor queue priority %s, display timing %s",
           globalPriorityExt ? "HIGH" : "default (global priority unavailable)",
           displayTimingOk_ ? "available" : "unavailable (frame gen presents back to back)");
    if (deviceResult!=VK_SUCCESS) {
        // A driver that rejects the feature chain must not cost us the whole
        // renderer: retry once with the pre-LSFG device create, unchanged.
        if (lsfgCaps_.featuresEnabled) {
            RLOG_E("createLogicalDevice: CreateDevice failed WITH LSFG features; retrying without");
            ci.pNext = nullptr;
            lsfgCaps_.featuresEnabled    = false;
            lsfgCaps_.memoryModelEnabled = false;
            lsfgCaps_.float16Enabled     = false;
            if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS)
                throw std::runtime_error("device");
        } else {
            throw std::runtime_error("device");
        }
    }

    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
    fgTimestampPeriodNs_ = props.limits.timestampPeriod;
}

void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);

    VkExtent2D logicalExt;
    if (caps.currentExtent.width != 0xFFFFFFFF) {
        logicalExt = caps.currentExtent;
    } else if (window) {
        int winW = ANativeWindow_getWidth(window);
        int winH = ANativeWindow_getHeight(window);
        logicalExt = {
            (uint32_t)std::clamp(winW, (int)caps.minImageExtent.width, (int)caps.maxImageExtent.width),
            (uint32_t)std::clamp(winH, (int)caps.minImageExtent.height, (int)caps.maxImageExtent.height)
        };
    } else {
        logicalExt = {(uint32_t)surfaceWidth, (uint32_t)surfaceHeight};
    }

    VkSurfaceTransformFlagBitsKHR pre;
    switch (caps.currentTransform) {
        case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            pre = caps.currentTransform;
            break;
        default:

            RLOG_E("createSwapchain: currentTransform inesperado (0x%x), usando IDENTITY",
                (unsigned)caps.currentTransform);
            pre = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
            break;
    }
    surfaceTransform = pre;

    if (pre == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR || pre == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR) {
        swapchainExt = { logicalExt.height, logicalExt.width };
    } else {
        swapchainExt = logicalExt;
    }

    uint32_t fmtN=0; vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtN); vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,fmts.data());
    swapchainFmt = VK_FORMAT_R8G8B8A8_UNORM;
    lsfgCaps_.probedFormat = swapchainFmt;
    lsfgCaps_.storageOnSwapchainFormat = lsfg::probeStorageFormat(vk_, physicalDevice, swapchainFmt);
    lsfgCaps_.linearBlitOnSwapchainFormat = lsfg::probeLinearBlit(vk_, physicalDevice, swapchainFmt);
    lsfg::explain(lsfgCaps_, lsfgCacheVariant_);
    RLOG("lsfg-native: %s", lsfgCaps_.reason);
    const bool nativeFg = fgArmed_.load() && fgCapsOk();
    uint32_t imgCount=caps.minImageCount+1;
    if (nativeFg) imgCount = std::max(imgCount, std::min(caps.minImageCount + kMaxPresentsPerFrame, 8u));
    if (caps.maxImageCount>0&&imgCount>caps.maxImageCount) imgCount=caps.maxImageCount;

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}
    // LSFG presents generated frames before the real one and relies on FIFO
    // ordering them onto consecutive vblanks; mailbox would let them collapse.
    if (nativeFg) presentMode = VK_PRESENT_MODE_FIFO_KHR;
    if(verboseLog){
        std::string pmList;
        for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
        RLOG("createSwapchain: logical=%dx%d identity=%dx%d fmt=%d preTransform=0x%x supportedPresentModes=[%s] chosen=%d req=%d",
            logicalExt.width,logicalExt.height,swapchainExt.width,swapchainExt.height,
            (int)swapchainFmt,(unsigned)pre,pmList.c_str(),(int)presentMode,(int)requestedPresentMode);
    }

    VkCompositeAlphaFlagBitsKHR compositeAlpha=
        (caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    VkSwapchainKHR oldSwapchain=swapchain;

    VkSwapchainCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface=surface; ci.minImageCount=imgCount; ci.imageFormat=swapchainFmt;
    ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; ci.imageExtent=swapchainExt;
    ci.imageArrayLayers=1; ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainTransferDst = nativeFg && (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    if (swapchainTransferDst) ci.imageUsage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    VkSwapchainKHR newSwapchain=VK_NULL_HANDLE;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&newSwapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    swapchain=newSwapchain;
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount);
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
    fgSwapchainCapacity_ = imgCount > caps.minImageCount ? imgCount - caps.minImageCount : 0;
    swapchainViews.resize(imgCount);
    for (size_t i=0;i<imgCount;i++) {
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=swapchainImages[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=swapchainFmt;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkComponentMapping mapping{};
        mapping.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components = mapping;
        if (vk_.CreateImageView(device,&vi,nullptr,&swapchainViews[i])!=VK_SUCCESS) throw std::runtime_error("imgview");
    }
}

void VulkanRendererContext::createRenderPass() {
    renderPass = createCompatibleRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    if (!renderPass) throw std::runtime_error("renderpass");
}

void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}

VkShaderModule VulkanRendererContext::makeShader(const uint32_t* code, size_t sz) {
    VkShaderModuleCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize=sz; ci.pCode=code; VkShaderModule m;
    if (vk_.CreateShaderModule(device,&ci,nullptr,&m)!=VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}

void VulkanRendererContext::createPipeline(bool blend, VkPipeline& out) {
    if (pipeLayout==VK_NULL_HANDLE) {
        VkPushConstantRange pc{}; pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.size=sizeof(WindowPushConstantsSGSR);
        VkPipelineLayoutCreateInfo li{}; li.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount=1; li.pSetLayouts=&dsLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&pc;
        if (vk_.CreatePipelineLayout(device,&li,nullptr,&pipeLayout)!=VK_SUCCESS) throw std::runtime_error("pipelayout");
    }
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_frag_code,sizeof(window_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=blend?VK_TRUE:VK_FALSE;
    if (blend){ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;ba.alphaBlendOp=VK_BLEND_OP_ADD;}
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&out)!=VK_SUCCESS) throw std::runtime_error("pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
}

void VulkanRendererContext::createCursorPipeline() {  }

void VulkanRendererContext::createSgsrPipeline() {
    if (sgsrPipeline != VK_NULL_HANDLE) return;
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_sgsr_frag_code,sizeof(window_sgsr_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=VK_FALSE;
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&sgsrPipeline)!=VK_SUCCESS) throw std::runtime_error("sgsr_pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
    RLOG("createSgsrPipeline: done");
}

void VulkanRendererContext::createFsr1Pipeline() {
    if (fsr1Pipeline != VK_NULL_HANDLE) return;
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_fsr1_frag_code,sizeof(window_fsr1_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=VK_FALSE;
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&fsr1Pipeline)!=VK_SUCCESS) throw std::runtime_error("fsr1_pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
    RLOG("createFsr1Pipeline: done");
}

void VulkanRendererContext::createStretchPipeline() {
    if (stretchPipeline != VK_NULL_HANDLE) return;
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_stretch_frag_code,sizeof(window_stretch_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=VK_FALSE;
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&stretchPipeline)!=VK_SUCCESS) throw std::runtime_error("stretch_pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
    RLOG("createStretchPipeline: done");
}

void VulkanRendererContext::createPostFXPipeline() {
    if (postfxPipeline != VK_NULL_HANDLE) return;
    auto vert = makeShader(window_vert_code,        sizeof(window_vert_code));
    auto frag = makeShader(window_postfx_frag_code, sizeof(window_postfx_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;   stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo   vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=VK_FALSE;
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&postfxPipeline)!=VK_SUCCESS) throw std::runtime_error("postfx_pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
    RLOG("createPostFXPipeline: done");
}

void VulkanRendererContext::setPostFXMode(int mode) {
    // Destroys a pipeline the render thread may be recording with: exclusive frame lock.
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    RLOG("setPostFXMode: %d -> %d", postFXMode, mode);
    if (postFXMode == mode) return;
    postFXMode = mode;
    if (mode > 0) {
        if (filterMode != 2 && filterMode != 3 && postfxPipeline == VK_NULL_HANDLE)
            createPostFXPipeline();
    } else {
        if (filterMode != 5 && postfxPipeline != VK_NULL_HANDLE) {
            vk_.DeviceWaitIdle(device);
            vk_.DestroyPipeline(device, postfxPipeline, nullptr);
            postfxPipeline = VK_NULL_HANDLE;
            RLOG("setPostFXMode: postfxPipeline destruído");
        }
    }
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::setSharpness(float s) {
    sharpness = std::clamp(s, 0.0f, 1.0f);
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::createFramebuffers() {
    swapchainFBs.resize(swapchainViews.size());
    for (size_t i=0;i<swapchainViews.size();i++) {
        VkImageView att[]={swapchainViews[i]};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=renderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=swapchainExt.width; fi.height=swapchainExt.height; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&swapchainFBs[i])!=VK_SUCCESS) throw std::runtime_error("fb");
    }
}

void VulkanRendererContext::createCmdPool() {
    VkCommandPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; ci.queueFamilyIndex=graphicsQueueFamilyIndex;
    if (vk_.CreateCommandPool(device,&ci,nullptr,&cmdPool)!=VK_SUCCESS) throw std::runtime_error("cmdpool");
}

void VulkanRendererContext::createSampler() {
    VkFilter filter = (filterMode == 1) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d)",
        filterMode==1?"NEAREST":"LINEAR", filterMode);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    if (vk_.CreateSampler(device,&ci,nullptr,&sampler)!=VK_SUCCESS) throw std::runtime_error("sampler");
}

void VulkanRendererContext::createWinTexPool() {
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 129};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=129;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}

void VulkanRendererContext::createCursorDS() {
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=winTexPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}

void VulkanRendererContext::createCmdBufs() {
    // One command buffer per pending present, per frame-in-flight slot: up to
    // kMaxPresentsPerFrame buffers are needed when frame gen is armed.
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT * kMaxPresentsPerFrame);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=(uint32_t)cmdBufs.size();
    if (vk_.AllocateCommandBuffers(device,&ai,cmdBufs.data())!=VK_SUCCESS) throw std::runtime_error("cmdbuf");
}

void VulkanRendererContext::createSyncObjects() {
    inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
    for (auto& fence : inFlightFences)
        if (vk_.CreateFence(device,&fi,nullptr,&fence)!=VK_SUCCESS) throw std::runtime_error("fence");
    recreateSyncObjects();
}

void VulkanRendererContext::recreateSyncObjects() {
    // Sized against the swapchain, so this must run whenever it does. A
    // pending semaphore from a since-destroyed swapchain must never survive
    // into the new one, hence the unconditional destroy-then-rebuild here.
    for (auto sem : imgAvailSems) vk_.DestroySemaphore(device, sem, nullptr);
    for (auto sem : renderDoneSems) vk_.DestroySemaphore(device, sem, nullptr);
    imgAvailSems.assign(MAX_FRAMES_IN_FLIGHT * kMaxPresentsPerFrame, VK_NULL_HANDLE);
    // Reacquiring an image guarantees its preceding present consumed this semaphore.
    renderDoneSems.assign(swapchainImages.size(), VK_NULL_HANDLE);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (auto& sem : imgAvailSems)
        if (vk_.CreateSemaphore(device,&si,nullptr,&sem)!=VK_SUCCESS) throw std::runtime_error("acquire semaphore");
    for (auto& sem : renderDoneSems)
        if (vk_.CreateSemaphore(device,&si,nullptr,&sem)!=VK_SUCCESS) throw std::runtime_error("present semaphore");
}

void VulkanRendererContext::cleanupSwapchain() {
    vk_.DeviceWaitIdle(device);
    lsfgEngine_.reset();
    lsfgEngineTried_ = false;
    compositeArmed = false;
    destroyCompositeTargets();
    destroyFgQueryPool();
    if (compositeRenderPass) vk_.DestroyRenderPass(device, compositeRenderPass, nullptr);
    if (cursorOverlayRenderPass) vk_.DestroyRenderPass(device, cursorOverlayRenderPass, nullptr);
    compositeRenderPass = cursorOverlayRenderPass = VK_NULL_HANDLE;

    for (auto fb:swapchainFBs) vk_.DestroyFramebuffer(device,fb,nullptr); swapchainFBs.clear();
    for (auto iv:swapchainViews) vk_.DestroyImageView(device,iv,nullptr); swapchainViews.clear();
    if (!cmdBufs.empty()){vk_.FreeCommandBuffers(device,cmdPool,(uint32_t)cmdBufs.size(),cmdBufs.data());cmdBufs.clear();}
    if (swapchain!=VK_NULL_HANDLE) {
        vk_.DestroySwapchainKHR(device,swapchain,nullptr);
        swapchain=VK_NULL_HANDLE;
    }
    swapchainImages.clear();
    imgInFlight.clear();
}

uint32_t VulkanRendererContext::findMemType(uint32_t filter, VkMemoryPropertyFlags props) {
    for (uint32_t i=0;i<memProperties.memoryTypeCount;i++)
        if ((filter&(1u<<i))&&(memProperties.memoryTypes[i].propertyFlags&props)==props) return i;
    throw std::runtime_error("memtype");
}

void VulkanRendererContext::createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem)
{
    VkBufferCreateInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO; bi.size=sz; bi.usage=usage; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateBuffer(device,&bi,nullptr,&buf)!=VK_SUCCESS) throw std::runtime_error("buffer");
    VkMemoryRequirements req; vk_.GetBufferMemoryRequirements(device,buf,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,props);
    if (vk_.AllocateMemory(device,&ai,nullptr,&mem)!=VK_SUCCESS) throw std::runtime_error("bufmem");
    vk_.BindBufferMemory(device,buf,mem,0);
}

VkCommandBuffer VulkanRendererContext::beginOneTime() {
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandPool=cmdPool; ai.commandBufferCount=1;
    VkCommandBuffer cb; vk_.AllocateCommandBuffers(device,&ai,&cb);
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.BeginCommandBuffer(cb,&bi); return cb;
}

void VulkanRendererContext::endOneTime(VkCommandBuffer cb) {
    vk_.EndCommandBuffer(cb);
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount=1; si.pCommandBuffers=&cb;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; VkFence fence;
    vk_.CreateFence(device,&fi,nullptr,&fence);
    vk_.QueueSubmit(graphicsQueue,1,&si,fence); vk_.WaitForFences(device,1,&fence,VK_TRUE,UINT64_MAX);
    vk_.DestroyFence(device,fence,nullptr); vk_.FreeCommandBuffers(device,cmdPool,1,&cb);
}

void VulkanRendererContext::transition(VkCommandBuffer cb, VkImage img,
    VkImageLayout ol, VkImageLayout nl, VkAccessFlags sa, VkAccessFlags da,
    VkPipelineStageFlags ss, VkPipelineStageFlags dss)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=ol; b.newLayout=nl; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.srcAccessMask=sa; b.dstAccessMask=da;
    vk_.CmdPipelineBarrier(cb,ss,dss,0,0,nullptr,0,nullptr,1,&b);
}

bool VulkanRendererContext::createWinTexResources(WinTex& wt, int w, int h) {
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return false;
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,wt.img,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device,&ai,nullptr,&wt.mem)!=VK_SUCCESS){vk_.DestroyImage(device,wt.img,nullptr);wt.img=VK_NULL_HANDLE;return false;}
    vk_.BindImageMemory(device,wt.img,wt.mem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vi.components={swapRB?VK_COMPONENT_SWIZZLE_B:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,swapRB?VK_COMPONENT_SWIZZLE_R:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorSetAllocateInfo dsai{}; dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    if (vk_.AllocateDescriptorSets(device,&dsai,&wt.ds)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=wt.view; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=wt.ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    VkDeviceSize stgSz=(VkDeviceSize)w*h*4;
    createBuffer(stgSz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,wt.stg,wt.stgMem);
    vk_.MapMemory(device,wt.stgMem,0,stgSz,0,&wt.mapped);
    wt.cap=stgSz; wt.w=w; wt.h=h; wt.needsTransition=true;
    return true;
}

bool VulkanRendererContext::importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb) {
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID)
        return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext=&fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device,ahb,&props)!=VK_SUCCESS)
        return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);

    VkFormat imageFormat = fmtP.format;
    if (imageFormat == VK_FORMAT_UNDEFINED) {
        if (desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
            imageFormat = VK_FORMAT_R8G8B8A8_UNORM;
        else if (desc.format == 5u)
            imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
        else {
            RLOG_E("importAHBToWinTex: unsupported external-only AHB format=%u externalFormat=%" PRIu64,
                desc.format, fmtP.externalFormat);
            return false;
        }
        RLOG("importAHBToWinTex: using compatible VkFormat fallback=%d for AHB format=%u",
            (int)imageFormat, desc.format);
    }

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ii{};
    ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext=&emi; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.format=imageFormat;
    ii.extent={desc.width,desc.height,1};
    ii.mipLevels=1; ii.arrayLayers=1; ii.samples=VK_SAMPLE_COUNT_1_BIT;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS)
        return false;

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer=ahb;

    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext=&imp; ded.image=wt.img;

    VkMemoryAllocateInfo mai{};
    mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext=&ded; mai.allocationSize=props.allocationSize;
    mai.memoryTypeIndex=findMemType(props.memoryTypeBits,0);
    if (vk_.AllocateMemory(device,&mai,nullptr,&wt.mem)!=VK_SUCCESS){
        vk_.DestroyImage(device,wt.img,nullptr);
        wt.img=VK_NULL_HANDLE;
        return false;
    }
    vk_.BindImageMemory(device,wt.img,wt.mem,0);

    VkImageViewCreateInfo vi{};
    vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D;
    vi.format=imageFormat;
    vi.components={VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,
                   VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){
        destroyWinTex(wt);
        return false;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    VkResult dsRes=vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY){
        RLOG_E("importAHBToWinTex: descriptor pool exhausted for AHB texture");
        destroyWinTex(wt);
        return false;
    }
    if (dsRes!=VK_SUCCESS){ destroyWinTex(wt); return false; }

    VkDescriptorImageInfo dii{};
    dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    dii.imageView=wt.view; dii.sampler=sampler;

    VkWriteDescriptorSet wr{};
    wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wr.dstSet=wt.ds; wr.dstBinding=0;
    wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);

    wt.needsTransition=true;
    wt.isAHB=true;
    wt.w=(int)desc.width;
    wt.h=(int)desc.height;
    return true;
}

void VulkanRendererContext::destroyWinTex(WinTex& wt) {
    if (wt.isAHB) { wt = {}; return; }
    if (wt.img!=VK_NULL_HANDLE || wt.stg!=VK_NULL_HANDLE) {
        WinTex deferred = wt;
        deferred.isAHB = false;
        deleteQueue.push_back({deferred, pendingFrameSerial ? pendingFrameSerial : submittedSerial, nullptr});
        retirePending.store(true, std::memory_order_release);
    }
    wt={};
}

void VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return;
    cleanupCursorTex();
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    vk_.CreateImage(device,&ii,nullptr,&cursorImg);
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vk_.AllocateMemory(device,&ai,nullptr,&cursorMem); vk_.BindImageMemory(device,cursorImg,cursorMem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vk_.CreateImageView(device,&vi,nullptr,&cursorView);
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    cursorTexW=w; cursorTexH=h;
}

void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    for (uint32_t slot=0; slot<MAX_FRAMES_IN_FLIGHT; ++slot) {
        if (cursorStg[slot]!=VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device,cursorStg[slot],nullptr);
            vk_.FreeMemory(device,cursorStgM[slot],nullptr);
            cursorStg[slot]=VK_NULL_HANDLE; cursorStgM[slot]=VK_NULL_HANDLE;
            cursorStgP[slot]=nullptr; cursorStgC[slot]=0;
        }
    }
    cursorTexW=0; cursorTexH=0;
}

void VulkanRendererContext::ensureCursorStaging(VkDeviceSize sz, uint32_t slot) {
    if (cursorStgC[slot]>=sz) return;
    if (cursorStg[slot]!=VK_NULL_HANDLE) {
        vk_.DestroyBuffer(device,cursorStg[slot],nullptr);
        vk_.FreeMemory(device,cursorStgM[slot],nullptr);
    }
    createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,cursorStg[slot],cursorStgM[slot]);
    vk_.MapMemory(device,cursorStgM[slot],0,sz,0,&cursorStgP[slot]); cursorStgC[slot]=sz;
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis,
    VkRect2D scissorRect)
{
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) throw std::runtime_error("begin cb");

    ahbTransitions.clear(); preUpload.clear(); postUpload.clear();

    for (auto& d : draws) {
        if (d.img==VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        } else if (!d.isAHB && (d.needsTransition || d.upload!=VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    if (!preUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)preUpload.size(), preUpload.data());

    for (auto& d : draws) {
        if (d.isAHB || d.upload==VK_NULL_HANDLE || d.img==VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset=0; r.bufferRowLength=0; r.bufferImageHeight=0;
        r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)d.w,(uint32_t)d.h,1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    bool cursorDrawn = curVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE;
    bool hasCursorCopy = hasCursorUpload && cursorImg!=VK_NULL_HANDLE && cursorUpload!=VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        // Every cursor upload overwrites the complete image, including its first use.
        b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.image=cursorImg; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy r{}; r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)curW,(uint32_t)curH,1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)postUpload.size(), postUpload.data());

    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass=renderPass; rpi.framebuffer=swapchainFBs[imgIdx]; rpi.renderArea={{0,0},swapchainExt};
    VkClearValue clr={{{0.f,0.f,0.f,1.f}}}; rpi.clearValueCount=1; rpi.pClearValues=&clr;

    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,0,(float)swapchainExt.width,(float)swapchainExt.height,0,1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);

    {
        int32_t  ox2 = std::max(0, scissorRect.offset.x);
        int32_t  oy2 = std::max(0, scissorRect.offset.y);
        uint32_t maxW = (swapchainExt.width  > (uint32_t)ox2) ? swapchainExt.width  - (uint32_t)ox2 : 0u;
        uint32_t maxH = (swapchainExt.height > (uint32_t)oy2) ? swapchainExt.height - (uint32_t)oy2 : 0u;
        VkRect2D sc{{ox2, oy2},
                    {std::min(scissorRect.extent.width,  maxW),
                     std::min(scissorRect.extent.height, maxH)}};
        vk_.CmdSetScissor(cb, 0, 1, &sc);
    }

    float rotCosR, rotSinR;
    getPreRotationCosSin(rotCosR, rotSinR);

    bool useSgsr    = (filterMode == 2) && (sgsrPipeline    != VK_NULL_HANDLE);
    bool useFsr1    = (filterMode == 3) && (fsr1Pipeline    != VK_NULL_HANDLE);
    bool usePostFX  = (postFXMode  > 0) && (postfxPipeline  != VK_NULL_HANDLE);
    bool useStretch = (stretchMode == 1) && (stretchPipeline != VK_NULL_HANDLE);

    VkPipeline activePipeline = useSgsr   ? sgsrPipeline
                              : useFsr1   ? fsr1Pipeline
                              : usePostFX ? postfxPipeline
                              : useStretch? stretchPipeline
                              : pipeline;
    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, activePipeline);
    for (auto& d : draws) {
        if (d.ds==VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &d.ds, 0, nullptr);
        if (useSgsr) {
            WindowPushConstantsSGSR pc{};
            if (useStretch) { pc.ndcX0 = -1.f; pc.ndcX1 = 1.f; }
            else { pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f; pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f; }
            pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
            pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
            pc.useTexAlpha = 0;
            float sw = (float)std::max(d.w, 1); float sh = (float)std::max(d.h, 1);
            pc.invSrcW = 1.0f / sw; pc.invSrcH = 1.0f / sh;
            pc.srcW = sw; pc.srcH = sh;
            pc.effectId = postFXMode; pc.resW = 0.0f; pc.sharpness = sharpness;
            pc.cosR = rotCosR; pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        } else if (useFsr1) {
            WindowPushConstantsFSR1 pc{};
            if (useStretch) { pc.ndcX0 = -1.f; pc.ndcX1 = 1.f; }
            else { pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f; pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f; }
            pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
            pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
            pc.useTexAlpha = 0;
            pc.srcW = (float)std::max(d.w, 1); pc.srcH = (float)std::max(d.h, 1);
            pc.outW = std::max(1.0f, fabsf(pc.ndcX1-pc.ndcX0)*0.5f*(float)swapchainExt.width);
            pc.outH = std::max(1.0f, fabsf(pc.ndcY1-pc.ndcY0)*0.5f*(float)swapchainExt.height);
            pc.effectId = postFXMode; pc.sharpness = sharpness;
            pc.cosR = rotCosR; pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        } else if (usePostFX) {
            WindowPushConstantsPostFX pc{};
            pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f; pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
            pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f; pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
            pc.effectId=postFXMode; pc.sharpness=sharpness;
            pc.resW=(float)std::max(d.w,1); pc.resH=(float)std::max(d.h,1);
            pc.cosR = rotCosR; pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        } else if (useStretch) {
            WindowPushConstantsStretch pc{};
            pc.ndcX0=-1.f; pc.ndcX1=1.f;
            pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f; pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
            pc.useTexAlpha=0; pc.strength=stretchStrength; pc.profile=stretchProfile;
            pc.cosR = rotCosR; pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        } else {
            WindowPushConstants pc{};
            pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f; pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
            pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f; pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
            pc.useTexAlpha=0;
            pc.cosR = rotCosR; pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        }
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {
        if (useSgsr || useFsr1 || useStretch || usePostFX) vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &cursorDS, 0, nullptr);
        float cx=(float)((int)ptrX-curHotX), cy=(float)((int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.useTexAlpha=1;
        cpc.cosR = rotCosR; cpc.sinR = rotSinR;
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(cpc), &cpc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus!=VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed with status=%d (swapRB=%d draws=%zu imgIdx=%u)",
            (int)endStatus, (int)swapRB, draws.size(), imgIdx);
        throw std::runtime_error("end cb");
    }
}

void VulkanRendererContext::renderLoop() {
    // The compositor sits on the path of every frame and of the cursor; at the default nice 0 it
    // competes on equal terms with the emulator's guest threads. -10 is what Android uses for
    // its own display-critical threads; an app may lower its own threads' nice value.
    if (setpriority(PRIO_PROCESS, 0, -10) != 0)
        RLOG_E("renderLoop: could not raise render thread priority (errno=%d)", errno);
    while (isRunning) {
        {
            std::unique_lock<std::mutex> lk(dirtyMutex);
            // A deferred frame-gen wake (cursor/transform change while the guest was drawing)
            // is re-run after the idle window even if nothing else arrives, so a game that stops
            // presenting still gets its cursor.
            const bool fgWait = fgDeferred_.load(std::memory_order_relaxed);
            const auto fgDeadline = std::chrono::steady_clock::now() +
                                    std::chrono::milliseconds(kFgIdleMs);
            while (isRunning) {
                const bool detached = surfaceDetached.load();
                if (!detached && fbResized.load()) break;
                const bool content = needsRender.load();
                const bool cursor  = cursorMoved.load();
                // A guest frame held by the limiter for its display slot.
                const int64_t due  = nextPendingDueNs_.load(std::memory_order_relaxed);
                if (!detached && (content || cursor || due != 0)) {
                    const int64_t now = steadyNowNs();
                    int64_t wait = INT64_MAX;
                    if (content) wait = pacingWaitNs(true);
                    else if (cursor) {
                        wait = pacingWaitNs(false);
                        // Cursor motion shortly before a held frame's slot rides along with
                        // that frame: a separate cursor present there would sit in the queue
                        // in front of it and push the frame a refresh late.
                        const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
                        if (due != 0 && due - now < period) wait = std::max(wait, due - now);
                    }
                    if (due != 0) wait = std::min(wait, due - now);
                    if (wait <= 0) break;
                    // Woken early by newer updates; re-evaluated each time (a content update
                    // during a cursor-only wait is allowed sooner).
                    dirtyCV.wait_for(lk, std::chrono::nanoseconds(wait));
                    continue;
                }
                if (fgWait) {
                    if (dirtyCV.wait_until(lk, fgDeadline) == std::cv_status::timeout) break;
                    continue;
                }
                dirtyCV.wait(lk);
            }
        }
        if (!isRunning) break;
        try { renderFrame(); }
        catch (const std::exception& error) {
            if (!swapchainRetryPending.exchange(true)) RLOG_E("renderFrame failed: %s", error.what());
            { std::lock_guard<std::mutex> lk(renderMutex); pendingFrameSerial=0; }
            fbResized.store(true);
        }
        catch (...) {
            if (!swapchainRetryPending.exchange(true)) RLOG_E("renderFrame failed: unknown exception");
            { std::lock_guard<std::mutex> lk(renderMutex); pendingFrameSerial=0; }
            fbResized.store(true);
        }
        // A failed frame or swapchain retries at a calm pace instead of spinning the core: the
        // wait is cut short as soon as the surface goes away or the renderer stops.
        if (swapchainRetryPending.load()) {
            std::unique_lock<std::mutex> lk(dirtyMutex);
            dirtyCV.wait_for(lk,std::chrono::milliseconds(50),[this]{
                return !isRunning||surfaceDetached.load(); });
        }
    }
}

int64_t VulkanRendererContext::pacingWaitNs(bool content) const {
    // Frame generation plans and paces its own presents (and defers non-content wakes itself).
    if (fgArmed_.load(std::memory_order_relaxed)) return 0;
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    if (period <= 0) return 0;
    // Time until the display has drained everything presented so far, in the model.
    const int64_t pending = presentSlotEndNs_ - steadyNowNs();
    // Content may have one frame waiting behind the one being shown; cursor-only motion none.
    const int64_t wait = pending - (content ? period : 0);
    // The model can only be wrong by about one refresh; never hold a frame longer than two.
    return std::min(wait, 2 * period);
}

void VulkanRendererContext::notePresentedForPacing(uint32_t presents, int64_t lastDesiredNs) {
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    if (period <= 0 || presents == 0) return;
    presentSlotEndNs_ = std::max(presentSlotEndNs_, steadyNowNs()) + (int64_t)presents * period;
    // Presents held for a desired time occupy the display until that refresh is over.
    // (desired = intended presentation - half a refresh; the frame stays one refresh.)
    // The model counts in queue deadlines; desired times are presentation times (deadline +
    // the frame timeline's offset), so convert back.
    if (lastDesiredNs > 0) {
        const int64_t offset = vsyncPresentOffsetNs_.load(std::memory_order_relaxed);
        presentSlotEndNs_ = std::max(presentSlotEndNs_, lastDesiredNs + period / 2 - offset + period);
    }
}

uint32_t VulkanRendererContext::fgPresentCap(uint32_t capacity, int64_t groupStartNs) {
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    if (period <= 0) return capacity;
    const int64_t now = steadyNowNs();
    // Where this group starts: its slot (limiter grid), else the next refresh it can make.
    const int64_t lead = std::clamp<int64_t>(period * 2 / 5, 3000000LL, 8000000LL);
    const int64_t start = groupStartNs > now ? groupStartNs : now + lead;
    // Refreshes still taken by earlier presents at that point: they go first in FIFO.
    const int64_t backlog = presentSlotEndNs_ - start;
    const int64_t queued = backlog > period / 2 ? (backlog + period / 2) / period : 0;
    // Refreshes one source frame lasts, carried as credit so a fractional ratio (70 FPS on
    // 120 Hz = 1.71) alternates instead of always rounding one way. Unknown source rate: no
    // limit from it, only from the backlog.
    const int64_t interval = fgSourceIntervalNs();
    double slots;
    if (interval > 0) {
        const double perFrame = (double)interval / (double)period;
        fgSlotCredit_ = std::min(fgSlotCredit_ + perFrame, perFrame + 1.0);
        slots = std::floor(fgSlotCredit_ + 0.05);
    } else {
        fgSlotCredit_ = 0.0;
        slots = (double)(capacity + 1);
    }
    const int64_t presents = (int64_t)slots - queued;   // the real frame always goes
    const uint32_t cap = presents <= 1 ? 0u : (uint32_t)std::min<int64_t>(presents - 1, capacity);
    if (cap < capacity && (fgCapLog_++ % 240u) == 0u)
        RLOG("lsfg-native: present cap %u of %u (slots %.2f, queued %lld)",
             cap, capacity, slots, (long long)queued);
    return cap;
}

void VulkanRendererContext::setDisplayRefreshRate(float hz) {
    const int64_t period = (hz > 1.0f && hz < 1000.0f) ? (int64_t)(1.0e9 / (double)hz) : 0;
    if (refreshPeriodNs_.exchange(period, std::memory_order_relaxed) != period)
        RLOG("setDisplayRefreshRate: %.2f Hz (pacing %s)", hz, period ? "on" : "off");
    wakeRender();
}

void VulkanRendererContext::flushDeleteQueue() {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (deleteQueue.empty()) return;
    vk_.DeviceWaitIdle(device);
    for (auto& retired:deleteQueue) destroyTexNow(retired);
    deleteQueue.clear();
    retirePending.store(false, std::memory_order_release);
}

void VulkanRendererContext::destroyTexNow(RetiredTex& retired) {
    WinTex& wt=retired.wt;
    if (wt.ds  !=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
    if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
    if (wt.img !=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
    if (wt.mem !=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
    if (wt.stg !=VK_NULL_HANDLE){vk_.DestroyBuffer(device,wt.stg,nullptr);vk_.FreeMemory(device,wt.stgMem,nullptr);}
    if (retired.ahb) AHardwareBuffer_release(retired.ahb);
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);

    if (surfaceDetached.load(std::memory_order_acquire)) return;
    if (surfaceWidth==0||surfaceHeight==0) { swapchainRetryPending.store(true); return; }

    if (fbResized.load()) {
        for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
        cleanupSwapchain();
        bool ok=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();recreateSyncObjects();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);ok=true;}
        catch(const std::exception& e){
            if (!swapchainRetryPending.exchange(true)) RLOG_E("swapchain recreate failed: %s",e.what());
        }
        catch(...){
            if (!swapchainRetryPending.exchange(true)) RLOG_E("swapchain recreate failed: unknown exception");
        }
        if (ok) {
            fbResized.store(false);
            swapchainRetryPending.store(false);
            needsRender.store(true);
            wakeRender();
        }
        return;
    }

    // Nothing to draw into (a recreate is still failing or pending): ask for another attempt.
    if (swapchain==VK_NULL_HANDLE || cmdBufs.empty()) {
        fbResized.store(true);
        swapchainRetryPending.store(true);
        return;
    }
    if (cmdSlot(0) >= cmdBufs.size() || cmdBufs[cmdSlot(0)] == VK_NULL_HANDLE) {
        fbResized.store(true);
        swapchainRetryPending.store(true);
        return;
    }
    // --- Frame gen: only new guest content makes a source frame. A wake that
    // carries none (pointer motion, cursor image, transform) is folded into the
    // next guest frame while the guest is drawing - every present draws the
    // cursor at its latest position anyway - and only once the guest has been
    // idle for kFgIdleMs is it drawn on its own, without frame generation.
    fgDeferred_.store(false, std::memory_order_relaxed);
    fgPassthrough_ = false;
    fgAnchorSlotNs_ = 0;
    // Frames held for their display slot go on screen now if their time has come - before the
    // frame generation check below, which takes them as this pass's new source frame.
    if (nextPendingDueNs_.load(std::memory_order_relaxed) != 0) {
        std::lock_guard<std::mutex> lk(renderMutex);
        applyDuePresentsLocked(steadyNowNs());
    }
    uint64_t fgNewSourceFrames = 0;
    if (compositeActive()) {
        // New guest frames since the last frame-gen pass: presents when the guest
        // presents through X Present, content uploads otherwise.
        const auto now = std::chrono::steady_clock::now();
        const int64_t nowNs = (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
            now.time_since_epoch()).count();
        const uint64_t content = guestFrameSerial_.load(std::memory_order_relaxed);
        const uint64_t present = guestPresentSerial_.load(std::memory_order_relaxed);
        const int64_t  lastPresentNs = guestPresentNs_.load(std::memory_order_relaxed);
        const bool presentsDriving = lastPresentNs != 0 && nowNs - lastPresentNs < kPresentActiveNs;
        const uint64_t fresh = presentsDriving
            ? (present > fgSeenPresentSerial_ ? present - fgSeenPresentSerial_ : 0)
            : (content > fgSeenContentSerial_ ? content - fgSeenContentSerial_ : 0);
        if (fresh > 0) {
            fgSeenContentSerial_ = content;
            fgSeenPresentSerial_ = present;
            fgLastGuestFrame_    = now;
            // Counters switching over (first present after content-only
            // frames) can show a stale gap; one pass never covers more than a
            // few guest frames.
            fgNewSourceFrames = std::min<uint64_t>(fresh, 4);
        } else if (now - fgLastGuestFrame_ < std::chrono::milliseconds(kFgIdleMs)) {
            fgDeferred_.store(true, std::memory_order_relaxed);
            return;
        } else {
            fgPassthrough_ = true;
        }
    }

    bool currentFenceWaited = false;
    VkResult fenceState=vk_.GetFenceStatus ? vk_.GetFenceStatus(device,inFlightFences[currentFrame]) : VK_NOT_READY;
    if (fenceState==VK_NOT_READY) {
        if (vk_.WaitForFences(device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX)!=VK_SUCCESS)
            throw std::runtime_error("frame fence wait");
        currentFenceWaited = true;
    } else if (fenceState!=VK_SUCCESS) throw std::runtime_error("frame fence status");

    // This slot's frame is finished, so every texture it drew can now be destroyed.
    completedSerial=std::max(completedSerial,slotSerial[currentFrame]);
    if (retirePending.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(renderMutex);
        if (!deleteQueue.empty()) {
            size_t keep=0;
            for (auto& retired:deleteQueue) {
                if (retired.serial<=completedSerial) destroyTexNow(retired);
                else deleteQueue[keep++]=std::move(retired);
            }
            deleteQueue.resize(keep);
        }
        retirePending.store(!deleteQueue.empty(),std::memory_order_release);
    }

    // This slot's previous frame is complete, so its chain timestamps are final.
    readFgQueryResult();

    // --- Frame gen: decide whether THIS frame composites off-swapchain. The
    // targets are created lazily on the first armed frame and torn down when it
    // disarms, so a session that never turns frame gen on never allocates them.
    if (fgArmed_.load(std::memory_order_relaxed) && fgCapsOk() && swapchainTransferDst) {
        const int mult = fgMultiplier_.load(std::memory_order_relaxed);
        const uint32_t want = (uint32_t)std::min(std::max(mult, 2), 4) + 1u;
        uint32_t ringW = 0, ringH = 0;
        compositeExtentFor(ringW, ringH);
        compositeArmed = ensureCompositeTargets(ringW, ringH, want);
        if (compositeArmed) compositeArmed = createCursorOverlayRenderPass();
        if (compositeArmed && !compositeTargets.empty())
            compositeIndex = (compositeIndex + 1) % (uint32_t)compositeTargets.size();
    } else if (compositeArmed || !compositeTargets.empty()) {
        // Disarmed (or the swapchain went away): drop the ring so the direct
        // path is byte-identical to a session that never armed it.
        compositeArmed = false;
        vk_.DeviceWaitIdle(device);
        destroyCompositeTargets();
        // Free the interpolation chain too (every per-resolution image and
        // descriptor set, sized to the panel). The compiled shader modules are
        // kept, so re-arming rebuilds the chain only, not the 25 pipelines.
        if (lsfgEngine_) {
            lsfgEngine_->releaseChain();
            lsfgEngine_->reset();
        }
    }

    // --- Frame gen: decide how many frames to synthesise for this source
    // frame, BEFORE acquiring, since that sets how many images we need.
    fgPlan_ = FrameGenPlan{};
    const uint32_t capacity = (uint32_t)std::min<size_t>(
        std::min(kMaxPresentsPerFrame - 1, fgSwapchainCapacity_),
        compositeTargets.empty() ? 0 : compositeTargets.size() - 1);
    if (compositeActive() && !fgPassthrough_ && ensureLsfgEngine()) {
        if (fgConfigDirty_.exchange(false, std::memory_order_relaxed)) {
            lsfgEngine_->configure(
                (uint32_t)std::max(fgMultiplier_.load(std::memory_order_relaxed), 2),
                (uint32_t)std::max(fgTargetRate_.load(std::memory_order_relaxed), 0),
                fgFlowScale_.load(std::memory_order_relaxed),
                fgRefreshHz_.load(std::memory_order_relaxed));
        }
        // The flow-pyramid size depends on the guest extent. Set it before the
        // first prepare so the 25-pipeline chain is built once at the right size.
        {
            uint32_t gw = 0, gh = 0;
            guestExtentForFrameGen(gw, gh);
            if (gw > 0 && gh > 0) lsfgEngine_->setGuestExtent(gw, gh);
        }
        if (lsfgEngine_->needsRebuild(compositeW, compositeH, swapchainFmt))
            vk_.DeviceWaitIdle(device);
        if (lsfgEngine_->prepare(compositeW, compositeH, swapchainFmt)) {
            lsfgEngine_->setPresentedRate(fgPresentedRate_);
            // The pacer gets GUEST frames, not compositor passes: with
            // presents counted, one pass may carry 0..n of them.
            fgSourceFrames_ += fgNewSourceFrames;
            const uint32_t cap = fgPresentCap(capacity, fgAnchorSlotNs_);
            fgPlan_.generations = lsfgEngine_->plan(cap, fgSourceFrames_);
        }
    }
    fgPlan_.presents = fgPlan_.generations + 1;

    // A real timeout (rather than UINT64_MAX) makes a wedged acquire visible
    // as VK_TIMEOUT instead of hanging the render thread forever; the
    // non-success guard below already handles it like any other failure.
    // The first image is the one the frame cannot do without, so it keeps the
    // long timeout. Every further image only carries a generated frame: if the
    // presentation engine cannot hand one over within about one refresh
    // interval, show fewer generated frames for this source frame instead of
    // dropping the frame and recreating the swapchain. VK_TIMEOUT/NOT_READY do
    // not signal the semaphore, and every image that WAS acquired is presented
    // (the last one carries the real frame), so no signal is leaked.
    const uint64_t genAcquireTimeout = frameGenAcquireTimeoutNs();
    for (uint32_t k = 0; k < fgPlan_.presents; k++) {
        uint32_t idx = 0;
        VkResult ar = vk_.AcquireNextImageKHR(device,swapchain,
                                              k == 0 ? 2000000000ULL : genAcquireTimeout,
                                              imgAvailSems[syncSlot(k)],VK_NULL_HANDLE,&idx);
        if (k > 0 && (ar == VK_TIMEOUT || ar == VK_NOT_READY)) {
            fgPlan_.presents    = k;
            fgPlan_.generations = k - 1;
            break;
        }
        if (ar==VK_ERROR_OUT_OF_DATE_KHR||ar==VK_ERROR_SURFACE_LOST_KHR){
            // Logged deliberately: when this fires every frame it IS the
            // swapchain-recreate loop, and it used to be invisible.
            if ((fgAcquireFailLog_++ % 60u) == 0u)
                RLOG_E("renderFrame: acquire %u/%u -> %s (recreating swapchain)",
                       k, fgPlan_.presents,
                       ar==VK_ERROR_OUT_OF_DATE_KHR ? "OUT_OF_DATE" : "SURFACE_LOST");
            fbResized.store(true);
            return;
        }
        if (ar!=VK_SUCCESS&&ar!=VK_SUBOPTIMAL_KHR) {
            // Could not get every image we planned for. Anything already
            // acquired has a semaphore nobody will wait on, so the only safe
            // move is to drop this frame entirely rather than leak a signal.
            if (k == 0) {
                // Nothing acquired. A failure that comes back at once (device lost) with a
                // held frame already due would otherwise loop straight back here: retry at the
                // calm pace of a failed swapchain instead (cleared by the next good present).
                if (!swapchainRetryPending.exchange(true))
                    RLOG_E("renderFrame: acquire failed (res=%d), retrying", (int)ar);
                return;
            }
            RLOG_E("renderFrame: acquire %u/%u failed (res=%d) - dropping frame",
                   k, fgPlan_.presents, (int)ar);
            fbResized.store(true);
            return;
        }
        if (idx >= swapchainFBs.size() || idx >= swapchainImages.size()) {
            RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
                idx, swapchainFBs.size(), swapchainImages.size());
            fbResized.store(true);
            return;
        }
        fgPlan_.imgIdx[k] = idx;
    }
    // A working acquire ends any failure back-off (it would otherwise pace every frame at 50 ms).
    if (swapchainRetryPending.load(std::memory_order_relaxed)) swapchainRetryPending.store(false);
    // Real frame N is presented LAST; generated frames take the earlier slots.
    const uint32_t imgIdx = fgPlan_.imgIdx[fgPlan_.presents - 1];
    VkResult res = VK_SUCCESS;

    if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
    for (uint32_t k = 0; k < fgPlan_.presents; k++) {
        const uint32_t idx = fgPlan_.imgIdx[k];
        if (imgInFlight[idx]!=VK_NULL_HANDLE &&
            (!currentFenceWaited || imgInFlight[idx] != inFlightFences[currentFrame])) {
            if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, imgInFlight[idx]) == VK_NOT_READY) {
                if (vk_.WaitForFences(device,1,&imgInFlight[idx],VK_TRUE,2000000000ULL) != VK_SUCCESS) {
                    RLOG_E("renderFrame: in-flight image fence wait timed out (2s), skipping frame");
                    fbResized.store(true);
                    return;
                }
            }
        }
        imgInFlight[idx]=inFlightFences[currentFrame];
    }

    for (uint32_t k = 0; k < fgPlan_.presents; k++) vk_.ResetCommandBuffer(cmdBufs[cmdSlot(k)],0);

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;
    VkRect2D effectiveScissor{{0,0},swapchainExt};
    // First-use transitions are only marked done once the submit that carries them succeeded.
    struct PendingAHBTransition { int64_t srcId; VkImage image; };
    struct PendingTexTransition { int64_t id; VkImage image; };
    std::vector<PendingAHBTransition> pendingAHB;
    std::vector<PendingTexTransition> pendingTex;

    {
        std::lock_guard<std::mutex> lk(renderMutex);

        // Pin the draw list before releasing renderMutex: removeWindow may run while the
        // commands are recorded but before their submit reaches the GPU.
        pendingFrameSerial=++submittedSerial;

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY;
        curVis=cursorVisible.load();

        if (hasCustomScissor) effectiveScissor = rotateRectLogicalToIdentity(customScissor);

        // A held frame that came due while this pass waited for its image goes along too -
        // not with frame generation, which counted its source frame at the start of the pass
        // (taking one now would show it without generated frames and count it next pass).
        if (!compositeActive()) applyDuePresentsLocked(steadyNowNs());

        if (treeDirty) { rebuildRenderListFromTree(); treeDirty = false; }

        frameDraws.clear();
        for (auto& re:renderList) {
            auto it=texMap.find(re.id);
            if (it==texMap.end()) continue;
            WinTex& wt=it->second;
            if (wt.ds==VK_NULL_HANDLE) continue;
            DrawEntry de{wt.img,wt.ds,VK_NULL_HANDLE,re.x,re.y,wt.w,wt.h};
            de.isAHB=wt.isAHB;
            if (wt.isAHB) {
                auto sit=srcImports.find(wt.srcId);
                if (sit!=srcImports.end() && sit->second.wt.img==wt.img &&
                    sit->second.wt.needsTransition &&
                    std::none_of(pendingAHB.begin(),pendingAHB.end(),
                        [&](const PendingAHBTransition& p){return p.image==wt.img;})) {
                    de.needsTransition=true;
                    pendingAHB.push_back({wt.srcId,wt.img});
                }
            } else if (wt.needsTransition &&
                std::none_of(pendingTex.begin(),pendingTex.end(),
                    [&](const PendingTexTransition& p){return p.image==wt.img;})) {
                de.needsTransition=true;
                pendingTex.push_back({re.id,wt.img});
            }
            if (wt.dirty && !wt.isAHB && wt.stg!=VK_NULL_HANDLE) { de.upload=wt.stg; wt.dirty=false; }
            else if (wt.isAHB) { wt.dirty=false; }
            frameDraws.push_back(de);
        }

        if (isCursorImageDirty.load() && !cursorPixels.empty()) {
            if (cursorImg==VK_NULL_HANDLE || cursorTexW!=cursorPendingW || cursorTexH!=cursorPendingH) {
                // The shared cursor descriptor and the old image view can still be referenced by
                // either frame slot. Resizes are rare; wait only here, never on pointer motion or
                // same-size cursor updates.
                if (cursorImg!=VK_NULL_HANDLE)
                    for (auto& fence:inFlightFences)
                        if (vk_.WaitForFences(device,1,&fence,VK_TRUE,UINT64_MAX)!=VK_SUCCESS)
                            throw std::runtime_error("cursor resize fence wait");
                ensureCursorTex(cursorPendingW,cursorPendingH);
            }
            VkDeviceSize csz=(VkDeviceSize)cursorTexW*cursorTexH*4;
            ensureCursorStaging(csz,currentFrame);
            memcpy(cursorStgP[currentFrame],cursorPixels.data(),(size_t)csz);
            isCursorImageDirty.store(false); hasCurUpload=true; curUpload=cursorStg[currentFrame];
        }
        curW=cursorTexW; curH=cursorTexH;
    }

    recordCmdBuf(cmdBufs[cmdSlot(0)],imgIdx,frameDraws,
        frameAhbTransitions,framePreUpload,framePostUpload,
        curUpload,hasCurUpload,
        ox,oy,sx,sy,cw,ch,ptrX,ptrY,curHotX,curHotY,curW,curH,curVis,
        effectiveScissor);

    const VkPipelineStageFlags waitStage = compositeActive()
        ? (VkPipelineStageFlags)(VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
        : (VkPipelineStageFlags)VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSwapchainKHR scs[]={swapchain};

    int64_t desiredPresent[kMaxPresentsPerFrame] = {};
    if (compositeActive()) fgPresentTimes(fgPlan_.presents, desiredPresent, fgAnchorSlotNs_);

    // The slot's fence rides on the LAST submit. A fence signal is ordered
    // after every command submitted earlier on the same queue, so it still
    // covers all of this frame's work.
    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    uint32_t presented = 0;
    bool fenceSubmitted = false;
    // Generated frames belong between N-1 and N, so they go out first and the
    // real frame is presented last.
    for (uint32_t k = 0; k < fgPlan_.presents; k++) {
        VkCommandBuffer cb = cmdBufs[cmdSlot(k)];
        if (k > 0) {
            VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) { fbResized.store(true); break; }
            // Order this buffer after everything already submitted for this
            // frame: the chain's shared passes and the earlier generations.
            // Barriers span command buffers on one queue, so this is enough.
            VkMemoryBarrier mb{}; mb.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            mb.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT;
            mb.dstAccessMask=VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT;
            vk_.CmdPipelineBarrier(cb,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 1,&mb, 0,nullptr, 0,nullptr);
            if (k < fgPlan_.generations) recordFrameGenGeneration(cb, k);
            else                          copyCompositeToSwapchain(cb, imgIdx);
            if (vk_.EndCommandBuffer(cb)!=VK_SUCCESS) {
                RLOG_E("renderFrame: EndCommandBuffer failed for present %u/%u", k, fgPlan_.presents);
                fbResized.store(true);
                break;
            }
        }
        const bool last = (k + 1 == fgPlan_.presents);
        VkSemaphore wSem = imgAvailSems[syncSlot(k)];
        VkSemaphore sSem = renderDoneSems[fgPlan_.imgIdx[k]];
        VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount=1; si.pWaitSemaphores=&wSem; si.pWaitDstStageMask=&waitStage;
        si.commandBufferCount=1; si.pCommandBuffers=&cb;
        si.signalSemaphoreCount=1; si.pSignalSemaphores=&sSem;
        if (vk_.QueueSubmit(graphicsQueue,1,&si, last ? inFlightFences[currentFrame] : VK_NULL_HANDLE)!=VK_SUCCESS) {
            // The fence was reset above and may now never signal; replace it
            // with a signalled one so the next use of this slot does not wait
            // on it forever.
            // An earlier submit of this frame already went out and is not covered by that fence
            // (it rides on the last one), so let it finish before its textures can be retired.
            if (k > 0) vk_.DeviceWaitIdle(device);
            {
                std::lock_guard<std::mutex> lk(renderMutex);
                if (k > 0) slotSerial[currentFrame] = pendingFrameSerial;
                pendingFrameSerial = 0;
                if (k == 0 && hasCurUpload) isCursorImageDirty.store(true);
            }
            vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
            VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
            vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
            fbResized.store(true);
            swapchainRetryPending.store(true);
            return;
        }
        if (k == 0 && (!pendingAHB.empty() || !pendingTex.empty())) {
            std::lock_guard<std::mutex> lk(renderMutex);
            for (const auto& p:pendingAHB) {
                auto it=srcImports.find(p.srcId);
                if (it!=srcImports.end() && it->second.wt.img==p.image)
                    it->second.wt.needsTransition=false;
            }
            for (const auto& p:pendingTex) {
                auto it=texMap.find(p.id);
                if (it!=texMap.end() && it->second.img==p.image)
                    it->second.needsTransition=false;
            }
        }
        fenceSubmitted = last;
        VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.waitSemaphoreCount=1; pi.pWaitSemaphores=&sSem;
        pi.swapchainCount=1; pi.pSwapchains=scs; pi.pImageIndices=&fgPlan_.imgIdx[k];
        VkPresentTimeGOOGLE presentTime{0, (uint64_t)desiredPresent[k]};
        VkPresentTimesInfoGOOGLE presentTimes{};
        if (desiredPresent[k] > 0) {
            presentTimes.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
            presentTimes.swapchainCount = 1;
            presentTimes.pTimes = &presentTime;
            pi.pNext = &presentTimes;
        }
        res=vk_.QueuePresentKHR(graphicsQueue,&pi);
        presented++;
        if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR||res==VK_SUBOPTIMAL_KHR) {
            // The swapchain recreate this triggers also rebuilds the
            // semaphores, so the acquires we never waited on cannot strand a
            // signal into the next swapchain (recreateSyncObjects).
            fbResized.store(true);
            break;
        }
    }
    if (!fenceSubmitted) {
        // Left early before the last submit: an empty submit carrying the
        // fence is ordered after everything queued above, so the slot's fence
        // still means "this frame's work is done".
        VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vk_.QueueSubmit(graphicsQueue,1,&si,inFlightFences[currentFrame]);
    }
    {
        // Everything of this frame is submitted and the slot's fence covers it.
        std::lock_guard<std::mutex> lk(renderMutex);
        slotSerial[currentFrame]=pendingFrameSerial;
        pendingFrameSerial=0;
    }
    trackPresentedRate(presented);
    notePresentedForPacing(presented, presented > 0 ? desiredPresent[presented - 1] : 0);
    if (compositeActive()) fgSlotCredit_ = std::max(0.0, fgSlotCredit_ - (double)presented);
    currentFrame=(currentFrame+1)%MAX_FRAMES_IN_FLIGHT;
}

void VulkanRendererContext::onSurfaceResized(int w, int h) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (w==0||h==0) return;
    surfaceWidth=w; surfaceHeight=h; fbResized.store(true); wakeRender();
}

void VulkanRendererContext::detachSurface() {
    surfaceDetached.store(true, std::memory_order_release);
    wakeRender(true);
    { std::unique_lock<std::shared_mutex> frameLock(frameMutex); }
    vk_.DeviceWaitIdle(device);
    cleanupSwapchain();
    if (surface != VK_NULL_HANDLE) {
        vk_.DestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window) { ANativeWindow_release(window); window = nullptr; }
}

bool VulkanRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (window) { ANativeWindow_release(window); window = nullptr; }
    window = newWindow;
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    if (vk_.CreateAndroidSurfaceKHR(instance, &ci, nullptr, &surface) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: CreateAndroidSurface failed");
        ANativeWindow_release(window); window = nullptr;
        return false;
    }
    {
        std::unique_lock<std::shared_mutex> frameLock(frameMutex);
        try {
            createSwapchain();
            createFramebuffers();
            createCmdBufs();
            recreateSyncObjects();
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: swapchain recreate failed");
            return false;
        }

        surfaceWidth  = ANativeWindow_getWidth(window);
        surfaceHeight = ANativeWindow_getHeight(window);
        // Texture layouts survive a surface change; forcing a transition from UNDEFINED here
        // would throw their contents away.
        swapchainRetryPending.store(false);
        surfaceDetached.store(false, std::memory_order_release);
    }
    needsRender.store(true, std::memory_order_release);
    wakeRender(true);
    __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer", "reattachSurface: OK");
    return true;
}

void VulkanRendererContext::setTransform(float ox, float oy, float sx, float sy) {
    { std::lock_guard<std::mutex> lk(renderMutex); sceneOffsetX=ox;sceneOffsetY=oy;sceneScaleX=sx;sceneScaleY=sy; }
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::updatePointerPosition(short x, short y) {
    pointerX.store(x); pointerY.store(y);
    if (cursorVisible.load()) { cursorMoved.store(true); wakeRender(); }
}

void VulkanRendererContext::setCursorVisible(bool v) {
    cursorVisible.store(v); cursorMoved.store(true); wakeRender();
}

void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short stride, short hotX, short hotY) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    cursorPixels.resize((size_t)w*h);
    const size_t srcStrideBytes=(size_t)std::max((int)stride,(int)w)*4;
    const size_t rowBytes=(size_t)w*4;
    const uint8_t* src=static_cast<const uint8_t*>(px);
    uint8_t* dst=reinterpret_cast<uint8_t*>(cursorPixels.data());
    for (int y=0;y<h;y++) memcpy(dst+(size_t)y*rowBytes,src+(size_t)y*srcStrideBytes,rowBytes);
    cursorPendingW=w; cursorPendingH=h;
    cursorHotX=hotX; cursorHotY=hotY;
    isCursorImageDirty.store(true); needsRender.store(true); wakeRender();
}

void VulkanRendererContext::updateWindowContent(int64_t id, void* px, short w, short h, short stride, int, int) {
    if (!px||w<=0||h<=0) return;
    void* mapped=nullptr;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        WinTex& wt=texMap[id];
        if (wt.isAHB) wt={}; // The cached AHB owns the borrowed image.
        if (wt.img==VK_NULL_HANDLE || wt.w!=w || wt.h!=h) {
            if (wt.img!=VK_NULL_HANDLE) destroyWinTex(wt);
            if (!createWinTexResources(wt,w,h)) { texMap.erase(id); return; }
        }
        mapped=wt.mapped;
    }
    if (!mapped) return;
    const size_t dstPitch=(size_t)w*4;
    const int32_t srcStride=stride>0?stride:w;
    uint32_t* src2=static_cast<uint32_t*>(px);
    uint8_t*  dst2=static_cast<uint8_t*>(mapped);
    for (int row=0;row<h;++row)
        memcpy(dst2+(size_t)row*dstPitch, &src2[(size_t)row*srcStride], (size_t)w*4);
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        auto it=texMap.find(id);
        if (it!=texMap.end()) it->second.dirty=true;
    }
    guestFrameSerial_.fetch_add(1, std::memory_order_relaxed);
    needsRender.store(true); wakeRender();
}

bool VulkanRendererContext::acquireSourceLocked(int64_t srcId, AHardwareBuffer* ahb) {
    auto sit = srcImports.find(srcId);
    if (sit != srcImports.end() && (sit->second.released || sit->second.ahb != ahb)) {
        // A drawable's buffer never changes and a released one is never presented again; if
        // either happens anyway, never sample the stale import. Windows still showing it keep
        // the borrowed handles until they move on (retired only once unused).
        RLOG_E("acquireSource: stale import for source %" PRId64 " (released=%d)",
               srcId, (int)sit->second.released);
        if (!sit->second.released) {
            sit->second.released = true;
            if (sit->second.users <= 0) retireSourceLocked(sit);
        }
        return false;
    }
    if (sit != srcImports.end()) return true;
    SourceImport imp{};
    if (!importAHBToWinTex(imp.wt, ahb)) {
        RLOG_E("acquireSource: import failed for source %" PRId64, srcId);
        return false;
    }
    AHardwareBuffer_acquire(ahb);
    imp.ahb = ahb;
    srcImports.emplace(srcId, imp);
    RLOG("acquireSource: imported source %" PRId64 " img=%p (%dx%d, %zu imports)",
        srcId, (void*)imp.wt.img, imp.wt.w, imp.wt.h, srcImports.size());
    return true;
}

void VulkanRendererContext::showSourceLocked(int64_t contentId, int64_t srcId) {
    auto sit = srcImports.find(srcId);
    if (sit == srcImports.end()) return;
    SourceImport& src = sit->second;
    WinTex& wt = texMap[contentId];
    const int64_t previousSrc = wt.isAHB ? wt.srcId : 0;
    if (!wt.isAHB && wt.img!=VK_NULL_HANDLE) destroyWinTex(wt);
    if (previousSrc != srcId) src.users++;
    wt = WinTex{};
    wt.img  = src.wt.img; wt.mem  = src.wt.mem; wt.view = src.wt.view; wt.ds = src.wt.ds;
    wt.isAHB = true; wt.ahb = src.ahb; wt.srcId = srcId; wt.w = src.wt.w; wt.h = src.wt.h;
    wt.needsTransition = false; // The first-use layout belongs to the import.
    // After the new reference is taken: the old source may be a released pixmap that this
    // window was the last one showing.
    if (previousSrc != 0 && previousSrc != srcId) unrefSourceLocked(previousSrc);
}

void VulkanRendererContext::updateWindowContentAHB(int64_t contentId, int64_t srcId,
                                                   AHardwareBuffer* ahb, short, short, int, int) {
    if (!ahb || contentId==0 || srcId==0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    if (!acquireSourceLocked(srcId, ahb)) return;
    // A newer frame replaces anything still waiting for its slot.
    dropPendingLocked(contentId);
    showSourceLocked(contentId, srcId);
    guestFrameSerial_.fetch_add(1, std::memory_order_relaxed);
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::presentFrame(int64_t contentId, int64_t srcId, AHardwareBuffer* ahb,
                                         int64_t guestFrameNs, int64_t out[3]) {
    out[0] = out[1] = out[2] = 0;
    if (!ahb || contentId==0 || srcId==0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    if (!acquireSourceLocked(srcId, ahb)) return;
    const int64_t now = steadyNowNs();

    // Windows that stopped presenting (closed launcher, recreated swapchain) drop their state.
    if (pacers_.size() > 1) {
        for (auto it = pacers_.begin(); it != pacers_.end();) {
            if (it->first != contentId && now - it->second.lastPresentNs > 2000000000LL)
                it = pacers_.erase(it);
            else ++it;
        }
    }
    Pacer& pc = pacers_[contentId];
    pc.lastPresentNs = now;

    if (guestFrameNs >= 0 && guestFrameNs < 1000000000LL) {
        if (!pc.guestFrameValid) {
            pc.guestFrameMeanNs = (double)guestFrameNs; pc.guestFrameDevNs = guestFrameNs * 0.25;
        } else {
            const double d = (double)guestFrameNs - pc.guestFrameMeanNs;
            pc.guestFrameMeanNs += 0.1 * d;
            pc.guestFrameDevNs  += 0.1 * (std::fabs(d) - pc.guestFrameDevNs);
        }
        pc.guestFrameValid = true;
    }

    const int     fps    = fpsLimit_.load(std::memory_order_relaxed);
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    bool staged = false;

    if (fps > 0) {
        const int64_t spacing  = 1000000000LL / fps;
        const int64_t deadline = vsyncDeadlineNs_.load(std::memory_order_relaxed);
        const bool clockFresh  = deadline != 0 &&
            now - vsyncSeenNs_.load(std::memory_order_relaxed) < 250000000LL;
        // Hold-to-slot only when it means something: a live vsync clock and a limit below the
        // refresh rate. Frame generation too: its group of presents starts at the slot.
        const bool fg   = fgArmed_.load(std::memory_order_relaxed);
        const bool grid = clockFresh && period > 0 && spacing > period + period / 20;
        if (grid) {
            // Composite this long before the slot's queue deadline (GPU blit + submit; with
            // frame generation also the chain up to the first generated frame).
            const int64_t baseLead = std::clamp<int64_t>(period * 2 / 5, 3000000LL, 8000000LL);
            const int64_t lead = fg ? fgLeadNs(baseLead, spacing) : baseLead;
            const auto snap = [&](int64_t t) {
                return deadline + (int64_t)std::llround((double)(t - deadline) / (double)period) * period; };
            const auto ceilGrid = [&](int64_t t) {
                return deadline + (int64_t)std::ceil((double)(t - deadline) / (double)period) * period; };
            const int64_t earliest = now + lead + 500000LL;
            int64_t ideal = pc.idealSlotNs ? pc.idealSlotNs + spacing : 0;
            int64_t slot  = ideal ? snap(ideal) : 0;
            const bool late = ideal != 0 && slot < earliest;
            pc.lateHistory = (pc.lateHistory << 1) | (late ? 1u : 0u);
            // First frame or a late one (missed its slot): restart the cadence from the next
            // slot that can still be made.
            if (!ideal || late) { slot = ceilGrid(earliest); ideal = slot; }
            auto& q = pendingPresents_[contentId];
            if (q.size() >= 2) {
                // The guest got ahead (spare buffers at start-up): show the newest frame in
                // the last queued slot instead of letting the queue - and latency - grow.
                unrefSourceLocked(q.back().srcId);
                q.back().srcId = srcId;
                srcImports[srcId].users++;
                slot  = q.back().slotNs;
                ideal = pc.idealSlotNs;
            } else {
                q.push_back({srcId, slot - lead, slot});
                srcImports[srcId].users++;   // the pending entry keeps the import alive
                pc.idealSlotNs = ideal;
            }
            staged = true;
            const int64_t due = slot - lead;

            // One frame in flight while that makes its slots; two for a while after misses.
            const int misses = __builtin_popcount(pc.lateHistory & 0x3FFFFFFFu);   // last 30
            if (pc.pipelineDepth == 1 && misses >= 2) {
                pc.pipelineDepth = 2; pc.depthTwoUntilNs = now + 5000000000LL; pc.lateHistory = 0;
                RLOG("frame pacing: guest misses its slots, overlapping two frames for 5 s");
            } else if (pc.pipelineDepth == 2 && now > pc.depthTwoUntilNs) {
                pc.pipelineDepth = 1; pc.lateHistory = 0;
            }
            // The buffer given back now is drawn into for the next slot (depth 1) or the one
            // after (depth 2); it should come back just before that slot's composite.
            const int64_t target = snap(ideal + spacing * pc.pipelineDepth);
            const int64_t guestEst = pc.guestFrameValid
                ? (int64_t)(pc.guestFrameMeanNs + 3.0 * pc.guestFrameDevNs) : spacing / 2;
            const int64_t justInTime = target - lead - guestEst - 2000000LL;
            out[0] = std::max<int64_t>({justInTime, pc.lastReleaseNs + spacing * 95 / 100, now});
            out[1] = due + 1500000LL;   // previous frame off screen and its GPU reads done
            out[2] = pc.pipelineDepth;
        } else {
            // Release cadence only (no clock, or limit >= refresh). Two buffers may overlap so
            // a heavy guest keeps its CPU/GPU pipelining.
            pc.idealSlotNs = 0;
            out[0] = std::max(now, pc.lastReleaseNs + spacing);
            out[1] = 0;
            out[2] = 2;
        }
        pc.lastReleaseNs = out[0];
    } else {
        pc.idealSlotNs = 0;
        pc.lastReleaseNs = 0;
        pc.pipelineDepth = 1; pc.lateHistory = 0;
    }

    if (staged) {
        // Counted as a guest frame when it goes on screen (applyDuePresentsLocked): frame
        // generation takes a new source frame from these counters, and one counted now would
        // be composited with the previous content still showing.
        updateNextPendingDueLocked();
    } else {
        dropPendingLocked(contentId);
        showSourceLocked(contentId, srcId);
        // One publish, one wake: the content change and the present belong together (frame
        // generation paces on presents; two wakes could queue the same frame twice in FIFO).
        noteGuestFrameLocked(now);
        needsRender.store(true);
    }
    wakeRender();
}

void VulkanRendererContext::noteGuestFrameLocked(int64_t nowNs) {
    guestFrameSerial_.fetch_add(1, std::memory_order_relaxed);
    guestPresentNs_.store(nowNs, std::memory_order_relaxed);
    guestPresentSerial_.fetch_add(1, std::memory_order_relaxed);
}

int64_t VulkanRendererContext::fgLeadNs(int64_t baseLead, int64_t spacing) const {
    // The whole chain (shared passes + every generation) runs before the first present; a
    // margin over its smoothed cost covers frame-to-frame variance. Unknown cost: a guess that
    // fits mid-range GPUs at 1080p until the first timestamps are in.
    const int64_t cost = fgChainCostNs_.load(std::memory_order_relaxed);
    const int64_t chain = cost > 0 ? cost + cost / 4 + 1000000LL : 8000000LL;
    // Never so early that the slot is mostly spent waiting: at most 3/4 of the source interval.
    return std::clamp<int64_t>(baseLead + chain, baseLead, std::max(baseLead, spacing * 3 / 4));
}

void VulkanRendererContext::dropPendingLocked(int64_t contentId) {
    auto it = pendingPresents_.find(contentId);
    if (it == pendingPresents_.end()) return;
    std::deque<PendingPresent> q = std::move(it->second);
    pendingPresents_.erase(it);
    for (const auto& p : q) unrefSourceLocked(p.srcId);
    updateNextPendingDueLocked();
}

void VulkanRendererContext::applyDuePresentsLocked(int64_t nowNs) {
    if (pendingPresents_.empty()) return;
    // A wake can be a little early or late; anything due within the next 0.25 ms goes now.
    const int64_t horizon = nowNs + 250000LL;
    for (auto it = pendingPresents_.begin(); it != pendingPresents_.end();) {
        auto& q = it->second;
        while (!q.empty() && q.front().dueNs <= horizon) {
            const int64_t src = q.front().srcId;
            fgAnchorSlotNs_ = q.front().slotNs;
            q.pop_front();
            showSourceLocked(it->first, src);
            unrefSourceLocked(src);   // the pending entry's reference
            noteGuestFrameLocked(nowNs);
        }
        if (q.empty()) it = pendingPresents_.erase(it); else ++it;
    }
    updateNextPendingDueLocked();
}

void VulkanRendererContext::updateNextPendingDueLocked() {
    int64_t due = 0;
    for (const auto& [content, q] : pendingPresents_)
        if (!q.empty() && (due == 0 || q.front().dueNs < due)) due = q.front().dueNs;
    nextPendingDueNs_.store(due, std::memory_order_relaxed);
}

void VulkanRendererContext::setFpsLimit(int fps) {
    fps = std::clamp(fps, 0, 1000);
    if (fpsLimit_.exchange(fps, std::memory_order_relaxed) == fps) return;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        for (auto& [content, pc] : pacers_) pc.resetCadence();
        // Frames held for the old cadence are shown right away.
        for (auto& [content, q] : pendingPresents_)
            for (auto& p : q) p.dueNs = 1;   // in the past (0 means "none")
        updateNextPendingDueLocked();
    }
    RLOG("setFpsLimit: %d", fps);
    wakeRender();
}

int64_t VulkanRendererContext::fgSourceIntervalNs() const {
    // The pacer's measured guest rate; an FPS limit is only a floor - a guest that cannot
    // reach the limit delivers its frames further apart than the limit says.
    const int fps = fpsLimit_.load(std::memory_order_relaxed);
    const int64_t limitInterval = fps > 0 ? 1000000000LL / fps : 0;
    const float rate = lsfgEngine_ ? lsfgEngine_->sourceRate() : 0.0f;
    const int64_t measured = rate > 1.0f ? (int64_t)(1.0e9f / rate) : 0;
    return std::max(limitInterval, measured);
}

void VulkanRendererContext::fgPresentTimes(uint32_t presents, int64_t desired[],
                                           int64_t anchorSlotNs) const {
    for (uint32_t k = 0; k < presents; k++) desired[k] = 0;
    if (!displayTimingOk_ || presents == 0) return;
    const int64_t period   = refreshPeriodNs_.load(std::memory_order_relaxed);
    const int64_t interval = fgSourceIntervalNs();
    const int64_t deadline = vsyncDeadlineNs_.load(std::memory_order_relaxed);
    const int64_t now      = steadyNowNs();
    if (period <= 0 || interval <= 0 || deadline == 0 ||
        now - vsyncSeenNs_.load(std::memory_order_relaxed) > 250000000LL) return;
    // Only with a measured offset (frame timeline, API 33+). A guessed one that is off by more
    // than half a refresh moves every later present one refresh away from the first, which is
    // less even than plain back-to-back FIFO.
    const int64_t offset = vsyncPresentOffsetNs_.load(std::memory_order_relaxed);
    if (offset <= 0 || offset > 4 * period) return;
    const double step = (double)interval / (double)presents;
    // The first queue deadline the composite can still make. A frame held for a limiter slot
    // starts exactly there (unless the chain ran late), so the whole group is timed from it:
    // every source frame then starts on its own slot, and its presents fall on the same
    // refreshes of that slot each time. Without a slot only the spread matters, and only when
    // a present gets more than one refresh - back to back IS even otherwise, and holding
    // frames would only add latency.
    const int64_t lead = std::clamp<int64_t>(period * 2 / 5, 3000000LL, 8000000LL);
    const int64_t reachable = deadline +
        (int64_t)std::ceil((double)(now + lead - deadline) / (double)period) * period;
    // A slot whose queue deadline has already passed (the chain ran late) is not kept.
    const bool anchored = anchorSlotNs > now;
    if (!anchored && (presents < 2 || step < 1.5 * (double)period)) return;
    const int64_t first = (anchored ? anchorSlotNs : reachable) + offset;
    int64_t prevRefreshes = -1;
    for (uint32_t k = 0; k < presents; k++) {
        int64_t refreshes = (int64_t)std::llround(k * step / (double)period);
        // Two presents for one refresh would drop the earlier one at the compositor.
        refreshes = std::max(refreshes, prevRefreshes + 1);
        prevRefreshes = refreshes;
        // Half a refresh early: the compositor shows a buffer at the first refresh whose
        // expected presentation is not before the desired time, so this lands on the intended
        // refresh even if the estimate is off by up to half of one. Unanchored, the first goes
        // out as soon as it is ready.
        if (anchored || k > 0) desired[k] = first + refreshes * period - period / 2;
    }
}

void VulkanRendererContext::onVsync(int64_t frameTimeNs, int64_t deadlineNs, int64_t presentNs) {
    if (deadlineNs > 0 && presentNs > deadlineNs)
        vsyncPresentOffsetNs_.store(presentNs - deadlineNs, std::memory_order_relaxed);
    // Before API 33 there is no frame timeline: a frame queued before the next app vsync makes
    // the same display refresh as one queued right after this one, so that is the grid.
    const int64_t period = refreshPeriodNs_.load(std::memory_order_relaxed);
    const int64_t grid = deadlineNs > 0 ? deadlineNs : (period > 0 ? frameTimeNs + period : 0);
    if (grid <= 0) return;
    vsyncDeadlineNs_.store(grid, std::memory_order_relaxed);
    vsyncSeenNs_.store(steadyNowNs(), std::memory_order_relaxed);
}

void VulkanRendererContext::unrefSourceLocked(int64_t srcId) {
    auto it = srcImports.find(srcId);
    if (it == srcImports.end()) return;
    if (--it->second.users <= 0) {
        it->second.users = 0;
        if (it->second.released) retireSourceLocked(it);
    }
}

void VulkanRendererContext::retireSourceLocked(std::unordered_map<int64_t, SourceImport>::iterator it) {
    // Destroyed (and its AHB reference dropped) once the last frame that may have sampled it
    // has completed on the GPU - see renderFrame()'s deleteQueue sweep.
    WinTex deferred = it->second.wt;
    deferred.isAHB = false;
    deleteQueue.push_back({deferred, pendingFrameSerial ? pendingFrameSerial : submittedSerial,
                           it->second.ahb});
    retirePending.store(true, std::memory_order_release);
    srcImports.erase(it);
}

void VulkanRendererContext::eraseContentLocked(int64_t contentId) {
    dropPendingLocked(contentId);
    pacers_.erase(contentId);
    auto it = texMap.find(contentId);
    if (it == texMap.end()) return;
    if (it->second.isAHB) {
        const int64_t srcId = it->second.srcId;
        texMap.erase(it);
        unrefSourceLocked(srcId);
    } else {
        destroyWinTex(it->second);
        texMap.erase(it);
    }
}

void VulkanRendererContext::releaseDrawable(int64_t id) {
    if (id == 0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    // As window content (a destroyed window, or the old content of a resized one).
    eraseContentLocked(id);
    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    // As a buffer source (a freed pixmap, or the content above). A window may still be showing
    // it - e.g. the last frame of a swapchain the game just recreated - so it lives until that
    // window shows something else.
    auto sit = srcImports.find(id);
    if (sit != srcImports.end()) {
        sit->second.released = true;
        if (sit->second.users <= 0) retireSourceLocked(sit);
    }
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::setRenderList(const int64_t* ids, const int* xs, const int* ys, int count) {
    std::lock_guard<std::mutex> lk(renderMutex);
    renderList.resize(count);
    for (int i=0;i<count;i++) renderList[i]={ids[i],xs[i],ys[i]};
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);
    eraseContentLocked(id);
    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::initRootWindow(int64_t rootId, int64_t contentId, int width, int height) {
    std::lock_guard<std::mutex> lk(renderMutex);
    rootWindowId = rootId;
    treeScreenW = width; treeScreenH = height;
    WinNode node;
    node.id = rootId; node.parentId = 0; node.contentId = contentId;
    node.x = 0; node.y = 0; node.width = width; node.height = height;
    node.mapped = true; node.viewable = true;
    windowTree[rootId] = node;
    treeDirty = true;
}

void VulkanRendererContext::createWindowNode(int64_t id, int64_t parentId, int64_t contentId,
                                              int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lk(renderMutex);
    WinNode node;
    node.id = id; node.parentId = parentId; node.contentId = contentId;
    node.x = x; node.y = y; node.width = width; node.height = height;
    node.mapped = false; node.viewable = true;
    windowTree[id] = node;
    auto pit = windowTree.find(parentId);
    if (pit != windowTree.end()) pit->second.children.push_back(id);
    treeDirty = true;
}

void VulkanRendererContext::destroyWindowNode(int64_t id) {
    int64_t contentIdToFree = 0;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        auto it = windowTree.find(id);
        if (it == windowTree.end()) return;
        contentIdToFree = it->second.contentId;
        int64_t parentId = it->second.parentId;
        auto pit = windowTree.find(parentId);
        if (pit != windowTree.end()) {
            auto& ch = pit->second.children;
            ch.erase(std::remove(ch.begin(), ch.end(), id), ch.end());
        }
        windowTree.erase(it);
        treeDirty = true;
    }
    if (contentIdToFree != 0) removeWindow(contentIdToFree);
}

void VulkanRendererContext::mapWindowNode(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it = windowTree.find(id);
    if (it == windowTree.end()) return;
    it->second.mapped = true;
    treeDirty = true;
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::unmapWindowNode(int64_t id) {
    int64_t contentIdToFree = 0;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        auto it = windowTree.find(id);
        if (it == windowTree.end()) return;
        it->second.mapped = false;
        contentIdToFree = it->second.contentId;
        treeDirty = true;
    }

    if (contentIdToFree != 0) removeWindow(contentIdToFree);
}

void VulkanRendererContext::reparentWindowNode(int64_t id, int64_t newParentId) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it = windowTree.find(id);
    if (it == windowTree.end()) return;
    int64_t oldParentId = it->second.parentId;
    auto oldPit = windowTree.find(oldParentId);
    if (oldPit != windowTree.end()) {
        auto& ch = oldPit->second.children;
        ch.erase(std::remove(ch.begin(), ch.end(), id), ch.end());
    }
    it->second.parentId = newParentId;
    auto newPit = windowTree.find(newParentId);
    if (newPit != windowTree.end()) newPit->second.children.push_back(id);
    treeDirty = true;
}

void VulkanRendererContext::updateWindowGeometryNode(int64_t id, int64_t contentId,
                                                      int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it = windowTree.find(id);
    if (it == windowTree.end()) return;
    it->second.x = x; it->second.y = y;
    it->second.width = width; it->second.height = height;
    // A resize replaces the window's content drawable. Java releases the old one (which also
    // drops it here), but this call is queued and may arrive first or after: dropping the stale
    // texture here too keeps the two orders equivalent.
    if (it->second.contentId != contentId && it->second.contentId != 0)
        eraseContentLocked(it->second.contentId);
    it->second.contentId = contentId;
    treeDirty = true;
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::syncChildOrder(int64_t parentId, const int64_t* orderedChildIds, int count) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it = windowTree.find(parentId);
    if (it == windowTree.end()) return;
    it->second.children.assign(orderedChildIds, orderedChildIds + count);
    treeDirty = true;
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::setWindowViewable(int64_t id, bool viewable) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it = windowTree.find(id);
    if (it == windowTree.end()) return;
    it->second.viewable = viewable;
    treeDirty = true;
}

void VulkanRendererContext::collectWindowNodes(int64_t id, int absX, int absY,
                                                std::vector<RenderEntry>& out,
                                                std::vector<std::pair<int,int>>& outSizes) {
    auto it = windowTree.find(id);
    if (it == windowTree.end()) return;
    WinNode& node = it->second;
    if (!node.mapped) return;

    int myAbsX = absX, myAbsY = absY;
    if (id != rootWindowId) {
        myAbsX = absX + node.x;
        myAbsY = absY + node.y;
        // 1x1 helper windows are skipped, but their children still get collected below.
        bool renderable = node.width > 1 && node.height > 1;
        if (node.viewable && renderable) {
            out.push_back({node.contentId, myAbsX, myAbsY});
            outSizes.push_back({node.width, node.height});
        }
    }
    for (int64_t childId : node.children) {
        collectWindowNodes(childId, myAbsX, myAbsY, out, outSizes);
    }
}

void VulkanRendererContext::rebuildRenderListFromTree() {
    std::vector<RenderEntry> flat;
    std::vector<std::pair<int,int>> sizes;
    collectWindowNodes(rootWindowId, 0, 0, flat, sizes);

    int start = 0;
    for (int i = (int)flat.size() - 1; i >= 0; i--) {
        if (sizes[i].first >= treeScreenW && sizes[i].second >= treeScreenH) {
            start = i;
            break;
        }
    }

    renderList.assign(flat.begin() + start, flat.end());
}

void VulkanRendererContext::destroyAllSourceImports() {
    // Device idle (destructor only).
    for (auto& [srcId, imp] : srcImports) {
        WinTex& wt = imp.wt;
        if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, winTexPool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        if (imp.ahb) AHardwareBuffer_release(imp.ahb);
    }
    srcImports.clear();
}

void VulkanRendererContext::dumpRendererInfo() {
    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice,&props);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== RENDERER INFO ===");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "GPU: %s vendorID=0x%x driverVersion=0x%x apiVersion=%d.%d.%d",
        props.deviceName,props.vendorID,props.driverVersion,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Swapchain: %dx%d fmt=%d",swapchainExt.width,swapchainExt.height,(int)swapchainFmt);
    std::string pmList;
    for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "SupportedPresentModes: [%s] current=%d",pmList.c_str(),(int)requestedPresentMode);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Filter: mode=%d (%s)",
        filterMode, filterMode==3?"FSR1":filterMode==2?"SGSR":filterMode==1?"NEAREST":"LINEAR");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Pacing: refresh period %.3f ms, compositor queue priority %s",
        refreshPeriodNs_.load() / 1.0e6, globalPriorityHigh_.load() ? "HIGH" : "default");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
}

void VulkanRendererContext::setFilterMode(int mode) {
    // vkDeviceWaitIdle needs every queue externally synchronised, and the sampler and the
    // descriptor sets rewritten below are in use by the frame being recorded: run between
    // frames (frameMutex) and with the texture maps stable (renderMutex), the same order
    // renderFrame() takes them in.
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> mapLock(renderMutex);
    auto modeName=[](int m){ return m==1?"NEAREST":m==2?"SGSR":m==3?"FSR1":"LINEAR"; };
    RLOG("setFilterMode: %d -> %d (%s->%s)", filterMode, mode, modeName(filterMode), modeName(mode));
    if (filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }
    filterMode=mode;
    if (mode == 2 && sgsrPipeline == VK_NULL_HANDLE) createSgsrPipeline();
    if (mode == 3 && fsr1Pipeline == VK_NULL_HANDLE) createFsr1Pipeline();
    if (mode == 5 && postfxPipeline == VK_NULL_HANDLE) createPostFXPipeline();
    vk_.DeviceWaitIdle(device);
    if (sampler!=VK_NULL_HANDLE){vk_.DestroySampler(device,sampler,nullptr);sampler=VK_NULL_HANDLE;}
    createSampler();
    auto updateDS=[&](VkDescriptorSet ds, VkImageView view){
        if(ds==VK_NULL_HANDLE||view==VK_NULL_HANDLE) return;
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView=view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    };
    for (auto& [id,wt]:texMap) updateDS(wt.ds, wt.view);
    for (auto& [srcId,imp]:srcImports) updateDS(imp.wt.ds, imp.wt.view);
    if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) updateDS(cursorDS, cursorView);
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::setStretchMode(int mode) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    RLOG("setStretchMode: %d -> %d", stretchMode, mode);
    if (stretchMode == mode) return;
    stretchMode = mode;
    if (mode == 1 && stretchPipeline == VK_NULL_HANDLE) createStretchPipeline();
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::setSwapRB(bool enabled) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    if (swapRB == enabled) return;
    swapRB = enabled;
    RLOG("setSwapRB: %d", (int)swapRB);
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    // availablePresentModes is rewritten by createSwapchain() on the render thread.
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    bool supported = false;
    for (auto pm : availablePresentModes) if (pm == mode) { supported = true; break; }
    VkPresentModeKHR target = supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    RLOG("setPresentMode: requested=%d supported=%d -> applying=%d",(int)mode,(int)supported,(int)target);
    if (requestedPresentMode==target) { RLOG("setPresentMode: already set, skipping"); return; }
    requestedPresentMode=target;
    fbResized.store(true); wakeRender();
}

std::vector<int> VulkanRendererContext::getSupportedPresentModes() const {
    std::vector<int> out;
    for (auto pm:availablePresentModes) out.push_back((int)pm);
    return out;
}

void VulkanRendererContext::setCustomScissor(int x, int y, int w, int h) {
    std::lock_guard<std::mutex> lk(renderMutex);
    customScissor  = {{x, y}, {(uint32_t)std::max(0, w), (uint32_t)std::max(0, h)}};
    hasCustomScissor = true;
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::clearCustomScissor() {
    std::lock_guard<std::mutex> lk(renderMutex);
    hasCustomScissor = false;
    needsRender.store(true); wakeRender();
}

#pragma GCC diagnostic pop
