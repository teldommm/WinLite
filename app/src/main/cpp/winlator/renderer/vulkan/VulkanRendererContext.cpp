#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "VulkanRendererContext.h"
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <inttypes.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <chrono>

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

// Surface transforms the renderer knows how to pre-rotate for; anything else is drawn as IDENTITY.
VkSurfaceTransformFlagBitsKHR normalizePreTransform(VkSurfaceTransformFlagBitsKHR t) {
    switch (t) {
        case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            return t;
        default:
            return VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
}

// The surface's size in the display's own orientation (before pre-rotation).
VkExtent2D queryLogicalExtent(const VkSurfaceCapabilitiesKHR& caps, ANativeWindow* window,
                              int fallbackW, int fallbackH) {
    if (caps.currentExtent.width != 0xFFFFFFFF) return caps.currentExtent;
    if (window) {
        const int winW = ANativeWindow_getWidth(window);
        const int winH = ANativeWindow_getHeight(window);
        return {
            (uint32_t)std::clamp(winW, (int)caps.minImageExtent.width, (int)caps.maxImageExtent.width),
            (uint32_t)std::clamp(winH, (int)caps.minImageExtent.height, (int)caps.maxImageExtent.height)
        };
    }
    return {(uint32_t)fallbackW, (uint32_t)fallbackH};
}

// A/B switch for the FIFO queue depth: `setprop debug.winlite.swapchain_extra 1` keeps the spare
// swapchain image (minImageCount + 1), `0` drops it, unset lets the renderer decide. -1: unset.
int swapchainExtraImageOverride() {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.winlite.swapchain_extra", value) <= 0) return -1;
    if (value[0] == '1') return 1;
    if (value[0] == '0') return 0;
    return -1;
}

// The render thread paces presentation: a normal-priority thread loses its slot to guest threads
// that occupy every core, which shows up as frame-time jitter. Failing to raise the priority is
// harmless (the thread just keeps the default).
void boostRenderThread() {
    pthread_setname_np(pthread_self(), "WinLiteRender");
    errno = 0;
    const pid_t tid = (pid_t)syscall(SYS_gettid);
    if (setpriority(PRIO_PROCESS, (id_t)tid, -4) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "Winlator_Renderer",
            "render thread priority not raised (errno=%d)", errno);
    }
}
}

VulkanRendererContext::VulkanRendererContext(ANativeWindow* win, int cW, int cH,
                                             void* aHandle, bool enableValidation,
                                             const char* cacheDir)
    // cW/cH describe the X screen (the scene container), not necessarily the Android surface.
    // Direct output places SurfaceControl layers in real surface pixels; using cW/cH here makes
    // the first direct frame smaller whenever those sizes differ. surfaceChanged() may already
    // have happened while nativeInit() was still running, so it cannot be relied on to correct
    // these fields. Read the native window just as reattachSurface() does after an app resume.
    : window(win), surfaceWidth(ANativeWindow_getWidth(win)), surfaceHeight(ANativeWindow_getHeight(win)),
      containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle), validationRequested(enableValidation),
      cacheDirPath(cacheDir ? cacheDir : "")
{
    createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
    createSwapchain(); createRenderPass(); createDSLayout();
    fx.verboseLog = verboseLog;
    fx.create(&vk_, device, physicalDevice, renderPass, dsLayout, cacheDirPath);
    createFramebuffers(); createCmdPool(); createSampler();
    createWinTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
    fx.saveCache();   // the window pipelines built above are on disk from the first run on
    direct_ = std::make_unique<DirectServer>(*this);
    direct_->attach(window);
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; wakeRender(true);
    if (renderThread.joinable()) renderThread.join();
    // Its thread calls into this context: gone before anything here is.
    direct_.reset();
    std::lock_guard<std::mutex> lk(renderMutex);
    vk_.DeviceWaitIdle(device);
    destroyDirect();
    texMap.clear();   // aliases of the imported buffers freed below

    for (auto& wt : deleteQueue) freeWinTexVk(wt);
    deleteQueue.clear();
    for (auto& r : retiring) freeWinTexVk(r.tex);
    retiring.clear();
    // Imported window buffers: their Vulkan objects (the device is idle) and the references taken
    // at import. Nothing freed them when the renderer went away, so every re-created renderer
    // left all of its imports behind.
    cleanupAllAHBCache();
    cleanupSwapchain(); cleanupCursorTex();

    vk_.DestroySampler(device, sampler, nullptr);
    vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
    fx.destroy();   // pipelines, layout; saves the pipeline cache
    vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
    for (auto sem : renderDoneSems) vk_.DestroySemaphore(device, sem, nullptr);
    for (auto sem : imgAvailSems) vk_.DestroySemaphore(device, sem, nullptr);
    for (auto fence : inFlightFences) vk_.DestroyFence(device, fence, nullptr);
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
    vk_.DestroyDevice(device, nullptr);
    vk_.DestroySurfaceKHR(instance, surface, nullptr);
    // The reference taken by nativeInit or reattachSurface (ANativeWindow_fromSurface). Only
    // detachSurface() released it, so a renderer destroyed while attached - after a failed
    // re-attach, Java re-creates it - kept the window's native Surface alive.
    if (window) { ANativeWindow_release(window); window = nullptr; }
    if (validationMessenger != VK_NULL_HANDLE) {
        auto destroyMessenger = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
            gipa(instance, "vkDestroyDebugUtilsMessengerEXT"));
        if (destroyMessenger) destroyMessenger(instance, validationMessenger, nullptr);
        validationMessenger = VK_NULL_HANDLE;
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
    LOAD_D2(CreatePipelineCache);
    LOAD_D2(DestroyPipelineCache);
    LOAD_D2(GetPipelineCacheData);
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
    if (externalSemaphoreFd_) {
        vk_.GetSemaphoreFdKHR    = (PFN_vkGetSemaphoreFdKHR)d("vkGetSemaphoreFdKHR");
        vk_.ImportSemaphoreFdKHR = (PFN_vkImportSemaphoreFdKHR)d("vkImportSemaphoreFdKHR");
        externalSemaphoreFd_ = vk_.GetSemaphoreFdKHR && vk_.ImportSemaphoreFdKHR;
    }
    if (externalFenceFd_) {
        vk_.GetFenceFdKHR    = (PFN_vkGetFenceFdKHR)d("vkGetFenceFdKHR");
        vk_.ImportFenceFdKHR = (PFN_vkImportFenceFdKHR)d("vkImportFenceFdKHR");
        externalFenceFd_ = vk_.GetFenceFdKHR && vk_.ImportFenceFdKHR;
    }
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

    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface",
                       VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;

    // Opt-in validation layer. Nothing below runs unless it was asked for, and an instance that
    // cannot be created with it is created again without.
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
    bool hasSemaphoreFd = false;
    bool hasFenceFd = false;
    if (enumDevExts) {
        uint32_t n=0;
        if (enumDevExts(physicalDevice,nullptr,&n,nullptr)==VK_SUCCESS && n>0) {
            std::vector<VkExtensionProperties> av(n);
            if (enumDevExts(physicalDevice,nullptr,&n,av.data())==VK_SUCCESS) {
                for (auto& e:av) {
                    if (strcmp(e.extensionName, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)==0)
                        hasForeignQueue = true;
                    if (strcmp(e.extensionName, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)==0)
                        hasDisplayTiming = true;
                    if (strcmp(e.extensionName, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)==0)
                        hasSemaphoreFd = true;
                    if (strcmp(e.extensionName, VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME)==0)
                        hasFenceFd = true;
                }
            }
        }
    }
    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    // The AHB extension depends on VK_EXT_queue_family_foreign, so it is enabled with it whenever
    // the device offers it. A device that does not advertise it keeps working exactly as before.
    foreignQueueFamily_ = hasForeignQueue;
    if (hasForeignQueue) {
        extList.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    } else {
        RLOG_E("AHB imports want VK_EXT_queue_family_foreign, but the Vulkan device does not advertise it; continuing without it");
    }
    // The display clock of the X composite: when the presented images really reached the
    // display (see pollPresentTiming).
    displayTiming_ = hasDisplayTiming;
    if (hasDisplayTiming) extList.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
    RLOG("VK_GOOGLE_display_timing %s", hasDisplayTiming ? "enabled (display clock)" : "unavailable");
    // Sync files: the acquire / release fences of the buffers the Direct output exchanges with
    // SurfaceFlinger and the game (VK_KHR_external_semaphore/fence themselves are core in 1.1).
    externalSemaphoreFd_ = hasSemaphoreFd;
    externalFenceFd_ = hasFenceFd;
    if (hasSemaphoreFd) extList.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    if (hasFenceFd) extList.push_back(VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME);
    RLOG("sync files: semaphore fd %s, fence fd %s",
         hasSemaphoreFd ? "yes" : "no", hasFenceFd ? "yes" : "no");
    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();
    if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    if (displayTiming_) {
        getRefreshCycleGOOGLE_ = (PFN_vkGetRefreshCycleDurationGOOGLE)
            vk_.GetDeviceProcAddr(device, "vkGetRefreshCycleDurationGOOGLE");
        getPastPresentTimingGOOGLE_ = (PFN_vkGetPastPresentationTimingGOOGLE)
            vk_.GetDeviceProcAddr(device, "vkGetPastPresentationTimingGOOGLE");
    }
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
}

