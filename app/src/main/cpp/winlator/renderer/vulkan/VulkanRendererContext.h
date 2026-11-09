#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <cstddef>
#include <vulkan/vulkan_android.h>

#include "vkr_dis.h"

struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreatePipelineCache CreatePipelineCache;
    PFN_vkDestroyPipelineCache DestroyPipelineCache;
    PFN_vkGetPipelineCacheData GetPipelineCacheData;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <algorithm>
#include <utility>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;

struct WindowPushConstants      { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float _pad[7];
                                  float cosR = 1.f, sinR = 0.f; };
struct WindowPushConstantsSGSR  { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float invSrcW, invSrcH, srcW, srcH;
                                  int   effectId;
                                  float resW;
                                  float sharpness;
                                  float cosR = 1.f, sinR = 0.f; };
struct WindowPushConstantsStretch { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha; float strength; float profile;
                                  float _pad[5];
                                  float cosR = 1.f, sinR = 0.f; };

struct WindowPushConstantsFSR1  { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float srcW, srcH, outW, outH;
                                  int   effectId;
                                  float sharpness;
                                  float _pad0;
                                  float cosR = 1.f, sinR = 0.f; };

struct WindowPushConstantsPostFX  { float ndcX0, ndcY0, ndcX1, ndcY1;
                                    int   effectId;
                                    float sharpness;
                                    float resW, resH;
                                    int   postFxId;   // effect 1..4 on top of ColorBoost
                                    float _pad[3];
                                    float cosR = 1.f, sinR = 0.f; };

