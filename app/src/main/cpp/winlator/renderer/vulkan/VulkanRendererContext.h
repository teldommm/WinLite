#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <memory>
#include <chrono>
#include "lsfg/lsfg_probe.h"
namespace lsfg { class Engine; }
#include <cstddef>
#include <vulkan/vulkan_android.h>

#include "view_transformation.hpp"
#include "xform.hpp"
#include "adpf/adpf_session.h"

struct VkTable {

    // --- Native LSFG frame generation: compute dispatch, timestamp queries,
    // capability probing and the composite->swapchain blit fallback.
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkCmdDispatch CmdDispatch;
    PFN_vkUnmapMemory UnmapMemory;
    PFN_vkCmdClearColorImage CmdClearColorImage;
    PFN_vkResetDescriptorPool ResetDescriptorPool;
    PFN_vkCreateQueryPool CreateQueryPool;
    PFN_vkDestroyQueryPool DestroyQueryPool;
    PFN_vkGetQueryPoolResults GetQueryPoolResults;
    PFN_vkCmdResetQueryPool CmdResetQueryPool;
    PFN_vkCmdWriteTimestamp CmdWriteTimestamp;

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
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
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
    PFN_vkCmdBlitImage CmdBlitImage;
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
    PFN_vkGetSemaphoreCounterValue GetSemaphoreCounterValue;
    PFN_vkWaitSemaphores WaitSemaphores;
    PFN_vkSignalSemaphore SignalSemaphore;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <deque>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) do { if (verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__); } while (0)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include <vector>