void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);

    const VkExtent2D logicalExt = queryLogicalExtent(caps, window, surfaceWidth, surfaceHeight);
    const VkSurfaceTransformFlagBitsKHR pre = normalizePreTransform(caps.currentTransform);
    if (pre != caps.currentTransform) {
        RLOG_E("createSwapchain: unexpected currentTransform (0x%x), using IDENTITY",
            (unsigned)caps.currentTransform);
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

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}

    // Image count. A FIFO queue is as deep as the images beyond the one on screen: the spare image
    // minImageCount + 1 adds a frame of latency whenever the game outruns the display, for no
    // gain in smoothness. Modes that can drop images (MAILBOX, IMMEDIATE) keep it.
    // debug.winlite.swapchain_extra forces either way.
    const bool fifoLike = presentMode==VK_PRESENT_MODE_FIFO_KHR || presentMode==VK_PRESENT_MODE_FIFO_RELAXED_KHR;
    bool spareImage = !fifoLike;
    const int extraOverride = swapchainExtraImageOverride();
    if (extraOverride >= 0) spareImage = extraOverride != 0;
    uint32_t imgCount=caps.minImageCount+(spareImage?1u:0u);
    if (caps.maxImageCount>0&&imgCount>caps.maxImageCount) imgCount=caps.maxImageCount;
    if (imgCount<caps.minImageCount) imgCount=caps.minImageCount;
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
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u (min=%u spare=%d override=%d)",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount,
        caps.minImageCount,(int)spareImage,extraOverride);
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
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

// Fx's window pipelines are built against this pass (VulkanRendererFx::create).
void VulkanRendererContext::createRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&renderPass)!=VK_SUCCESS) throw std::runtime_error("renderpass");
}

void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}

void VulkanRendererContext::setPostFXMode(int mode) {
    std::lock_guard<std::mutex> setterLock(setterMutex);
    RLOG("setPostFXMode: %d -> %d", fx.postFXMode, mode);
    if (fx.postFXMode == mode) return;
    // Compile first, without the frame lock: the render thread keeps drawing meanwhile. Only the
    // setters change the mode and the pipelines, and they run one at a time.
    VkPipeline builtFx = VK_NULL_HANDLE;
    if (fx.filterMode == 5 || (mode > 0 && !fx.upscalerActive()))
        builtFx = fx.prebuild(VulkanRendererFx::Kind::PostFx);
    {
        // Swaps pipelines the render thread may be recording with: exclusive frame lock.
        auto frameLock = lockFrameExclusive();
        fx.postFXMode = mode;
        fx.adopt(VulkanRendererFx::Kind::PostFx, builtFx);
        fx.syncPostFX();
        fxSerial++;
        needsRender.store(true); wakeRender();
    }
    fx.saveCache();
}

void VulkanRendererContext::setSharpness(float s) {
    fx.sharpness = std::clamp(s, 0.0f, 1.0f);
    fxSerial++;
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
    VkFilter filter = fx.samplesNearest() ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d)",
        fx.samplesNearest()?"NEAREST":"LINEAR", fx.filterMode);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    if (vk_.CreateSampler(device,&ci,nullptr,&sampler)!=VK_SUCCESS) throw std::runtime_error("sampler");
}

void VulkanRendererContext::createWinTexPool() {
    // One set per window texture / imported guest buffer and one for the cursor. 129 ran out
    // with many windows, and every texture past that failed: a black window. Sets are small.
    constexpr uint32_t kWinTexSets = 1024;
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kWinTexSets};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=kWinTexSets;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}

void VulkanRendererContext::createCursorDS() {
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=winTexPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}

void VulkanRendererContext::createCmdBufs() {
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=MAX_FRAMES_IN_FLIGHT;
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
    // renderDoneSems has one semaphore per swapchain image, so this must run whenever the
    // swapchain is rebuilt. Indexing it by frame slot reused a semaphore the presentation engine
    // could still be waiting on. Only called with the GPU idle on the old swapchain.
    for (auto sem : imgAvailSems) vk_.DestroySemaphore(device, sem, nullptr);
    for (auto sem : renderDoneSems) vk_.DestroySemaphore(device, sem, nullptr);
    imgAvailSems.assign(MAX_FRAMES_IN_FLIGHT, VK_NULL_HANDLE);
    renderDoneSems.assign(swapchainImages.size(), VK_NULL_HANDLE);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (auto& sem : imgAvailSems)
        if (vk_.CreateSemaphore(device,&si,nullptr,&sem)!=VK_SUCCESS) throw std::runtime_error("acquire semaphore");
    for (auto& sem : renderDoneSems)
        if (vk_.CreateSemaphore(device,&si,nullptr,&sem)!=VK_SUCCESS) throw std::runtime_error("present semaphore");
}

