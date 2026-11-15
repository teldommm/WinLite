#pragma once
// Fx: the one place the renderer's effects live. Upscalers (SGSR, SGSR HQ, FSR 1, NIS, Lanczos),
// nearest, stretch, sharpness, ColorBoost and the PostFX effects; their pipelines, push
// constants and the pipeline cache.
//
// Fx draws a guest buffer into a rectangle of a render pass that is compatible with the one it
// was created for. The composite (VulkanRendererContext::recordCmdBuf) binds once per frame and
// draws every window with the effect in force; the cursor is drawn by Fx too, never with an
// effect.
//
// Threads: the setters' protocol is VulkanRendererContext's. A pipeline is compiled with
// prebuild() outside any lock, and installed with adopt() under the exclusive frame lock; the
// settings change under the same lock. Recording reads them under the shared frame lock.
#include "VulkanRendererVk.h"

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

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

class VulkanRendererFx {
public:
    // The pipelines built on demand for the effect setters (the base window pipelines are not here).
    enum class Kind { Sgsr, Fsr1, Lanczos, SgsrHq, Nis, Stretch, PostFx };

    // What is drawn: a guest buffer's descriptor set (combined image sampler) and its size.
    struct Input {
        VkDescriptorSet ds;
        int             w, h;
    };
    // Where: the rectangle in normalized device coordinates of the render area, the render area
    // (the upscalers' output size comes from it), and the surface pre-rotation.
    struct Target {
        float      x0, y0, x1, y1;
        VkExtent2D extent;
        float      cosR, sinR;
    };

    bool verboseLog = true;

    // ---- settings (see the class comment for when they change)
    int   filterMode      = 0;   // 0 linear, 1 nearest, 2 SGSR, 3 FSR, 4 Lanczos, 5 ColorBoost, 6 SGSR HQ, 7 NIS
    int   stretchMode     = 0;
    int   postFXMode      = 0;
    float stretchStrength = 0.40f;
    float stretchProfile  = 0.60f;
    float sharpness       = 0.5f;

    // Pipeline cache (persisted in cacheDir when given), layout and the base window pipelines;
    // throws when the base pipeline cannot be built. `renderPass` sets the pass compatibility.
    void create(VkTable* vk, VkDevice device, VkPhysicalDevice physicalDevice,
                VkRenderPass renderPass, VkDescriptorSetLayout dsLayout, const std::string& cacheDir);
    // Device idle. Saves the cache.
    void destroy();
    void saveCache();   // writes the cache to disk when it grew

    VkPipelineLayout layout() const { return pipeLayout_; }

    // ---- setters' helpers
    static bool upscalerKindFor(int mode, Kind& kind);   // false for the modes without one
    // Builds the pipeline for `kind` unless it exists; null if it did, or if the build failed
    // (logged - the frame then falls back to the plain blit). No locks needed.
    VkPipeline prebuild(Kind kind);
    // Frame lock held exclusively: installs a prebuilt pipeline (or drops it if one got there first).
    void adopt(Kind kind, VkPipeline built);
    bool has(Kind kind) const;
    bool upscalerActive() const;   // the selected upscaler is built and applies PostFX inline
    // Frame lock held exclusively: drops the postfx pipeline when nothing uses it any more
    // (waits for the device then).
    void syncPostFX();
    bool samplesNearest() const { return filterMode == 1; }
    // Anything beyond a plain bilinear blit: an upscaler, nearest, ColorBoost, stretch, PostFX.
    bool needed() const { return filterMode != 0 || stretchMode != 0 || postFXMode != 0; }
    static const char* filterName(int mode);

    // ---- recording, inside a compatible render pass with viewport and scissor set
    // Binds the pipeline of the effect in force; draw() then draws windows with it.
    void bind(VkCommandBuffer cb);
    void draw(VkCommandBuffer cb, const Input& in, const Target& dst);
    // The cursor: the blending base pipeline, the texture's own alpha, no effect.
    void drawCursor(VkCommandBuffer cb, VkDescriptorSet ds, const Target& dst);
    // Direct + FX: the effect in force draws `in` over the whole of `framebuffer` (`extent`), in a
    // render pass of its own that is compatible with the one Fx was created for (same format).
    // No pre-rotation: SurfaceFlinger turns the buffer with the display.
    void recordStandalone(VkCommandBuffer cb, const Input& in, VkRenderPass renderPass,
                          VkFramebuffer framebuffer, VkExtent2D extent);

private:
    VkPipeline& slot(Kind kind);
    VkPipeline  buildPipeline(const uint32_t* fragCode, size_t fragSize, bool blend, const char* name);
    VkShaderModule makeShader(const uint32_t* code, size_t size);
    void createCache(const std::string& cacheDir);
    void createLayout();
    void createBasePipelines();

    VkTable*              vk_          = nullptr;
    VkDevice              device_      = VK_NULL_HANDLE;
    VkPhysicalDevice      physical_    = VK_NULL_HANDLE;
    VkRenderPass          renderPass_  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout_    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout_  = VK_NULL_HANDLE;

    VkPipeline pipeline_        = VK_NULL_HANDLE;   // the window pipeline with blending (cursor)
    // The window pipeline without blending: guest buffers are drawn opaque, only the cursor blends.
    VkPipeline opaquePipeline_  = VK_NULL_HANDLE;
    VkPipeline sgsrPipeline_    = VK_NULL_HANDLE;
    VkPipeline fsr1Pipeline_    = VK_NULL_HANDLE;
    VkPipeline lanczosPipeline_ = VK_NULL_HANDLE;
    VkPipeline sgsrHqPipeline_  = VK_NULL_HANDLE;
    VkPipeline nisPipeline_     = VK_NULL_HANDLE;
    VkPipeline stretchPipeline_ = VK_NULL_HANDLE;
    VkPipeline postfxPipeline_  = VK_NULL_HANDLE;

    VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
    std::string     pipelineCachePath_;
    size_t          pipelineCacheSavedSize_ = 0;
    // Serializes pipeline compilation and the cache's save (the cache itself is not touched by
    // two threads at once).
    std::mutex      pipelineBuildMutex_;

    // What bind() chose, for draw().
    bool useSgsr_ = false, useFsr_ = false, useLanczos_ = false, useSgsrHq_ = false, useNis_ = false;
    bool useColorBoost_ = false, usePostFX_ = false, useStretch_ = false;
};