#include <unordered_map>
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
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight,
                          void* adrenotoolsHandle = nullptr,
                          bool validationEnabled = false);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    // contentId: the window content the texture belongs to. sourceId: the drawable the buffer
    // belongs to (the window content itself, or the DRI3 pixmap it presents). Both are
    // Drawable.uid values: unique for the process lifetime, never reused.
    // Content drawn by the X server itself (not a guest present): shown at once.
    void updateWindowContentAHB(int64_t contentId, int64_t sourceId, AHardwareBuffer* ahb,
                                short w, short h, int x, int y);
    // Panel refresh rate, for render pacing. 0/unknown disables pacing.
    void setDisplayRefreshRate(float hz);

    // --- Frame limiter / guest frame pacing ----------------------------------------------
    // A guest frame presented through X Present (PresentPixmap).
    // guestFrameNs: how long the guest took from getting this buffer back (IdleNotify) to
    // presenting it again, -1 if unknown. Fills the X server's buffer-release plan:
    //   out[0] releaseNs   - earliest time to give a buffer back to the guest (0 = now)
    //   out[1] onScreenNs  - the previously presented buffer stays on screen until then: it
    //                        must not be given back before (0 = no constraint)
    //   out[2] depth       - buffers the guest may hold at once (0 = no limit)
    //   out[3] completeNs  - when to send PresentCompleteNotify (0 = at once): the display slot
    //                        while the guest keeps up with its slots
    // With an FPS limit and a vsync clock this is where the cadence is made:
    //  * the frame gets a display slot on the vsync grid, refresh/limit vsyncs after the last
    //    one (a fractional ratio alternates, e.g. 45 on 120 Hz -> 3,3,2), and is held until
    //    just before it, so every frame is shown for the same number of refreshes;
    //  * the guest gets a buffer as late as still lets the frame drawn into it make its slot
    //    (measured guest frame time + margin): input is sampled as late as possible;
    //  * the guest may hold two buffers at once when it cycles through at least three (depth
    //    2, the released one is drawn for the slot after next), else one (depth 1, the next
    //    slot). Each release is timed for its own slot either way, so the depth costs no
    //    latency; it only keeps a late frame from delaying the release of the next buffer;
    //  * never sooner than one limit interval after the previous release (the rate cap).
    // Without a vsync clock the limit is a plain release cadence and frames are shown at once.
    void presentFrame(int64_t contentId, int64_t sourceId, AHardwareBuffer* ahb,
                      int64_t guestFrameNs, int64_t out[4]);
    void setFpsLimit(int fps);

    // --- ADPF (Android Performance Hint), opt-in ----------------------------------------------
    // One hint session over the render thread and the companion threads Java registers (the X
    // server's connector thread, the Present limiter threads), fed with how long each render
    // cycle really worked. Off until switched on; switching off closes the session. Safe from
    // any thread: they only set flags and wake the render loop, which owns the session.
    void setPerformanceHint(bool enabled);
    // The companion threads' ids (app threads only); the render thread is always in the session.
    void setPerformanceHintThreads(const int32_t* tids, size_t count);

    // Choreographer timing from Java, once per vsync. deadlineNs/presentNs are the preferred
    // frame timeline (API 33+); 0 when unavailable.
    void onVsync(int64_t frameTimeNs, int64_t deadlineNs, int64_t presentNs);
    // The Java drawable with this uid is gone: drop it as window content and as a buffer source.
    // Its import is destroyed once no window shows it and the GPU has finished with it.
    void releaseDrawable(int64_t id);
    // The cursor texture is R8G8B8A8; rgba=false means the source is B8G8R8A8 and gets swapped.
    void updateCursorImage(void* pixels, short w, short h, short stride, short hotX, short hotY, bool rgba);
    void setCursorVisible(bool visible);
    void removeWindow(int64_t id);
    void beginBatch() {}
    void endBatch() {}
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    // Off by default; the view turns it on together with the validation layer. Errors
    // (RLOG_E) are always logged. Read by the render thread, written from JNI.
    std::atomic<bool> verboseLog{false};
    void setVerboseLog(bool v) { verboseLog.store(v, std::memory_order_relaxed); }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setStretchMode(int mode);
    void setPostFXMode(int mode);
    void setSharpness(float s);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;
    VkExtent2D getSwapchainExtent() const { return swapchainExt; }

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
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        int                  w              = 0;
        int                  h              = 0;
        bool                 needsTransition = false;
        int64_t              srcId          = 0;   // window texture: the SourceImport it borrows from
        VkFormat             fmt            = VK_FORMAT_UNDEFINED;   // of img/view (view rebuilds)
    };
    // One Vulkan import per source drawable's AHardwareBuffer, shared by every window texture
    // that shows it (texMap entries borrow img/view/ds). Holds its own AHB reference.
    struct SourceImport {
        WinTex           wt;
        AHardwareBuffer* ahb      = nullptr;
        int              users    = 0;      // texMap entries currently showing it
        bool             released = false;  // the Java drawable is gone
        uint64_t         lastSerial = 0;    // last frame (submittedSerial) that sampled it
    };
    // A texture whose window is gone. It is destroyed once the frame that last drew it has
    // finished on the GPU (serial <= completedSerial), so nothing waits for the device to go idle.
    struct RetiredTex {
        WinTex wt;
        uint64_t serial = 0;
        AHardwareBuffer* ahb = nullptr;
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
    bool validationRequested = false;
    VkDebugUtilsMessengerEXT validationMessenger = VK_NULL_HANDLE;
    int filterMode  = 0;
    int stretchMode = 0;
    int postFXMode  = 0;
    float stretchStrength = 0.40f;
    float stretchProfile  = 0.60f;
    std::atomic<float> sharpness{0.5f};   // written by the UI, read while recording
    float maxAnisotropy           = 1.0f;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    // Keyed by source drawable uid. Keying by AHardwareBuffer* (as before) could not be cleaned
    // up from the pixmap side and is unsafe once buffers are actually freed (address reuse).
    std::unordered_map<int64_t, SourceImport> srcImports;

    std::vector<RetiredTex> deleteQueue;
    std::atomic<bool> retirePending{false};
    uint64_t submittedSerial = 0;
    uint64_t completedSerial = 0;
    uint64_t pendingFrameSerial = 0;
    uint64_t slotSerial[MAX_FRAMES_IN_FLIGHT] = {};
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    // VK_EXT_queue_family_foreign is enabled: guest buffers are acquired from and released to the
    // foreign queue family on every frame that samples them (recordCmdBuf).
    bool foreignQueueFamily_ = false;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    short  cursorPendingW=0, cursorPendingH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    // One staging buffer per frame slot: the GPU may still be reading the previous slot's copy of
    // the cursor while the CPU fills the next one.
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

    // === Native LSFG: composite target ring ===================================
    // Frame generation cannot composite straight into a swapchain image: the
    // finished frame has to be READABLE (it becomes the next frame's LSFG
    // input) and generated frames have to be STORAGE-WRITABLE by a compute
    // dispatch. Android swapchain images are COLOR_ATTACHMENT only, and
    // storage support on a swapchain format is not something a driver owes us.
    //
    // So when frame gen is armed the whole existing recording is redirected at
    // a composite image we own — format-identical to the swapchain, so every
    // existing pipeline stays render-pass compatible — and a copy moves it into
    // the acquired swapchain image at the end. With frame gen off, not one of
    // these objects is created and the direct-to-swapchain path is untouched.
    struct CompositeTarget {
        VkImage         img         = VK_NULL_HANDLE;
        VkDeviceMemory  mem         = VK_NULL_HANDLE;
        VkImageView     view        = VK_NULL_HANDLE;  // colour attachment + sampled
        VkImageView     storageView = VK_NULL_HANDLE;  // compute writes (generate)
        VkFramebuffer   fb          = VK_NULL_HANDLE;
    };
    // Hard ceiling: (max generations + 1) presentable frames per source frame,
    // times a queue depth of 2, capped so the footprint stays bounded.
    static constexpr uint32_t kMaxCompositeTargets = 7;

    std::vector<CompositeTarget> compositeTargets;
    VkRenderPass compositeRenderPass = VK_NULL_HANDLE;  // CLEAR -> GENERAL
    uint32_t     compositeW = 0, compositeH = 0;
    uint32_t     compositeIndex = 0;      // rotates per composite; gives history for free
    bool         compositeArmed = false;  // targets exist AND this frame uses them
    bool         swapchainTransferDst = false; // swapchain was created with TRANSFER_DST

    // Set from the app when native LSFG frame generation is selected for this
    // session. Read on the render thread; false keeps every path as it was.
    std::atomic<bool> fgArmed_{false};
    std::atomic<int>  fgMultiplier_{0};
    std::atomic<int>  fgTargetRate_{0};   // 0 = fixed multiplier, >0 = adaptive target Hz
    // Tuning is written by the UI thread and consumed by the render thread, so
    // it is published through atomics and applied to the engine at the top of
    // the frame-gen block. The UI thread never touches the engine itself.
    std::atomic<float> fgFlowScale_{1.0f};
    std::atomic<float> fgRefreshHz_{0.0f};
    std::atomic<bool>  fgConfigDirty_{true};

    void compositeExtentFor(uint32_t& w, uint32_t& h) const;
    VkExtent2D renderExtent() const {
        return compositeActive() ? VkExtent2D{compositeW, compositeH} : swapchainExt;
    }
    void recordCompositeToSwapchainTransfer(VkCommandBuffer cb, VkImage src, uint32_t imgIdx);

    bool  createCompositeRenderPass();
    bool  ensureCompositeTargets(uint32_t w, uint32_t h, uint32_t count);
    void  destroyCompositeTargets();
    // True when this frame should composite off-swapchain. Call on the render
    // thread; every gate must hold or we fall back to the untouched path.
    bool  compositeActive() const;
    // Render pass / framebuffer this frame draws its FINAL pass into.
    VkRenderPass  targetRenderPass() const;
    VkFramebuffer targetFramebuffer(uint32_t imgIdx) const;
    // Copy/blit the finished composite into the acquired swapchain image and leave
    // it in PRESENT_SRC. No-op when the composite path is not active.
    void  copyCompositeToSwapchain(VkCommandBuffer cb, uint32_t imgIdx);

    // === Native LSFG: the software cursor ====================================
    // LSFG interpolates whatever it is given, so a cursor composited into the
    // frame gets warped along the flow field and smears. It is therefore
    // excluded from the composite while frame gen is armed and drawn once into
    // EVERY presented image instead - real and generated alike - through a
    // load-op render pass that leaves the image ready to present.
    VkRenderPass cursorOverlayRenderPass = VK_NULL_HANDLE;
    struct CursorOverlay {
        bool  draw = false;
        float ox = 0, oy = 0, sx = 0, sy = 0, cw = 0, ch = 0;
        short ptrX = 0, ptrY = 0, hotX = 0, hotY = 0, w = 0, h = 0;
    };
    CursorOverlay cursorOverlay_{};

    bool createCursorOverlayRenderPass();
    // Draw the cursor into an already-copied swapchain image and leave it in
    // PRESENT_SRC. Takes over the final transition from the copy helpers.
    void recordCursorOverlay(VkCommandBuffer cb, uint32_t imgIdx);
    // True when the cursor is being drawn per present rather than composited.
    bool cursorDrawnPerPresent() const;

    // === Native LSFG: the per-source-frame present plan =======================
    // Interpolation produces frames that belong BETWEEN N-1 and N, so the
    // generated frames are presented FIRST and real frame N is held back one
    // slot. All presents for one source frame are queued together: with FIFO
    // the driver then shows them on consecutive vblanks, so the pacing falls
    // out of the present mode for free - no sleeps, no render-mode change.
    static constexpr uint32_t kMaxPresentsPerFrame = 4;   // 1 real + up to 3 generated
    struct FrameGenPlan {
        uint32_t generations = 0;
        uint32_t presents    = 1;
        uint32_t imgIdx[kMaxPresentsPerFrame] = {};
    };
    FrameGenPlan fgPlan_{};
    std::unique_ptr<lsfg::Engine> lsfgEngine_;
    uint64_t    fgSourceFrames_ = 0;
    // Guest content updates (window content uploads / AHB swaps). A frame-gen
    // SOURCE frame is a wake that carries new guest content; cursor motion,
    // cursor image or transform changes alone are not, and must neither feed
    // the pacer nor be interpolated (that would queue duplicate presents).
    std::atomic<uint64_t> guestFrameSerial_{0};
    // Guest presents (X Present PresentPixmap). When the guest presents this
    // way it is the exact frame clock: one present = one source frame, and a
    // content upload without a present (another window, a cursor-sized damage)
    // is not one. Games that never use Present fall back to content uploads.
    std::atomic<uint64_t> guestPresentSerial_{0};
    std::atomic<int64_t>  guestPresentNs_{0};
    static constexpr int64_t kPresentActiveNs = 500000000LL;
    uint64_t    fgSeenContentSerial_ = 0;
    uint64_t    fgSeenPresentSerial_ = 0;
    std::chrono::steady_clock::time_point fgLastGuestFrame_{};
    // This frame is drawn without frame generation (no new guest content and
    // the guest has gone idle): composite + one present, no plan/process.
    bool        fgPassthrough_ = false;
    // A non-content wake was deferred while the guest was producing frames;
    // the render loop re-checks after kFgIdleMs so it is never lost.
    std::atomic<bool> fgDeferred_{false};
    static constexpr int kFgIdleMs = 100;
    std::string lsfgCachePath_;
    bool        lsfgEngineTried_ = false;

    // Sync objects are indexed per PRESENT, not per composite: each pending
    // present needs its own image-available and render-finished semaphore.
    uint32_t syncSlot(uint32_t k) const { return currentFrame * kMaxPresentsPerFrame + k; }
    // Destroy and rebuild every acquire/present semaphore. Called with the
    // swapchain, so no stale pending signal can survive into the new one.
    void recreateSyncObjects();
    uint32_t fgAcquireFailLog_ = 0;
    uint64_t frameGenAcquireTimeoutNs() const;
    void     guestExtentForFrameGen(uint32_t& w, uint32_t& h) const;

    // Measured PRESENTS per second - the rate that actually reaches the panel,
    // counting generated frames. The pacer's own "loop rate" cannot serve this
    // purpose: it is sampled once per SOURCE frame, so it always equals the
    // guest rate and contains no evidence that generation happened at all.
    // Written by the render thread; read by the HUD on the X server's thread (on every guest
    // present), lock-free.
    std::atomic<float> fgPresentedRate_{0.0f};
    uint32_t fgPresentAccum_    = 0;
    std::chrono::steady_clock::time_point fgRateWindowStart_{};
    bool     fgRateWindowOpen_  = false;
    void     trackPresentedRate(uint32_t presents);

    bool ensureLsfgEngine();
    bool fgCapsOk() const;
    // One command buffer per pending present: slot 0 carries the composite,
    // the chain's shared passes and generated frame 0; each later generated
    // frame and the real frame are recorded and submitted on their own, so a
    // finished frame reaches the presentation engine without waiting for the
    // rest of the chain.
    uint32_t cmdSlot(uint32_t k) const { return currentFrame * kMaxPresentsPerFrame + k; }
    // Shared chain passes (the 24 shaders every generated frame depends on).
    void recordFrameGenProcess(VkCommandBuffer cb);
    // Generated frame g: synthesise into its own spare composite target, copy it into the
    // swapchain image reserved for it and draw the cursor where cursorOverlay_ says.
    void recordFrameGenGeneration(VkCommandBuffer cb, uint32_t g);

    // Chain cost: three timestamps per frame slot - before the shared passes, after them, and
    // after generation 0's compute (before its copy, so a vblank wait on a swapchain image is not
    // counted). All three are in the first present's buffer. Read back after the slot's fence
    // wait, one frame later.
    static constexpr uint32_t kFgQueriesPerSlot = 3;
    VkQueryPool fgQueryPool_        = VK_NULL_HANDLE;
    bool        fgTimestampsOk_     = false;
    float       fgTimestampPeriodNs_ = 0.0f;
    bool        fgQueryPending_[MAX_FRAMES_IN_FLIGHT] = {};
    uint32_t    fgQueryGens_[MAX_FRAMES_IN_FLIGHT]    = {};
    float       fgChainMsPerGen_    = -1.0f;
    uint32_t    fgChainLogCount_    = 0;
    void ensureFgQueryPool();
    void destroyFgQueryPool();
    void readFgQueryResult();

    lsfg::Caps lsfgCaps_{};
    // Variant of the cache currently selected (read from its header in
    // setLsfgCachePath); decides which gate set fgCapsOk() applies.
    lsfg::Variant lsfgCacheVariant_ = lsfg::Variant::None;
    uint32_t fgSwapchainCapacity_ = 0;
    void setLsfgCachePath(const char* path);
    void setFrameGenArmed(bool armed, int multiplier, int targetRate = 0);
    void setFrameGenTuning(float flowScale, float refreshHz);

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

    // --- Render pacing (frame gen off). The render thread is woken by content updates and by
    // cursor motion alone. Every wake used to composite and present at once: with FIFO, cursor
    // motion plus guest frames produce more than one frame per refresh, the swapchain queue fills
    // to its depth, and every later frame waits behind it. A simple model of the display queue
    // (each present occupies one refresh after the later of "now" and the previous frame's slot)
    // gates the wakes: cursor-only frames are drawn only once the queue is drained (at most one
    // per refresh), content frames only while at most one frame is waiting - a deferred frame
    // composites the newest guest content when it does run.
    std::atomic<int64_t> refreshPeriodNs_{0};
    // The compositor's own swapchain mode: the pacing model is a FIFO queue; with MAILBOX or
    // IMMEDIATE there is no queue to drain and holding a frame only delays it.
    std::atomic<bool> swapchainFifo_{true};
    int64_t presentSlotEndNs_ = 0;   // render thread only
    // When the last frame was presented (render thread only). Outside FIFO there is no display
    // queue to model, but a frame that only moves the cursor is still not worth more than one
    // per refresh: with MAILBOX every pointer event (unbuffered, up to the mouse's polling rate)
    // used to be a full composite, hundreds per second, taking GPU time from the game.
    int64_t lastPresentNs_ = 0;
    int64_t pacingWaitNs(bool content) const;
    // lastDesiredNs: desired presentation time of the last present (display timing), 0 = none.
    // startNs: when this group's first present was recorded (0 = now). The group's refreshes
    // are counted from there, not from when its last present was queued: acquiring the later
    // images can take most of a source interval, and that wait is not display time.
    void    notePresentedForPacing(uint32_t presents, int64_t lastDesiredNs = 0,
                                   int64_t startNs = 0);
    // How long before a refresh's queue deadline a composite is started (GPU work + submit).
    static int64_t queueLeadNs(int64_t periodNs) {
        return std::clamp<int64_t>(periodNs * 2 / 5, 3000000LL, 8000000LL);
    }
    // The Choreographer clock has ticked recently (a stopped clock is not a grid to pace on).
    bool vsyncClockFresh(int64_t nowNs) const {
        return vsyncDeadlineNs_.load(std::memory_order_relaxed) != 0 &&
               nowNs - vsyncSeenNs_.load(std::memory_order_relaxed) < 250000000LL;
    }

    // --- ADPF state ---------------------------------------------------------------------------
    // A hint session is not thread safe, so every call on adpfSession_ is made either by the
    // render thread (renderLoop / renderFrame, which holds frameMutex shared) or by a setter
    // that holds frameMutex exclusive - never two at once. Java threads only touch the flags.
    enum : uint32_t {
        kAdpfResetAll     = 1u << 0,   // resolution / pipeline changed: forget what was learned
        kAdpfResetGpu     = 1u << 1,   // a GPU effect (upscaler, post-FX, ...) was switched
        kAdpfIncreaseGpu  = 1u << 2,   // heavier GPU work is coming (frame generation armed)
        kAdpfSpikeAll     = 1u << 3,   // one-off expensive frame
    };
    std::atomic<bool>     adpfWanted_{false};    // the option
    std::atomic<bool>     adpfDirty_{false};     // something for adpfService()
    std::atomic<uint32_t> adpfNotifyBits_{0};
    std::atomic<int>      renderTid_{0};
    std::mutex            adpfMutex_;            // companions
    std::vector<int32_t>  adpfCompanions_;
    bool                  adpfCompanionsDirty_ = false;
    bool                  adpfOpenTried_ = false;   // render thread: gave it a go since the last toggle
    int64_t               adpfTargetNs_ = 0;        // render thread: the target the session has
    adpf::Session         adpfSession_;
    // Asks for adpfService() and wakes the render loop; no-op while the option is off.
    void adpfNote(uint32_t bits);
    void adpfService();                              // render thread, between frames
    void adpfShutdown();                             // render thread, on its way out
    int64_t adpfCycleNs() const;                     // what one cycle of work should fit in
    void adpfFrameEnd(int64_t startNs, int64_t blockedNs);   // renderFrame, success path

    // Limiter state. Atomics are written from Java threads; the rest is renderMutex.
    std::atomic<int>     fpsLimit_{0};
    std::atomic<int64_t> vsyncDeadlineNs_{0};   // a recent point of the queue-deadline grid
    std::atomic<int64_t> vsyncSeenNs_{0};       // when the clock last ticked
    // expected presentation - deadline of the preferred frame timeline (API 33+), 0 unknown
    std::atomic<int64_t> vsyncPresentOffsetNs_{0};

    // --- Frame generation present timing (VK_GOOGLE_display_timing) ----------------------
    // The presents of one source frame used to be queued back to back in FIFO, so they went
    // out on consecutive refreshes: even only when refresh == multiplier x source rate (2x of
    // 30 FPS on 120 Hz showed 1 refresh, then 3). With display timing each present carries a
    // desired presentation time spread evenly over the source frame interval (2, 2), which is
    // also when an interpolated frame belongs on screen. Without the extension: as before.
    bool displayTimingOk_ = false;
    int64_t fgSourceIntervalNs() const;
    // Fills desired[k] (CLOCK_MONOTONIC ns, 0 = as soon as possible) for this frame's presents.
    // anchorSlotNs: the queue deadline of the display slot this source frame was held for
    // (limiter grid), 0 = none - then the first present goes out as soon as it is ready.
    void fgPresentTimes(uint32_t presents, int64_t desired[], int64_t anchorSlotNs) const;

    // --- Frame generation on the limiter grid ------------------------------------------------
    // With an FPS limit, frame generation's source frames are held for their display slot like
    // any other limited frame; the composite is started early enough for the chain up to the
    // first generated frame (fgChainCostNs_) and the group's presents carry desired times from
    // that slot on.
    // Smoothed GPU cost of the shared passes + generation 0 - all the first present waits for.
    std::atomic<int64_t> fgChainCostNs_{0};   // 0 = unknown
    int64_t fgLeadNs(int64_t baseLead, int64_t spacing) const;
    void    noteGuestFrameLocked(int64_t nowNs);   // a guest frame went on screen
    int64_t fgAnchorSlotNs_ = 0;              // render thread: slot of the frame just applied
    // Refreshes still taken by earlier presents when this group starts (FIFO tail, from the
    // display queue model): the pacer leaves them out of this source frame's panel budget.
    // groupStartNs: the group's slot (limiter grid), 0 = the next refresh it can make.
    uint32_t fgQueuedRefreshes(int64_t groupStartNs) const;
    struct PendingPresent { int64_t srcId; int64_t dueNs; int64_t slotNs; };
    std::unordered_map<int64_t, std::deque<PendingPresent>> pendingPresents_;  // by content
    std::atomic<int64_t> nextPendingDueNs_{0};  // earliest dueNs, 0 = none
    // Limiter/pacing state of one presenting window (content). Two windows presenting at the
    // same time (a launcher behind the game, a second swapchain) used to share one cadence and
    // frame-time estimate and disturbed each other.
    struct Pacer {
        int64_t idealSlotNs   = 0;    // unsnapped slot accumulator (keeps fractional ratios exact)
        int64_t lastReleaseNs = 0;
        double  guestFrameMeanNs = 0.0, guestFrameDevNs = 0.0;
        bool    guestFrameValid  = false;
        int64_t lastPresentNs    = 0;
        // Buffers of the two presents before the current one: the guest cycles through three or
        // more when all three differ (two may then be out at once), else through two.
        int64_t recentSrc[2]     = {0, 0};
        void resetCadence() { idealSlotNs = 0; lastReleaseNs = 0; }
    };
    std::unordered_map<int64_t, Pacer> pacers_;   // by content, under renderMutex
    bool    acquireSourceLocked(int64_t srcId, AHardwareBuffer* ahb);
    void    showSourceLocked(int64_t contentId, int64_t srcId);
    void    dropPendingLocked(int64_t contentId);
    void    applyDuePresentsLocked(int64_t nowNs);
    void    updateNextPendingDueLocked();
    std::atomic<bool> globalPriorityHigh_{false};
    // Timeline semaphore the first submit of every frame signals with that frame's serial - the
    // only submit that samples guest buffers (renderFrame) - so any thread can ask whether the
    // GPU is done with a frame's guest buffers (vkGetSemaphoreCounterValue) without touching the
    // render thread's fences. VK_NULL_HANDLE when the device has no timelines.
    VkSemaphore gpuTimeline_ = VK_NULL_HANDLE;
    // Whether the GPU may still be reading the guest buffer `srcId` (a frame that sampled it
    // has not finished). False when unknown (no timeline semaphore): callers then fall back
    // to their own timing.
    bool sourceBusy(int64_t srcId);
    // Blocking form, for the X server's hand-back of a busy buffer. begin is called like every
    // native call (under the Java view lock, which nativeDestroy also holds) and returns the
    // timeline value the buffer's last frame signals, or 0 if nothing is pending; a non-zero
    // return counts a waiter, and the same thread must then call endSourceIdleWait - outside
    // the Java lock, as it blocks (up to timeoutNs). The destructor wakes the waiters and waits
    // for them to leave before the semaphore and the device go away.
    uint64_t beginSourceIdleWait(int64_t srcId);
    void     endSourceIdleWait(uint64_t serial, uint64_t timeoutNs);
    std::atomic<int> idleWaiters_{0};
    void releaseIdleWaiters();

    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::atomic<bool> swapchainRetryPending{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    // Wake the render thread. The waiter checks its flags and goes to sleep under dirtyMutex;
    // notifying without passing through that mutex can land between the two, and the wake (a
    // guest frame, say) is lost until something else happens. Callers must not hold dirtyMutex.
    void wakeRender(bool all = false) {
        { std::lock_guard<std::mutex> lk(dirtyMutex); }
        if (all) dirtyCV.notify_all(); else dirtyCV.notify_one();
    }
    std::shared_mutex frameMutex;
    // Exclusive frame lock for configuration changes: waits for the frame being recorded (the
    // render thread holds frameMutex shared for one frame) and keeps the next one from starting.
    // Every setter that replaces something a frame records with takes it.
    std::unique_lock<std::shared_mutex> lockFrameExclusive() {
        return std::unique_lock<std::shared_mutex>(frameMutex);
    }

    VkRect2D          customScissor    = {{0,0},{0,0}};
    bool              hasCustomScissor = false;

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
    VkRenderPass createCompatibleRenderPass(VkAttachmentLoadOp loadOp, VkImageLayout initialLayout, VkImageLayout finalLayout);
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createSgsrPipeline();
    void createFsr1Pipeline();
    void createLanczosPipeline();
    void createSgsrHqPipeline();
    void createNisPipeline();
    void createStretchPipeline();
    void createPostFXPipeline();
    void tryCreatePipeline(void (VulkanRendererContext::*create)(), const char* name);
    bool upscalerActive() const;   // the selected upscaler is built and applies PostFX inline
    void syncPostFXPipeline();
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  destroyAllSourceImports();
    // renderMutex held for all of these.
    void  unrefSourceLocked(int64_t srcId);
    void  retireSourceLocked(std::unordered_map<int64_t, SourceImport>::iterator it);
    void  eraseContentLocked(int64_t contentId);
    void  destroyTexNow(RetiredTex& retired);
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz, uint32_t slot);

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

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