void VulkanRendererContext::cleanupSwapchain() {
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

void VulkanRendererContext::transition(VkCommandBuffer cb, VkImage img,
    VkImageLayout ol, VkImageLayout nl, VkAccessFlags sa, VkAccessFlags da,
    VkPipelineStageFlags ss, VkPipelineStageFlags dss)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=ol; b.newLayout=nl; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.srcAccessMask=sa; b.dstAccessMask=da;
    vk_.CmdPipelineBarrier(cb,ss,dss,0,0,nullptr,0,nullptr,1,&b);
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
        freeWinTexVk(wt); wt = {};
        return false;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    VkResult dsRes=vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY){
        RLOG_E("importAHBToWinTex: descriptor pool exhausted for AHB texture");
        freeWinTexVk(wt); wt = {};
        return false;
    }
    if (dsRes!=VK_SUCCESS){ freeWinTexVk(wt); wt = {}; return false; }

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
    wt.w=(int)desc.width;
    wt.h=(int)desc.height;
    wt.usage=desc.usage;
    return true;
}

void VulkanRendererContext::freeWinTexVk(const WinTex& wt) {
    if (wt.fb   != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, wt.fb, nullptr);
    if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, winTexPool, 1, &wt.ds);
    if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
    if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
    if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
}

// Render thread, renderMutex held. Everything in deleteQueue is out of texMap and the import
// cache, so no frame recorded from now on can name it; the frames submitted so far are the only
// ones that can still sample it.
void VulkanRendererContext::retireDeleteQueueLocked() {
    for (auto& wt : deleteQueue) retiring.push_back({wt, submitSerial});
    deleteQueue.clear();
}

// Render thread, renderMutex held. Frees what the GPU is provably done with, by polling the slot
// fences - the render thread is the only one that submits and resets them, so the status is
// stable. Frames complete in the order they were submitted per slot; a slot whose fence is still
// pending only vouches for the frames before its own.
// Every frame goes to graphicsQueue, and a fence signal covers everything submitted to the queue
// before it: a signaled slot vouches for its own frame and all earlier ones. A pending slot
// vouches for what had to complete before its frame could be submitted (its previous use, one
// round of slots earlier). The newest of these is the answer.
uint64_t VulkanRendererContext::completedSerialLocked() const {
    uint64_t done = 0;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        const bool signaled = i < inFlightFences.size() && vk_.GetFenceStatus &&
            vk_.GetFenceStatus(device, inFlightFences[i]) == VK_SUCCESS;
        const uint64_t s = slotSerial[i];
        const uint64_t vouched = signaled ? s : (s > MAX_FRAMES_IN_FLIGHT ? s - MAX_FRAMES_IN_FLIGHT : 0);
        done = std::max(done, vouched);
    }
    return done;
}

void VulkanRendererContext::reapRetiredLocked() {
    if (!retiring.empty() || !pendingReleases.empty()) {
        const uint64_t done = completedSerialLocked();
        size_t kept = 0;
        for (auto& r : retiring) {
            if (r.retireAfter <= done) freeWinTexVk(r.tex);
            else retiring[kept++] = r;
        }
        retiring.resize(kept);
        reapReleasesLocked(done);
    }
    hasRetired.store(!retiring.empty() || !deleteQueue.empty() || !pendingReleases.empty());
}

// ---------------------------------------------------------------- buffer releases
//
// xcore's Present gives a pixmap back to the game only when the renderer reports it is done
// with its buffer: no window shows it, and no submitted frame still samples it. A buffer is
// stamped with the serial of the last frame that samples it when that frame is recorded; once
// a window shows another buffer, the old one is reported as soon as that frame has completed
// (polled with the other retirements, never waited for).

void VulkanRendererContext::setHost(const XcoreHostApi* h) {
    if (h && h->version != XCORE_HOST_API_VERSION) {
        RLOG_E("xcore host API version %u (expected %u): buffer releases are not reported",
               h->version, XCORE_HOST_API_VERSION);
        h = nullptr;
    }
    host_.store(h, std::memory_order_release);
}

void VulkanRendererContext::reportReleased(AHardwareBuffer* ahb) {
    const XcoreHostApi* h = host_.load(std::memory_order_acquire);
    if (h && h->bufferReleased) h->bufferReleased(h->ctx, ahb);
}

bool VulkanRendererContext::shownLocked(AHardwareBuffer* ahb) const {
    for (const auto& [id, wt] : texMap) if (wt.ahb == ahb) return true;
    // SurfaceFlinger shows it, or may still read it (Direct).
    return direct_ && direct_->holds(ahb);
}

void VulkanRendererContext::noteDisplacedLocked(AHardwareBuffer* ahb) {
    if (!ahb || shownLocked(ahb)) return;   // another window still shows it
    for (const auto& p : pendingReleases) if (p.ahb == ahb) return;
    auto it = ahbImportCache.find(ahb);
    const uint64_t lastUse = it != ahbImportCache.end() ? it->second.lastUse : 0;
    if (lastUse <= completedSerialLocked()) {
        reportReleased(ahb);
        return;
    }
    pendingReleases.push_back({ahb, lastUse});
    hasRetired.store(true);   // the render loop polls the fences while something waits
}

void VulkanRendererContext::reapReleasesLocked(uint64_t done) {
    size_t kept = 0;
    for (auto& p : pendingReleases) {
        // Shown again meanwhile: whoever replaces it next reports it.
        if (shownLocked(p.ahb)) continue;
        if (p.after <= done) { reportReleased(p.ahb); continue; }
        pendingReleases[kept++] = p;
    }
    pendingReleases.resize(kept);
}

// Render thread, between frames: the render loop calls this while it has nothing to draw, so
// textures are released without waiting for the next frame.
void VulkanRendererContext::reapRetiredIdle() {
    std::lock_guard<std::mutex> lk(renderMutex);
    retireDeleteQueueLocked();
    reapRetiredLocked();
}

void VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return;
    cleanupCursorTex();
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_R8G8B8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    vk_.CreateImage(device,&ii,nullptr,&cursorImg);
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vk_.AllocateMemory(device,&ai,nullptr,&cursorMem); vk_.BindImageMemory(device,cursorImg,cursorMem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_R8G8B8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vk_.CreateImageView(device,&vi,nullptr,&cursorView);
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    cursorTexW=w; cursorTexH=h; cursorImgInit=false;
}

void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    for (uint32_t i=0;i<MAX_FRAMES_IN_FLIGHT;i++) {
        if (cursorStg[i]!=VK_NULL_HANDLE){
            vk_.DestroyBuffer(device,cursorStg[i],nullptr);vk_.FreeMemory(device,cursorStgM[i],nullptr);
            cursorStg[i]=VK_NULL_HANDLE;cursorStgM[i]=VK_NULL_HANDLE;cursorStgP[i]=nullptr;cursorStgC[i]=0;
        }
    }
    cursorTexW=0; cursorTexH=0; cursorImgInit=false;
}

// Only called for a slot whose fence has been waited, so the old buffer is no longer read.
void VulkanRendererContext::ensureCursorStaging(uint32_t slot, VkDeviceSize sz) {
    if (cursorStgC[slot]>=sz) return;
    if (cursorStg[slot]!=VK_NULL_HANDLE){
        vk_.DestroyBuffer(device,cursorStg[slot],nullptr);vk_.FreeMemory(device,cursorStgM[slot],nullptr);
        cursorStg[slot]=VK_NULL_HANDLE;cursorStgM[slot]=VK_NULL_HANDLE;cursorStgP[slot]=nullptr;cursorStgC[slot]=0;
    }
    createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,cursorStg[slot],cursorStgM[slot]);
    vk_.MapMemory(device,cursorStgM[slot],0,sz,0,&cursorStgP[slot]); cursorStgC[slot]=sz;
}