static_assert(offsetof(WindowPushConstants,       cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsSGSR,   cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsStretch,cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsFSR1,   cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(sizeof(WindowPushConstantsFSR1) == sizeof(WindowPushConstantsSGSR), "FSR1 push constants must fit the shared pipeline layout range");
static_assert(offsetof(WindowPushConstantsPostFX, cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsPostFX, postFxId) == 32, "postFxId offset must match window_postfx.frag");

class VulkanRendererContext {
public:
    // cacheDir: where the VkPipelineCache is kept between runs (null/empty: not persisted).
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight,
                          void* adrenotoolsHandle = nullptr,
                          bool validationEnabled = false,
                          const char* cacheDir = nullptr);
    ~VulkanRendererContext();

    // The pipelines built on demand for the effect setters (the base window pipelines are not here).
    enum class PipelineKind { Sgsr, Fsr1, Lanczos, SgsrHq, Nis, Stretch, PostFx };

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    // The cursor texture is R8G8B8A8; rgba=false means the source is B8G8R8A8 and gets swapped.
    void updateCursorImage(void* pixels, short w, short h, short stride, short hotX, short hotY, bool rgba);
    void setCursorVisible(bool visible);
    void removeWindow(int64_t id);
    // The Java side is done with this buffer (pixmap freed, window content replaced or destroyed).
    // Drops the renderer's import of it as soon as no window still shows it; the GPU may still be
    // sampling it, so the Vulkan objects go through deleteQueue. Safe to call for a buffer that was
    // never imported, or one that is already gone.
    void releaseAhb(AHardwareBuffer* ahb);
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setStretchMode(int mode);
    void setPostFXMode(int mode);
    void setSharpness(float s);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;

    // WFG frame generation (VulkanRendererWfg.cpp). flowSide: pixels on the shorter side of
    // the frame the motion is analysed at; multiplier 2..4 fixes the output at that many frames
    // per game frame, 0 chases targetFps instead (0 = the display refresh rate).
    void setWfgEnabled(bool enabled);
    void setWfgConfig(int flowSide, int targetFps, int multiplier, float refreshHz);
    uint64_t getWfgGeneratedFrames() const { return wfgMadeFrames.load(std::memory_order_relaxed); }
    uint64_t getPresentedFrames() const { return presentedFrames.load(std::memory_order_relaxed); }

    void setCustomScissor(int x, int y, int w, int h);
    void clearCustomScissor();
    inline void setTransformAndScissor(float ox, float oy, float sx, float sy,
                                       bool hasScissor,
                                       int scissorX, int scissorY, int scissorW, int scissorH) {
        setTransform(ox, oy, sx, sy);
        if (hasScissor) setCustomScissor(scissorX, scissorY, scissorW, scissorH);
        else            clearCustomScissor();
    }

private:
    // A window texture is an imported guest buffer (AHB); its Vulkan objects live in ahbImportCache
    // and texMap entries alias them.
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        int                  w              = 0;
        int                  h              = 0;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
    };

    struct WinNode {
        int64_t id       = 0;
        int64_t parentId = 0;
        int64_t contentId = 0;
        int     x = 0, y = 0;
        int     width = 0, height = 0;
        bool    mapped   = false;
        bool    viewable = true;
        std::vector<int64_t> children;
    };

    std::unordered_map<int64_t, WinNode> windowTree;
    int64_t rootWindowId = 0;
    int     treeScreenW = 0, treeScreenH = 0;
    bool    treeDirty = true;

public:
    void initRootWindow(int64_t rootId, int64_t contentId, int width, int height);
    void createWindowNode(int64_t id, int64_t parentId, int64_t contentId, int x, int y, int width, int height);
    void destroyWindowNode(int64_t id);
    void mapWindowNode(int64_t id);
    void unmapWindowNode(int64_t id);
    void reparentWindowNode(int64_t id, int64_t newParentId);
    void updateWindowGeometryNode(int64_t id, int64_t contentId, int x, int y, int width, int height);
    void syncChildOrder(int64_t parentId, const int64_t* orderedChildIds, int count);
    void setWindowViewable(int64_t id, bool viewable);

private:
    void collectWindowNodes(int64_t id, int absX, int absY,
                             std::vector<RenderEntry>& out,
                             std::vector<std::pair<int,int>>& outSizes);
    void rebuildRenderListFromTree();
public:

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    // Opt-in VK_LAYER_KHRONOS_validation (WINLATOR_VULKAN_VALIDATION=1); off unless asked for.
    bool validationRequested = false;
    std::string cacheDirPath;
    VkDebugUtilsMessengerEXT validationMessenger = VK_NULL_HANDLE;
    // VK_EXT_queue_family_foreign is enabled: guest buffers are acquired from and released to the
    // foreign queue family on every frame that samples them (recordCmdBuf).
    bool foreignQueueFamily_ = false;
    int filterMode  = 0;
    int stretchMode = 0;
    int postFXMode  = 0;
    float stretchStrength = 0.40f;
    float stretchProfile  = 0.60f;
    float sharpness = 0.5f;
    float maxAnisotropy           = 1.0f;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;
    // Imported buffers nobody wants any more (owner window gone / Java released them). They are
    // dropped as soon as no texMap entry still points at them, so the last frame stays visible
    // until the window shows something else.
    std::unordered_set<AHardwareBuffer*>                       ahbDead;

    // Textures other threads gave up (under renderMutex). The render thread stamps them with the
    // number of frames submitted so far - every frame that can still sample them - and frees them
    // once those frames' fences have signaled; nothing ever waits for the GPU on their account.
    std::vector<WinTex>    deleteQueue;
    struct RetiredTex { WinTex tex; uint64_t retireAfter; };
    std::vector<RetiredTex> retiring;                 // render thread / renderMutex holders only
    std::atomic<bool>      hasRetired{false};         // retiring is not empty (render loop polls)
    uint64_t               submitSerial = 0;          // frames submitted so far (render thread)
    uint64_t               slotSerial[MAX_FRAMES_IN_FLIGHT] = {};   // serial each slot's fence belongs to
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    // Applied state (what the cursor texture holds right now) ...
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    // ... and the pending image, written by updateCursorImage() under renderMutex. updateCursorImage()
    // makes no Vulkan call: the render thread applies it, so the texture is never replaced and the
    // staging never overwritten while a frame in flight still reads them.
    short  cursorPendW=0, cursorPendH=0, cursorPendHotX=0, cursorPendHotY=0;
    bool   cursorImgInit=false;   // cursorImg has been written at least once (leaves UNDEFINED)
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    // One staging buffer per frame slot: a slot's fence has been waited before it is written again.
    VkBuffer         cursorStg[MAX_FRAMES_IN_FLIGHT]  = {};
    VkDeviceMemory   cursorStgM[MAX_FRAMES_IN_FLIGHT] = {};
    void*            cursorStgP[MAX_FRAMES_IN_FLIGHT] = {};
    VkDeviceSize     cursorStgC[MAX_FRAMES_IN_FLIGHT] = {};

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;

    VkExtent2D       swapchainExt;

    VkSurfaceTransformFlagBitsKHR surfaceTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline        = VK_NULL_HANDLE;
    VkPipeline            sgsrPipeline    = VK_NULL_HANDLE;
    VkPipeline            fsr1Pipeline    = VK_NULL_HANDLE;
    VkPipeline            lanczosPipeline = VK_NULL_HANDLE;
    VkPipeline            sgsrHqPipeline  = VK_NULL_HANDLE;
    VkPipeline            nisPipeline     = VK_NULL_HANDLE;
    VkPipeline            stretchPipeline = VK_NULL_HANDLE;
    VkPipeline            postfxPipeline  = VK_NULL_HANDLE;
    // The window pipeline without blending: guest buffers are drawn opaque, only the cursor blends.
    VkPipeline            opaquePipeline  = VK_NULL_HANDLE;

    VkPipelineCache       pipelineCache   = VK_NULL_HANDLE;
    std::string           pipelineCachePath;
    size_t                pipelineCacheSavedSize = 0;
    // Serializes pipeline compilation and the cache's save (the cache itself is not touched by
    // two threads at once).
    std::mutex            pipelineBuildMutex;
    // The effect setters run one at a time, so they can compile before taking the frame lock.
    std::mutex            setterMutex;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    // The last swapchain rebuild in renderFrame failed: renderLoop retries after a pause
    // instead of spinning on a swapchain that is gone. Render thread only.
    bool              swapchainRebuildFailed = false;
    // Render thread only. A SUBOPTIMAL present rebuilds the swapchain only if the surface really
    // changed (transform or size); the check runs at most every 250 ms.
    bool              swapchainStillMatchesSurface();
    uint64_t          lastSuboptimalCheckNs = 0;
    uint64_t          suboptimalIgnored = 0;
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    // Wakes the render thread after a flag (needsRender, cursorMoved, fbResized, ...) was set.
    // The empty critical section orders the notify after the render thread's predicate check:
    // without it a notify landing between the check and the wait is lost, and the frame that
    // flag asked for is not drawn until something else happens. Callers must not hold dirtyMutex.
    void wakeRender(bool all = false) {
        { std::lock_guard<std::mutex> lk(dirtyMutex); }
        if (all) dirtyCV.notify_all(); else dirtyCV.notify_one();
    }
    std::shared_mutex frameMutex;
    // Exclusive frame lock for the effect setters: waits for the frame being recorded (the render
    // thread holds frameMutex shared for one frame) and keeps the next one from starting.
    std::unique_lock<std::shared_mutex> lockFrameExclusive() {
        return std::unique_lock<std::shared_mutex>(frameMutex);
    }

    VkRect2D          customScissor    = {{0,0},{0,0}};
    bool              hasCustomScissor = false;

    // WFG: the scene goes to a composite, WFG interpolates frames between the previous and this
    // composite straight into extra swapchain images, which are presented before the real one.
    struct WfgFrame {
        bool     active   = false;   // the scene goes to the composite this frame
        bool     newFrame = false;   // the composite is a new game frame for WFG
        uint32_t genCount = 0;
        uint32_t genIdx[VKR_DIS_MAX_GENERATIONS] = {};
    };
    std::atomic<bool>     wfgRequested{false};
    std::atomic<uint32_t> wfgFlowSide{180};
    std::atomic<uint32_t> wfgTargetFps{0};
    std::atomic<uint32_t> wfgMultiplier{0};
    std::atomic<uint32_t> wfgRefreshMhz{0};
    std::atomic<uint64_t> contentFrames{0};       // the game window's content updates
    std::atomic<uint64_t> wfgMadeFrames{0};
    std::atomic<uint64_t> presentedFrames{0};     // real and generated frames shown
    // The game window: the largest window whose content changed lately. Updates of other
    // windows (launchers, tooltips, the desktop) are not game frames for WFG's pacing.
    struct ContentRate { int64_t area = 0; uint64_t lastNs = 0; };
    std::unordered_map<int64_t, ContentRate> contentRates;   // under renderMutex
    void noteContentUpdate(int64_t id, int w, int h);
    std::atomic<uint64_t> lastGameFrameNs{0};
    // While the game drives the frames, a render without a new game frame is not presented:
    // its extra image would only grow the FIFO queue in front of the display (latency).
    std::atomic<bool>     wfgDeferredRender{false};
    uint64_t              wfgSkippedRenders = 0;
    // Model of the FIFO queue: when the last presented image reaches the display. Generated
    // frames are dropped while more than one image would wait ahead of this pair's own.
    uint64_t              wfgQueueEndNs = 0;
    uint64_t              wfgTrimmedFrames = 0;
    uint64_t              wfgAheadSum = 0;
    bool     wfgSkipRender();
    uint64_t wfgPeriodNs() const;
    void     wfgNotePresented(uint32_t count, uint64_t acquireWaitNs);
    VkrDis*          wfg              = nullptr;
    bool             wfgUnavailable   = false;
    bool             wfgSwapchain     = false;    // the swapchain was built for generation
    bool             swapchainTransferDst = false;
    uint32_t         swapchainMinImages = 0;
    uint64_t         wfgSeenFrames    = 0;
    uint64_t         wfgRealFrames    = 0;
    uint64_t         wfgLogReal       = 0;
    uint64_t         wfgLogMade       = 0;
    uint64_t         wfgAcquireMisses = 0;
    VkRenderPass     wfgPass          = VK_NULL_HANDLE;
    VkImage          wfgComposite     = VK_NULL_HANDLE;
    VkDeviceMemory   wfgCompositeMem  = VK_NULL_HANDLE;
    VkImageView      wfgCompositeView = VK_NULL_HANDLE;
    VkFramebuffer    wfgCompositeFB   = VK_NULL_HANDLE;
    VkExtent2D       wfgCompositeExt  = {0, 0};
    VkSemaphore      wfgGenSems[MAX_FRAMES_IN_FLIGHT][VKR_DIS_MAX_GENERATIONS] = {};

    // recordCmdBuf targets the swapchain image unless these name another pass/framebuffer;
    // recordLeaveOpen keeps the command buffer open for the WFG passes.
    VkRenderPass     recordPass       = VK_NULL_HANDLE;
    VkFramebuffer    recordFB         = VK_NULL_HANDLE;
    bool             recordLeaveOpen  = false;

    void getPreRotationCosSin(float& cosR, float& sinR) const {
        switch (surfaceTransform) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  cosR=0.f;  sinR=1.f;  break;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: cosR=-1.f; sinR=0.f;  break;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: cosR=0.f;  sinR=-1.f; break;
            default:                                       cosR=1.f;  sinR=0.f;  break;
        }
    }

    VkRect2D rotateRectLogicalToIdentity(VkRect2D r) const {
        const int Wi = (int)swapchainExt.width;
        const int Hi = (int)swapchainExt.height;
        int x=r.offset.x, y=r.offset.y, w=(int)r.extent.width, h=(int)r.extent.height;
        int ox,oy,ow,oh;
        switch (surfaceTransform) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
                ox = Wi - h - y; oy = x; ow = h; oh = w; break;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
                ox = Wi - w - x; oy = Hi - h - y; ow = w; oh = h; break;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
                ox = y; oy = Hi - w - x; ow = h; oh = w; break;
            default:
                ox = x; oy = y; ow = w; oh = h; break;
        }
        return VkRect2D{{ox,oy},{(uint32_t)std::max(0,ow),(uint32_t)std::max(0,oh)}};
    }

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipelineCache();
    void savePipelineCache();            // writes the cache to disk when it grew
    void createPipelineLayout();
    void createBasePipelines();          // the window pipeline (blend) and its opaque twin
    // Compiles a window pipeline with the shared vertex shader; throws on failure. Needs neither
    // the frame lock nor renderMutex: it only reads objects that outlive the renderer's frames.
    VkPipeline buildPipeline(const uint32_t* fragCode, size_t fragSize, bool blend, const char* name);
    VkPipeline& pipelineSlot(PipelineKind kind);
    // Builds the pipeline for `kind` unless it exists; null if it did, or if the build failed
    // (logged - the frame then falls back to the plain blit). No locks needed; callers hold
    // setterMutex.
    VkPipeline prebuildPipeline(PipelineKind kind);
    // Frame lock held exclusively: installs a prebuilt pipeline (or drops it if one got there first).
    void adoptPipeline(PipelineKind kind, VkPipeline built);
    bool upscalerActive() const;   // the selected upscaler is built and applies PostFX inline
    void syncPostFXPipeline();
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void recreateSyncObjects();   // semaphores sized against the current swapchain
    void cleanupSwapchain();

    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  cleanupAllAHBCache();
    void  freeWinTexVk(const WinTex& wt);   // renderMutex held or GPU idle
    void  retireDeleteQueueLocked();        // renderMutex held, render thread
    void  reapRetiredLocked();              // renderMutex held, render thread
    void  reapRetiredIdle();                // render thread, between frames
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(uint32_t slot, VkDeviceSize sz);
    void  reapDeadAhbsLocked();   // renderMutex held

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis,
        VkRect2D scissorRect);
    void renderLoop();
    void renderFrame();

    bool     wfgSync();
    void     wfgCreateSems();
    void     wfgDestroySems();
    bool     wfgEnsureComposite();
    void     wfgDestroyComposite();
    void     wfgDestroy();
    VkrDisContentRect wfgContentRect(const std::vector<DrawEntry>& draws,
                                     float ox, float oy, float sx, float sy, float cw, float ch) const;
    WfgFrame wfgBeginFrame(const std::vector<DrawEntry>& draws,
                           float ox, float oy, float sx, float sy, float cw, float ch);
    void     wfgRecord(VkCommandBuffer cb, uint32_t imgIdx, WfgFrame& wf);

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
