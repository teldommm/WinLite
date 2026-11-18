#pragma once
#include "VulkanRendererVk.h"
#include "VulkanRendererFx.h"
#include "VulkanRendererClock.h"
#include "DirectServer.h"

#include <memory>

#include <list>
#include <cstddef>

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

// xcore's entry points for the renderer: rust/xcore/src/android/output.rs `HostApi` (layout
// checked in vulkan_jni.cpp). Handed over with RendererApi::setHost; static on the Rust side.
struct XcoreHostApi {
    uint32_t version;
    void*    ctx;
    // The renderer is done with the buffer: no window shows it and no submitted frame reads it.
    // Only queues on the Rust side (a leaf lock), so it may be called with renderMutex held.
    void   (*bufferReleased)(void* ctx, AHardwareBuffer* ahb);
};
static constexpr uint32_t XCORE_HOST_API_VERSION = 1;


class VulkanRendererContext {
public:
    // cacheDir: where the VkPipelineCache is kept between runs (null/empty: not persisted).
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight,
                          void* adrenotoolsHandle = nullptr,
                          bool validationEnabled = false,
                          const char* cacheDir = nullptr);
    ~VulkanRendererContext();

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
    // xcore's table (null: none). Buffer releases are reported through it from then on.
    void setHost(const XcoreHostApi* host);

    // ---- Direct output (DirectServer; the renderer's side in VulkanRendererDirect.cpp)
    // mode: WL_OUTPUT_*; socketPath: where WL Direct clients connect (null/empty: none).
    void setDirectOutput(int mode, const char* socketPath);
    DirectServer::Status directStatus() const;
    void requestRender() { needsRender.store(true); wakeRender(); }
    // DirectServer gives back what it showed (any thread, renderMutex not held).
    void directBufferReleased(AHardwareBuffer* ahb);
    void directRingReleased(uint32_t gen, uint32_t slot, int fenceFd);
    void directClientGone(const std::vector<AHardwareBuffer*>& images);
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; fx.verboseLog = v; }
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

    // The panel's refresh rate as Java knows it (Display.getRefreshRate, follows mode changes):
    // the check on the swapchain's own refresh cycle, which some drivers keep stale after a
    // mode switch. 0: unknown.
    void setDisplayRefreshHz(float hz);
    uint64_t getPresentedFrames() const { return presentedFrames.load(std::memory_order_relaxed); }
    // presentedFrames as of the latest present and when it went out (steady_clock =
    // CLOCK_MONOTONIC = Java's System.nanoTime()), read together, for the HUD's output fps.
    struct PresentStamp { uint64_t frames = 0; uint64_t ns = 0; };
    PresentStamp getPresentStamp() const {
        std::lock_guard<std::mutex> g(presentStampMutex);
        return presentStamp;
    }

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
        // Import cache entries only: the serial of the last frame that samples the buffer
        // (submitSerial numbering; a frame recorded but not submitted keeps the next one).
        uint64_t             lastUse        = 0;
        uint64_t             usage          = 0;   // AHardwareBuffer_Desc::usage
        VkFramebuffer        fb             = VK_NULL_HANDLE;   // render targets (Direct ring)
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        AHardwareBuffer* ahb           = nullptr;
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
    // submitSerial and slotSerial are written under renderMutex (read by other threads through
    // completedSerialLocked).

    // ---- Buffer releases (xcore's Present gives a pixmap back once the renderer is done with it)
    std::atomic<const XcoreHostApi*> host_{nullptr};
    // Buffers no window shows any more that a submitted frame may still sample: reported once
    // the frame with serial `after` has completed. Under renderMutex.
    struct PendingRelease { AHardwareBuffer* ahb; uint64_t after; };
    std::vector<PendingRelease> pendingReleases;
    // The newest serial every frame up to which has completed (polls the slot fences).
    uint64_t completedSerialLocked() const;
    bool     shownLocked(AHardwareBuffer* ahb) const;
    // A window stopped showing `ahb`: report it now, or once the frames that sample it are done.
    void     noteDisplacedLocked(AHardwareBuffer* ahb);
    void     reapReleasesLocked(uint64_t done);
    void     reportReleased(AHardwareBuffer* ahb);
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
    // The effects and the window pipelines (drawn into renderPass).
    VulkanRendererFx      fx;
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

    // ---- Display clock (X composite source)
    // VK_GOOGLE_display_timing on the swapchain: every present carries a presentID, and the
    // feedback says when each image reached the display. The latest such time anchors the
    // refresh grid (clock_); the exact refresh cycle comes from vkGetRefreshCycleDurationGOOGLE.
    bool                  displayTiming_ = false;
    PFN_vkGetRefreshCycleDurationGOOGLE   getRefreshCycleGOOGLE_  = nullptr;
    PFN_vkGetPastPresentationTimingGOOGLE getPastPresentTimingGOOGLE_ = nullptr;
    DisplayClock          clock_;
    // Presents issued so far; a presentID is the low 30 bits of the sequence number.
    std::atomic<uint64_t> presentSeq{0};
    static constexpr uint32_t kPresentSeqMask  = 0x3fffffffu;
    // Every present carries a desiredPresentTime: the Android swapchain sets the window's
    // timestamp only when it is non-zero, and the window keeps the last one. kAutoPresentTime
    // goes over as NATIVE_WINDOW_TIMESTAMP_AUTO (INT64_MIN): SurfaceFlinger's backpressure shows
    // the images one per refresh (FIFO). An explicit time ("now") would let it drop an image
    // whenever two are ready in one frame.
    static constexpr uint64_t kAutoPresentTime = 0x8000000000000000ull;
    uint64_t seqOfPresentId(uint32_t id) const;
    // Feedback for presents up to this sequence number belongs to the previous display mode.
    std::atomic<uint64_t> modeSeq{0};
    std::atomic<uint64_t> swapchainRefreshNs{0};   // vkGetRefreshCycleDurationGOOGLE, 0 = unknown
    uint64_t              lastShownSeq     = 0;    // newest present the display reported shown
    std::atomic<uint32_t> refreshHintMhz{0};       // setDisplayRefreshHz
    // vkGetRefreshCycleDurationGOOGLE is a synchronous call into SurfaceFlinger on Android:
    // polled twice a second and whenever Java reports another mode, not every frame.
    uint64_t              refreshPollNs    = 0;
    uint32_t              refreshPollMhz   = 0;
    uint64_t              refreshPeriodNs() const;   // the period the grid is built on
    void                  pollPresentTiming();       // render thread, every frame