void VulkanRendererContext::renderLoop() {
    boostRenderThread();
    while (isRunning) {
        bool woken = true;
        { std::unique_lock<std::mutex> lk(dirtyMutex);
          auto pred = [this]{
              return !isRunning||(!surfaceDetached.load()&&(needsRender.load()||fbResized.load()))||cursorMoved.load(); };
          // While textures wait for the GPU to finish with them, look at the fences every few
          // milliseconds instead of sleeping until the next frame. Direct may want a frame at a
          // given time (hysteresis, hand-over to the composite).
          const uint64_t wakeAt = directWakeAtNs.load();
          auto limit = std::chrono::nanoseconds(INT64_MAX);
          if (hasRetired.load()) limit = std::chrono::milliseconds(8);
          if (wakeAt) {
              const uint64_t now = (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::steady_clock::now().time_since_epoch()).count();
              limit = std::min<std::chrono::nanoseconds>(limit, std::chrono::nanoseconds(wakeAt > now ? wakeAt - now : 0));
          }
          if (limit.count() == INT64_MAX) dirtyCV.wait(lk,pred);
          else woken = dirtyCV.wait_for(lk,limit,pred); }
        if (!isRunning) break;
        if (!woken) {
            const uint64_t wakeAt = directWakeAtNs.load();
            const uint64_t now = (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            if (wakeAt && wakeAt <= now) {
                directWakeAtNs.store(0);
                needsRender.store(true);
            } else {
                reapRetiredIdle();
                continue;
            }
        }
        // No swapchain guard here: renderFrame returns at once while the surface is detached
        // (clearing the wake flags) and is what retries a swapchain rebuild that failed.
        bool frameFailed = false;
        try { renderFrame(); }
        catch(...) {
            // A frame that threw after its acquire still holds the swapchain image it took
            // and leaves its acquire semaphore signaled, which the next acquire would reuse.
            // Rebuilding the swapchain and the sync objects (renderFrame's fbResized path)
            // releases them.
            fbResized.store(true);
            frameFailed = true;
        }
        if (frameFailed || (swapchainRebuildFailed && fbResized.load())) {
            // A failure that repeats must not rebuild, or retry, in a tight loop. The flag alone
            // is not enough: reattachSurface() builds a swapchain without clearing it.
            std::unique_lock<std::mutex> lk(dirtyMutex);
            dirtyCV.wait_for(lk,std::chrono::milliseconds(50),[this]{
                return !isRunning||surfaceDetached.load(); });
        }
    }
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);

    if (surfaceDetached.load(std::memory_order_acquire)) return;
    if (surfaceWidth==0||surfaceHeight==0) return;

    if (fbResized.load()) {
        for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
        // The fences cover the GPU work, not the presents still queued on the old swapchain:
        // their semaphores and images must not be destroyed while the presentation engine can
        // still use them.
        vk_.DeviceWaitIdle(device);
        cleanupSwapchain();
        bool ok=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();recreateSyncObjects();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);ok=true;}catch(...){}
        swapchainRebuildFailed = !ok;
        if (ok) fbResized.store(false);
        return;
    }

    if (currentFrame >= cmdBufs.size() || cmdBufs[currentFrame] == VK_NULL_HANDLE) return;

    // Direct output: SurfaceFlinger shows the game, and the composite presents nothing.
    if (direct_) {
        DirectServer::Candidate cand;
        {
            std::lock_guard<std::mutex> lk(renderMutex);
            if (treeDirty) { rebuildRenderListFromTree(); treeDirty = false; }
            cand = directCandidateLocked();
        }
        const uint64_t now = (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        const DirectServer::Plan plan = direct_->plan(cand, now);
        if (plan.wakeAtNs) {
            uint64_t cur = directWakeAtNs.load();
            while ((!cur || plan.wakeAtNs < cur) && !directWakeAtNs.compare_exchange_weak(cur, plan.wakeAtNs)) {}
        }
        if (!plan.composite) {
            renderDirect(plan, cand);
            return;
        }
        fxLastInput = nullptr;   // back in Direct + FX, the first pass always runs
    }

    bool currentFenceWaited = false;
    if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[currentFrame]) == VK_NOT_READY) {
        vk_.WaitForFences(device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX);
        currentFenceWaited = true;
    }

    uint32_t imgIdx;
    // Bounded wait: with UINT64_MAX a swapchain whose images never come back (surface being
    // torn down, a stalled compositor) blocks this thread - and detachSurface(), which waits
    // for the frame lock it holds - for good.
    VkResult res=vk_.AcquireNextImageKHR(device,swapchain,2000000000ULL,imgAvailSems[currentFrame],VK_NULL_HANDLE,&imgIdx);
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR){fbResized.store(true);return;}
    if (res==VK_TIMEOUT||res==VK_NOT_READY) {
        if (!surfaceDetached.load(std::memory_order_acquire)) { needsRender.store(true); wakeRender(); }
        return;
    }
    if (res!=VK_SUCCESS&&res!=VK_SUBOPTIMAL_KHR) return;
    if (imgIdx >= swapchainFBs.size() || imgIdx >= swapchainImages.size()) {
        RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
            imgIdx, swapchainFBs.size(), swapchainImages.size());
        return;
    }

    if (imgIdx >= renderDoneSems.size()) { fbResized.store(true); return; }
    if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
    if (imgInFlight[imgIdx]!=VK_NULL_HANDLE &&
        (!currentFenceWaited || imgInFlight[imgIdx] != inFlightFences[currentFrame])) {
        if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, imgInFlight[imgIdx]) == VK_NOT_READY)
            vk_.WaitForFences(device,1,&imgInFlight[imgIdx],VK_TRUE,UINT64_MAX);
    }
    imgInFlight[imgIdx]=inFlightFences[currentFrame];

    vk_.ResetCommandBuffer(cmdBufs[currentFrame],0);

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;
    VkRect2D effectiveScissor{{0,0},swapchainExt};

    {
        std::lock_guard<std::mutex> lk(renderMutex);

        // Textures other threads gave up are freed once the frames that could sample them have
        // completed: polled, never waited for, so the X thread's updates are not held up here.
        retireDeleteQueueLocked();
        reapRetiredLocked();

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curVis=cursorVisible.load();

        if (hasCustomScissor) effectiveScissor = rotateRectLogicalToIdentity(customScissor);

        if (treeDirty) { rebuildRenderListFromTree(); treeDirty = false; }

        frameDraws.clear();
        for (auto& re:renderList) {
            auto it=texMap.find(re.id);
            if (it==texMap.end()) continue;
            WinTex& wt=it->second;
            if (wt.ds==VK_NULL_HANDLE) continue;
            DrawEntry de{wt.img,wt.ds,re.x,re.y,wt.w,wt.h};
            de.ahb=wt.ahb;
            // Only a submitted frame consumes the flag (see below): a frame that is dropped
            // before the submit must not lose the UNDEFINED -> SHADER_READ_ONLY transition.
            if (wt.needsTransition) de.needsTransition=true;
            frameDraws.push_back(de);
        }
        // Every buffer this frame samples is in use until it completes. The frame gets the next
        // serial if it is submitted; if it is dropped, a later frame gets it (released a frame
        // late, never early).
        for (const auto& de : frameDraws) {
            if (!de.ahb) continue;
            auto cit = ahbImportCache.find(de.ahb);
            if (cit != ahbImportCache.end()) cit->second.lastUse = submitSerial + 1;
        }

        // A new cursor image is applied here, on the render thread, in step with the frame that
        // shows it. A different size replaces the texture, which frames still in flight may be
        // sampling: both slots are drained first (only when the cursor changes size). The staging
        // buffer is this slot's own, and this slot's fence was waited above.
        if (isCursorImageDirty.load() && cursorPendW>0 && cursorPendH>0 &&
            cursorPixels.size() >= (size_t)cursorPendW*cursorPendH) {
            if (cursorImg==VK_NULL_HANDLE || cursorTexW!=cursorPendW || cursorTexH!=cursorPendH) {
                for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
                ensureCursorTex(cursorPendW,cursorPendH);
            }
            if (cursorImg!=VK_NULL_HANDLE) {
                const VkDeviceSize csz=(VkDeviceSize)cursorPendW*cursorPendH*4;
                ensureCursorStaging(currentFrame,csz);
                if (cursorStgP[currentFrame]) {
                    memcpy(cursorStgP[currentFrame], cursorPixels.data(), (size_t)csz);
                    cursorHotX=cursorPendHotX; cursorHotY=cursorPendHotY;
                    hasCurUpload=true; curUpload=cursorStg[currentFrame];
                    isCursorImageDirty.store(false);
                }
            }
        }
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorTexW; curH=cursorTexH;
    }

    // The display clock: feedback from earlier presents, every frame.
    if (displayTiming_) pollPresentTiming();
    recordCmdBuf(cmdBufs[currentFrame],imgIdx,frameDraws,
        frameAhbTransitions,framePostUpload,
        curUpload,hasCurUpload,
        ox,oy,sx,sy,cw,ch,ptrX,ptrY,curHotX,curHotY,curW,curH,curVis,
        effectiveScissor);

    if (hasCurUpload) cursorImgInit=true;   // the copy recorded above takes the image out of UNDEFINED

    VkSemaphore waitSem=imgAvailSems[currentFrame], signalSem=renderDoneSems[imgIdx];
    const VkPipelineStageFlags waitStage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount=1; si.pWaitSemaphores=&waitSem; si.pWaitDstStageMask=&waitStage;
    si.commandBufferCount=1; si.pCommandBuffers=&cmdBufs[currentFrame];
    si.signalSemaphoreCount=1; si.pSignalSemaphores=&signalSem;

    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    if (vk_.QueueSubmit(graphicsQueue,1,&si,inFlightFences[currentFrame])!=VK_SUCCESS) {
        vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
        fbResized.store(true);   // the acquired image was never presented: rebuild
        return;
    }
    // The submit went through, so the transitions recorded in this frame will run on the queue
    // before any later frame: only now are they done. Matched by image (the window entries alias
    // the import cache's), so every alias of a transitioned buffer is cleared together.
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        // Under the lock: completedSerialLocked() reads these from other threads.
        slotSerial[currentFrame]=++submitSerial;   // this slot's fence now stands for frame #submitSerial
        bool any=false;
        for (const auto& de : frameDraws) if (de.needsTransition) { any=true; break; }
        if (any) {
            auto transitioned=[&](VkImage img) {
                for (const auto& de : frameDraws) if (de.needsTransition && de.img==img) return true;
                return false;
            };
            for (auto& [id, wt] : texMap) if (wt.needsTransition && transitioned(wt.img)) wt.needsTransition=false;
            for (auto& [ahb, wt] : ahbImportCache) if (wt.needsTransition && transitioned(wt.img)) wt.needsTransition=false;
        }
    }
    VkSwapchainKHR scs[]={swapchain};
    // With VK_GOOGLE_display_timing every present carries a presentID, so the display reports
    // when each image was shown (the display clock), and kAutoPresentTime (see there).
    VkPresentTimeGOOGLE ptime{};
    VkPresentTimesInfoGOOGLE ptimes{};
    ptimes.sType=VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
    ptimes.swapchainCount=1; ptimes.pTimes=&ptime;
    ptime.presentID=(uint32_t)(++presentSeq) & kPresentSeqMask;
    ptime.desiredPresentTime=kAutoPresentTime;
    VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.pNext=displayTiming_ ? &ptimes : nullptr;
    pi.waitSemaphoreCount=1; pi.pWaitSemaphores=&signalSem; pi.swapchainCount=1; pi.pSwapchains=scs; pi.pImageIndices=&imgIdx;

    res = vk_.QueuePresentKHR(graphicsQueue, &pi);
    if (res==VK_SUCCESS||res==VK_SUBOPTIMAL_KHR) presentedFrames.fetch_add(1,std::memory_order_relaxed);
    {
        const uint64_t stampNs = (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        std::lock_guard<std::mutex> g(presentStampMutex);
        presentStamp = { presentedFrames.load(std::memory_order_relaxed), stampNs };
        // Direct hands over to the composite once a composite frame is on the display.
        if (direct_) direct_->compositePresented(presentSeq, displayTiming_ ? lastShownSeq : 0, stampNs);
    }

    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR) fbResized.store(true);
    // SUBOPTIMAL only says the surface could be matched better. Some drivers return it on every
    // present, which would rebuild the swapchain (with a DeviceWaitIdle) on every frame: rebuild
    // only when the transform or the size really differ from what the swapchain was built for.
    else if (res==VK_SUBOPTIMAL_KHR && !swapchainStillMatchesSurface()) fbResized.store(true);
    currentFrame=(currentFrame+1)%MAX_FRAMES_IN_FLIGHT;
}

bool VulkanRendererContext::swapchainStillMatchesSurface() {
    using namespace std::chrono;
    const uint64_t now = (uint64_t)duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
    if (lastSuboptimalCheckNs != 0 && now - lastSuboptimalCheckNs < 250000000ULL) {
        suboptimalIgnored++;
        return true;
    }
    lastSuboptimalCheckNs = now;
    if (surface == VK_NULL_HANDLE) return true;

    VkSurfaceCapabilitiesKHR caps;
    if (vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &caps) != VK_SUCCESS)
        return true;   // a lost surface is reported as OUT_OF_DATE / SURFACE_LOST by the presents themselves

    const VkSurfaceTransformFlagBitsKHR pre = normalizePreTransform(caps.currentTransform);
    VkExtent2D ext = queryLogicalExtent(caps, window, surfaceWidth, surfaceHeight);
    if (pre == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR || pre == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)
        ext = { ext.height, ext.width };

    const bool same = pre == surfaceTransform &&
                      ext.width == swapchainExt.width && ext.height == swapchainExt.height;
    if (same) {
        if ((++suboptimalIgnored % 600) == 1)
            RLOG("present returned SUBOPTIMAL but transform/extent are unchanged (%" PRIu64 " ignored): not rebuilding",
                suboptimalIgnored);
        return true;
    }
    RLOG("present returned SUBOPTIMAL and the surface changed (transform 0x%x->0x%x, %ux%u->%ux%u): rebuilding",
        (unsigned)surfaceTransform, (unsigned)pre, swapchainExt.width, swapchainExt.height, ext.width, ext.height);
    return false;
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
    // The layers are children of the surface that goes away: they go first, and what they
    // showed comes back.
    if (direct_) direct_->detach();
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        for (auto& r : ring) { r.inSf = false; if (r.releaseFd >= 0) { close(r.releaseFd); r.releaseFd = -1; } }
    }
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
        // The guest buffers are untouched by a new surface/swapchain: their layout and contents
        // stay as they are, so no transition from UNDEFINED (which may discard them) is forced.
        // Flags still pending (a transition that has not been submitted yet) are kept.
        surfaceDetached.store(false, std::memory_order_release);
    }
    if (direct_) direct_->attach(window);
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