public:
    // {period, phase error, sequence} for xcore and Java; false while the grid is not fresh.
    bool getDisplayClock(int64_t out[3]) const;
    DisplayClock& displayClock() { return clock_; }

    // ---- Sync files (VK_KHR_external_semaphore_fd / VK_KHR_external_fence_fd, SYNC_FD)
    bool externalSemaphoreFd_ = false;
    bool externalFenceFd_     = false;

    std::atomic<uint64_t> presentedFrames{0};     // frames shown (presents that went through)

    // ---- Direct output
    std::unique_ptr<DirectServer> direct_;
    // The render loop runs a frame at this time even if nothing asked for one (Direct's
    // hysteresis and its hand-over to the composite); 0 = none.
    std::atomic<uint64_t> directWakeAtNs{0};
    // Per window content (under renderMutex): its updates, and whether its client presents
    // alternating buffers (may go to SurfaceFlinger as they are) or draws one in place.
    struct ContentInfo { AHardwareBuffer* last = nullptr; uint64_t gen = 0; uint32_t swaps = 0; uint32_t repeats = 0; };
    std::unordered_map<int64_t, ContentInfo> contentInfo;
    uint64_t              cursorImageSerial = 0;   // under renderMutex: a new cursor image
    uint64_t              directCursorSent = ~0ull; // render thread: the image Direct has
    std::atomic<uint64_t> fxSerial{0};             // the effect settings changed
    // Direct + FX: the ring of buffers Fx draws into (under renderMutex), the render pass for
    // them, and the sync files around each pass.
    struct RingSlot { AHardwareBuffer* ahb = nullptr; WinTex tex; bool inSf = false; int releaseFd = -1; };
    std::vector<RingSlot> ring;
    uint32_t              ringGen = 0;
    int32_t               ringW = 0, ringH = 0;
    bool                  ringUbwc = false;
    VkRenderPass          directPass = VK_NULL_HANDLE;
    VkSemaphore           directExportSems[MAX_FRAMES_IN_FLIGHT] = {};
    VkSemaphore           directWaitSems[MAX_FRAMES_IN_FLIGHT][2] = {};
    // WL Direct clients' images, imported for the Fx pass (under renderMutex).
    std::unordered_map<AHardwareBuffer*, WinTex> directImports;
    // What the last X-source Fx pass drew (it runs again only when one of these changed).
    AHardwareBuffer*      fxLastInput = nullptr;
    uint64_t              fxLastGen = 0, fxLastSerial = ~0ull;
    DirectServer::Rect    fxLastDst;
    DirectServer::Candidate directCandidateLocked();   // renderMutex held, tree rebuilt
    void renderDirect(const DirectServer::Plan& plan, const DirectServer::Candidate& cand);
    void runDirectFx(const DirectServer::Plan& plan, const DirectServer::Candidate& cand);
    bool ensureRingLocked(int32_t w, int32_t h, bool ubwc);
    void retireRingLocked();
    bool ensureDirectSync();
    bool importAHBAsTarget(WinTex& wt, AHardwareBuffer* ahb);
    void destroyDirect();   // device idle
    mutable std::mutex    presentStampMutex;
    PresentStamp          presentStamp;           // under presentStampMutex

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

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
};