void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short stride, short hotX, short hotY, bool rgba) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    // No Vulkan call here: the render thread creates/replaces the texture and fills the staging
    // buffer when it has waited for the frames that may still use them (see renderFrame).
    cursorPixels.resize((size_t)w*h);
    const size_t srcStrideBytes=(size_t)std::max((int)stride,(int)w)*4;
    const size_t rowBytes=(size_t)w*4;
    const uint8_t* src=static_cast<const uint8_t*>(px);
    uint8_t* dst=reinterpret_cast<uint8_t*>(cursorPixels.data());
    for (int y=0;y<h;y++) {
        const uint8_t* s=src+(size_t)y*srcStrideBytes;
        uint8_t* d=dst+(size_t)y*rowBytes;
        if (rgba) { memcpy(d,s,rowBytes); continue; }
        for (size_t x=0;x<rowBytes;x+=4) { d[x]=s[x+2]; d[x+1]=s[x+1]; d[x+2]=s[x]; d[x+3]=s[x+3]; }
    }
    cursorPendW=w; cursorPendH=h; cursorPendHotX=hotX; cursorPendHotY=hotY;
    cursorImageSerial++;
    isCursorImageDirty.store(true); needsRender.store(true); wakeRender();
}

void VulkanRendererContext::updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short, short, int, int) {
    if (!ahb) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    auto cit = ahbImportCache.find(ahb);
    if (cit == ahbImportCache.end()) {
        WinTex tmp{};
        if (!importAHBToWinTex(tmp, ahb)) {
            RLOG_E("updateWindowContentAHB: import failed for id=%" PRId64, id);
            return;
        }
        AHardwareBuffer_acquire(ahb);
        ahbImportCache[ahb] = tmp;
        windowAhbs[id].push_back(ahb);
        cit = ahbImportCache.find(ahb);
        RLOG("updateWindowContentAHB: imported new AHB %p for id=%" PRId64 " (%dx%d)",
            (void*)ahb, id, tmp.w, tmp.h);
    }
    WinTex& src = cit->second;
    // A window that gets its first texture can start to hide what is under it (see
    // rebuildRenderListFromTree), so the draw list has to be rebuilt then.
    const auto prevTex = texMap.find(id);
    const bool hadTexture = prevTex != texMap.end() && prevTex->second.ds != VK_NULL_HANDLE;
    AHardwareBuffer* const previous = prevTex != texMap.end() ? prevTex->second.ahb : nullptr;
    WinTex& wt  = texMap[id];
    wt.img  = src.img; wt.mem  = src.mem; wt.view = src.view; wt.ds   = src.ds;
    wt.ahb  = ahb; wt.w = src.w; wt.h = src.h;
    if (src.needsTransition) { wt.needsTransition = true; src.needsTransition = false; }
    if (!hadTexture) treeDirty = true;
    // The buffer this window showed until now goes back to the game once no frame reads it.
    if (previous && previous != ahb) noteDisplacedLocked(previous);
    // Direct takes a window's buffers as they are only from a client that presents
    // alternating buffers; one drawn over again in place could not be shown that way.
    ContentInfo& ci = contentInfo[id];
    if (ci.last && ci.last != ahb) { ci.swaps++; ci.repeats = 0; }
    else if (ci.last == ahb) ci.repeats++;
    ci.last = ahb;
    ci.gen++;
    // This window may have just stopped showing a buffer that was only waiting for that.
    reapDeadAhbsLocked();
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);
    contentInfo.erase(id);
    auto it = texMap.find(id);
    if (it != texMap.end()) {
        texMap.erase(it);   // an alias: the imported buffer is freed with the import cache entry
        treeDirty = true;
    }
    auto wit = windowAhbs.find(id);
    if (wit != windowAhbs.end()) {
        // Buffers imported for this window go too - unless another window still shows one of
        // them (a pixmap presented to two windows): reapDeadAhbsLocked() keeps those.
        for (AHardwareBuffer* ahb : wit->second) ahbDead.insert(ahb);
        windowAhbs.erase(wit);
    }
    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    reapDeadAhbsLocked();
    needsRender.store(true); wakeRender();
}

void VulkanRendererContext::releaseAhb(AHardwareBuffer* ahb) {
    if (!ahb) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    // Never imported (or already dropped): nothing of ours to free, and nothing may be marked -
    // the pointer could be reused by a later allocation.
    if (ahbImportCache.find(ahb) == ahbImportCache.end()) return;
    ahbDead.insert(ahb);
    reapDeadAhbsLocked();
    needsRender.store(true); wakeRender();
}

// Frees every dead imported buffer that no window shows any more. A window that still shows one
// keeps it (its last frame stays on screen) until updateWindowContentAHB() points it elsewhere
// or removeWindow() drops it. Texture entries whose window content id is not used by any window
// node (the old content of a resized window) cannot be shown again and are dropped here.
void VulkanRendererContext::reapDeadAhbsLocked() {
    if (ahbDead.empty()) return;

    std::unordered_set<int64_t> liveContent;
    for (auto& [wid, node] : windowTree) liveContent.insert(node.contentId);

    for (auto it = texMap.begin(); it != texMap.end(); ) {
        const WinTex& wt = it->second;
        if (wt.ahb && ahbDead.count(wt.ahb) && !liveContent.count(it->first)) {
            it = texMap.erase(it);
            treeDirty = true;
        }
        else ++it;
    }

    std::unordered_set<AHardwareBuffer*> shown;
    for (auto& [id, wt] : texMap) if (wt.ahb) shown.insert(wt.ahb);

    for (auto it = ahbDead.begin(); it != ahbDead.end(); ) {
        AHardwareBuffer* ahb = *it;
        if (shown.count(ahb)) { ++it; continue; }
        auto cit = ahbImportCache.find(ahb);
        if (cit != ahbImportCache.end()) {
            deleteQueue.push_back(cit->second);        // freed once the frames that may sample it are done
            hasRetired.store(true);
            ahbImportCache.erase(cit);
            // xcore dropped the drawable: nobody waits for its release, and the pointer may be
            // reused by a later buffer.
            pendingReleases.erase(std::remove_if(pendingReleases.begin(), pendingReleases.end(),
                [ahb](const PendingRelease& p) { return p.ahb == ahb; }), pendingReleases.end());
            AHardwareBuffer_release(ahb);              // the reference taken at import
        }
        for (auto& [wid, list] : windowAhbs)
            list.erase(std::remove(list.begin(), list.end(), ahb), list.end());
        it = ahbDead.erase(it);
    }
}

void VulkanRendererContext::initRootWindow(int64_t rootId, int64_t contentId, int width, int height) {
    std::lock_guard<std::mutex> lk(renderMutex);
    // Only called by the X server's replay of its window tree (a fresh renderer, a new server),
    // which re-sends every window right after: start from an empty tree, so nodes created by
    // window events queued ahead of the replay are rebuilt from the server's current state, not
    // merged with it.
    windowTree.clear();
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
    // An X id is only reused after DestroyWindow, which erases the node first. A node that
    // already exists therefore means a CreateWindow queued behind the window-tree replay, which
    // already built it from newer state: re-creating it would wipe its children and map state
    // and list it twice in its parent.
    if (windowTree.count(id)) return;
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
    it->second.contentId = contentId;
    treeDirty = true;
    // A resized window shows new content now; buffers of the old one may be free to go.
    reapDeadAhbsLocked();
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
        // Only 1x1 helper windows are skipped; a 1xN or Nx1 window is real content. Their
        // children still get collected below.
        const bool renderable = node.width >= 1 && node.height >= 1 &&
                                !(node.width == 1 && node.height == 1);
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

    // A full-screen window hides everything under it, but only once it has something to show:
    // without a texture it draws nothing, and culling the windows below it left a black frame.
    int start = 0;
    for (int i = (int)flat.size() - 1; i >= 0; i--) {
        if (sizes[i].first >= treeScreenW && sizes[i].second >= treeScreenH) {
            auto tit = texMap.find(flat[i].id);
            if (tit == texMap.end() || tit->second.ds == VK_NULL_HANDLE) continue;
            start = i;
            break;
        }
    }

    renderList.assign(flat.begin() + start, flat.end());
}

void VulkanRendererContext::cleanupAllAHBCache() {
    for (auto& [ahb, wt] : ahbImportCache) {
        freeWinTexVk(wt);
        AHardwareBuffer_release(ahb);
    }
    ahbImportCache.clear(); windowAhbs.clear();
    pendingReleases.clear();
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
        "Filter: mode=%d (%s)", fx.filterMode, VulkanRendererFx::filterName(fx.filterMode));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
}

void VulkanRendererContext::setFilterMode(int mode) {
    // One setter at a time. The shader compiles run before the frame lock is taken, so the render
    // thread keeps drawing while the driver works; under the lock there are only pointer swaps.
    using Kind = VulkanRendererFx::Kind;
    std::lock_guard<std::mutex> setterLock(setterMutex);
    RLOG("setFilterMode: %d -> %d (%s->%s)", fx.filterMode, mode,
         VulkanRendererFx::filterName(fx.filterMode), VulkanRendererFx::filterName(mode));
    if (fx.filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }

    Kind kind = Kind::Sgsr;
    const bool isUpscaler = VulkanRendererFx::upscalerKindFor(mode, kind);
    VkPipeline built = isUpscaler ? fx.prebuild(kind) : VK_NULL_HANDLE;
    const bool upscalerBuilt = isUpscaler && (built != VK_NULL_HANDLE || fx.has(kind));
    VkPipeline builtFx = VK_NULL_HANDLE;
    if (mode == 5 || (fx.postFXMode > 0 && !upscalerBuilt)) builtFx = fx.prebuild(Kind::PostFx);

    {
        // Replaces pipelines/sampler the render thread may be recording with: between frames.
        auto frameLock = lockFrameExclusive();
        std::lock_guard<std::mutex> mapLock(renderMutex);
        // Only the nearest/linear choice lives in the sampler: the other modes share one.
        const bool samplerChanges = (fx.filterMode == 1) != (mode == 1);
        fx.filterMode=mode;
        if (isUpscaler) fx.adopt(kind, built);
        fx.adopt(Kind::PostFx, builtFx);
        fx.syncPostFX();   // waits for the GPU itself if it releases the postfx pipeline
        if (samplerChanges) {
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
            for (auto& [ahb,wt]:ahbImportCache) updateDS(wt.ds, wt.view);
            if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) updateDS(cursorDS, cursorView);
            for (auto& [ahb,wt]:directImports) updateDS(wt.ds, wt.view);
        }
        fxSerial++;
        needsRender.store(true); wakeRender();
    }
    fx.saveCache();
}

void VulkanRendererContext::setStretchMode(int mode) {
    std::lock_guard<std::mutex> setterLock(setterMutex);
    RLOG("setStretchMode: %d -> %d", fx.stretchMode, mode);
    if (fx.stretchMode == mode) return;
    VkPipeline built = mode == 1 ? fx.prebuild(VulkanRendererFx::Kind::Stretch) : VK_NULL_HANDLE;
    {
        auto frameLock = lockFrameExclusive();
        fx.stretchMode = mode;
        fx.adopt(VulkanRendererFx::Kind::Stretch, built);
        fxSerial++;
        needsRender.store(true); wakeRender();
    }
    fx.saveCache();
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
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

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis,
    VkRect2D scissorRect)
{
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb, &bi) != VK_SUCCESS)
        throw std::runtime_error("begin cb");

    ahbTransitions.clear();
    postUpload.clear();

    // A guest buffer is written outside this device (the guest's driver, another API in another
    // process). With VK_EXT_queue_family_foreign it is acquired from the foreign queue family on
    // every frame that samples it and released back after the render pass, as Android's
    // RenderEngine does with its AHB textures; without the acquire the driver does not have to
    // make the producer's writes visible (or resolve the layout they left), and what the shader
    // reads is undefined. The first acquire comes from UNDEFINED, as nothing is known about the
    // image yet; later ones from the layout it was released in. A device without the extension
    // keeps the one-time transition.
    for (auto& d : draws) {
        if (d.img == VK_NULL_HANDLE) continue;
        if (!foreignQueueFamily_ && !d.needsTransition) continue;
        auto dup = std::find_if(ahbTransitions.begin(), ahbTransitions.end(),
            [&](const VkImageMemoryBarrier& o) { return o.image == d.img; });
        if (dup != ahbTransitions.end()) {   // the same buffer drawn twice: one acquire per frame
            if (d.needsTransition) dup->oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            continue;
        }
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = d.needsTransition ? VK_IMAGE_LAYOUT_UNDEFINED
                                        : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        if (foreignQueueFamily_) {
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            b.dstQueueFamilyIndex = graphicsQueueFamilyIndex;
        } else {
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        }
        b.image = d.img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        ahbTransitions.push_back(b);
    }

    if (!ahbTransitions.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    }
    const bool hasCursorCopy = hasCursorUpload && cursorImg != VK_NULL_HANDLE &&
                               cursorUpload != VK_NULL_HANDLE;
    // Not drawn before its first upload: a fresh texture holds nothing yet.
    const bool cursorDrawn = curVis && cursorImg != VK_NULL_HANDLE && cursorDS != VK_NULL_HANDLE &&
                             (cursorImgInit || hasCursorCopy);
    if (hasCursorCopy) {
        const bool fresh = !cursorImgInit;   // never written: UNDEFINED, nothing to wait for
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = cursorImg;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = fresh ? 0 : VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, fresh ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);

        VkBufferImageCopy r{};
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)curW, (uint32_t)curH, 1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg,
                                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);

        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)postUpload.size(), postUpload.data());
    }

    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = swapchainFBs[imgIdx];
    rpi.renderArea = {{0, 0}, swapchainExt};
    VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clear;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{0, 0, (float)swapchainExt.width, (float)swapchainExt.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &viewport);

    int32_t scX = std::max(0, scissorRect.offset.x);
    int32_t scY = std::max(0, scissorRect.offset.y);
    uint32_t maxW = swapchainExt.width > (uint32_t)scX ? swapchainExt.width - (uint32_t)scX : 0u;
    uint32_t maxH = swapchainExt.height > (uint32_t)scY ? swapchainExt.height - (uint32_t)scY : 0u;
    VkRect2D scissor{{scX, scY},
        {std::min(scissorRect.extent.width, maxW), std::min(scissorRect.extent.height, maxH)}};
    vk_.CmdSetScissor(cb, 0, 1, &scissor);

    float rotCosR, rotSinR;
    getPreRotationCosSin(rotCosR, rotSinR);

    // The effect in force for every window; the cursor never gets one.
    fx.bind(cb);
    for (auto& d : draws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        VulkanRendererFx::Target t{};
        t.x0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
        t.x1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
        t.y0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
        t.y1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
        t.extent = swapchainExt;
        t.cosR = rotCosR;
        t.sinR = rotSinR;
        fx.draw(cb, VulkanRendererFx::Input{d.ds, d.w, d.h}, t);
    }

    if (cursorDrawn) {
        // The image goes at pointer - hot spot even when that is off the top or left edge, as
        // an X server draws it; clamping it to 0 moved a cursor whose hot spot is not its corner
        // (I-beam, crosshair) away from the pointer near those edges.
        float cx = (float)((int)ptrX - curHotX);
        float cy = (float)((int)ptrY - curHotY);
        VulkanRendererFx::Target t{};
        t.x0 = (ox + cx * sx) / cw * 2.0f - 1.0f;
        t.y0 = (oy + cy * sy) / ch * 2.0f - 1.0f;
        t.x1 = (ox + (cx + curW) * sx) / cw * 2.0f - 1.0f;
        t.y1 = (oy + (cy + curH) * sy) / ch * 2.0f - 1.0f;
        t.extent = swapchainExt;
        t.cosR = rotCosR;
        t.sinR = rotSinR;
        fx.drawCursor(cb, cursorDS, t);
    }

    vk_.CmdEndRenderPass(cb);
    if (foreignQueueFamily_ && !ahbTransitions.empty()) {
        // Hand the guest buffers back to the foreign queue family, layout unchanged. Reads need
        // no access mask; the fragment-stage dependency orders the release after the sampling.
        for (auto& b : ahbTransitions) {
            b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex = graphicsQueueFamilyIndex;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            b.srcAccessMask = 0;
            b.dstAccessMask = 0;
        }
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    }

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed status=%d draws=%zu mode=%d",
               (int)endStatus, draws.size(), fx.filterMode);
        throw std::runtime_error("end cb");
    }
}

// ---------------------------------------------------------------- display clock (X composite)

namespace {
uint64_t monotonicNowNs() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

void VulkanRendererContext::setDisplayRefreshHz(float hz) {
    const uint32_t mhz = hz > 0.f ? (uint32_t)std::lround(hz * 1000.f) : 0u;
    if (refreshHintMhz.exchange(mhz) != mhz) RLOG("display refresh %.2f Hz", hz);
}

uint64_t VulkanRendererContext::refreshPeriodNs() const {
    const uint32_t mhz = refreshHintMhz.load();
    const uint64_t hint = mhz > 1000 ? (uint64_t)(1.0e12 / (double)mhz) : 16666667ull;
    // The swapchain's own refresh cycle is exact (Display.getRefreshRate is rounded), but it is
    // taken only while it agrees with the mode Java reports: some drivers keep the value the
    // swapchain was created with after a mode switch.
    const uint64_t sc = swapchainRefreshNs.load();
    if (sc && sc * 100 >= hint * 97 && sc * 100 <= hint * 103) return sc;
    return hint;
}

// The full sequence number of a presentID from the last 2^30 presents.
uint64_t VulkanRendererContext::seqOfPresentId(uint32_t id) const {
    const uint32_t low = id & kPresentSeqMask;
    const uint32_t cur = (uint32_t)presentSeq & kPresentSeqMask;
    return presentSeq - (uint64_t)((cur - low) & kPresentSeqMask);
}

// Reads VK_GOOGLE_display_timing feedback (every frame, from renderFrame): when images really
// reached the display, and (twice a second, or on a mode change) the exact refresh cycle. The
// latest such time anchors the refresh grid. Android reports an image only some presents after
// it was issued.
void VulkanRendererContext::pollPresentTiming() {
    const uint64_t now = monotonicNowNs();
    const uint32_t mhz = refreshHintMhz.load();
    if (getRefreshCycleGOOGLE_ &&
        (mhz != refreshPollMhz || !refreshPollNs || now - refreshPollNs >= 500000000ull)) {
        refreshPollMhz = mhz;
        refreshPollNs = now;
        VkRefreshCycleDurationGOOGLE rc{};
        if (getRefreshCycleGOOGLE_(device, swapchain, &rc) == VK_SUCCESS && rc.refreshDuration > 0)
            swapchainRefreshNs.store(rc.refreshDuration);
    }
    // A new period (the panel switched modes): the grid belongs to the old mode. Feedback for
    // images presented before the switch must not anchor the new one.
    const uint64_t oldPeriod = clock_.period();
    if (clock_.setPeriod(refreshPeriodNs())) {
        modeSeq = presentSeq;
        __android_log_print(ANDROID_LOG_INFO, WLOG_TAG, "Display clock: refresh %.3f -> %.3f ms, grid dropped",
                            (double)oldPeriod / 1.0e6, (double)clock_.period() / 1.0e6);
    }
    VkPastPresentationTimingGOOGLE past[16];
    for (int round = 0; getPastPresentTimingGOOGLE_ && round < 4; round++) {
        uint32_t count = 16;
        const VkResult r = getPastPresentTimingGOOGLE_(device, swapchain, &count, past);
        if (r != VK_SUCCESS && r != VK_INCOMPLETE) break;
        for (uint32_t i = 0; i < count; i++) {
            const VkPastPresentationTimingGOOGLE& t = past[i];
            if (!t.actualPresentTime) continue;   // dropped, or no present fence
            const uint64_t seq = seqOfPresentId(t.presentID);
            if (seq > lastShownSeq) lastShownSeq = seq;
            if (seq <= modeSeq) continue;
            clock_.noteShown(t.actualPresentTime);
        }
        if (r != VK_INCOMPLETE) break;
    }
}

bool VulkanRendererContext::getDisplayClock(int64_t out[3]) const {
    return clock_.get(monotonicNowNs(), out);
}

#pragma GCC diagnostic pop
