#pragma once
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <cstdint>
#include <memory>
#include <string>

#include <vulkan/vulkan.h>

#include "lsfg_common.hpp"
#include "lsfg_pacer.hpp"

namespace lsfg {

class LsfgShaders;
class LsfgChain;

// Hard ceiling on generated frames per source frame (2x..4x -> 1..3).
constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    // Both the constructor and destructor are out-of-line ON PURPOSE. The
    // shader set and the chain are held by unique_ptr to forward-declared
    // types, and an inline constructor would make the compiler emit the
    // exception-cleanup path for those members at every construction site -
    // which needs the complete types there. Keeping both here confines that to
    // lsfg_engine.cpp, where the types are complete.
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // Build the shader modules from a cache produced by lsfg_dll::buildCache.
    // Returns false if the cache does not yield all 25 modules.
    bool init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& cachePath);

    bool valid() const { return shaders_ != nullptr && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    void configure(uint32_t multiplier, uint32_t targetRate, float flowScale, float refreshRate);
    void setRefreshRate(float refreshRate);
    void setGuestExtent(uint32_t width, uint32_t height);

    bool needsRebuild(uint32_t width, uint32_t height, VkFormat format) const;
    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    // How many frames to generate for this source frame. Returns 0 until the
    // history slots are valid and the rates have settled, so the first frames
    // after a start or a resize are never interpolated from garbage.
    uint32_t plan(uint32_t capacity, uint64_t sourceFrames);

    // Take the just-composited frame as LSFG input and run the shared part of
    // the chain (everything except `generate`). `source` must be in GENERAL.
    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    // Synthesise generated frame `generation` into the given storage image.
    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);

    float    sourceRate() const;
    // The rate that actually reaches the panel, generated frames included.
    // Supplied by the renderer, which is the only thing that counts presents.
    float    loopRate() const { return presentedRate_; }
    void     setPresentedRate(float fps) { presentedRate_ = fps; }

    void forgetTargets();
    void reset();

    // Drop the interpolation chain (all per-resolution images and descriptor
    // sets) but keep the 25 compiled shader modules, so turning frame
    // generation off frees its VRAM while turning it back on only rebuilds the
    // chain, not the pipelines. The next prepare() rebuilds from scratch.
    void releaseChain();

private:
    float effectiveFlowScale(uint32_t width) const;

    bool historyFresh() const { return haveCopied_ && lastCopiedCount_ + 1 == frameCount_; }
    bool adaptive() const { return pacer_.Config().target_rate != 0; }

    Device      device_{};
    std::string cachePath_;
    std::unique_ptr<LsfgShaders> shaders_;
    std::unique_ptr<LsfgChain>   chain_;
    LsfgPacer      pacer_;
    LsfgPlan    plan_{};

    VkExtent2D builtExtent_{};
    VkExtent2D peakGuestExtent_{};
    VkFormat   builtFormat_{VK_FORMAT_UNDEFINED};
    float      builtFlowScale_{};
    float      flowScale_{1.0f};

    float    presentedRate_{};

    uint64_t frameCount_{};
    uint64_t lastCopiedCount_{};
    bool     haveCopied_{};
    bool     primeHistory_{};
    // Last frame the shared (flow-pyramid) chain actually ran for. The alpha
    // stage keeps a two-slot history of its own; a gap in shared dispatches
    // leaves the other slot stale, so the chain's history is re-primed.
    uint64_t lastSharedCount_{};
    bool     haveShared_{};
    uint64_t primeLogCount_{};
    uint64_t lastCount_{};
    size_t   lastGenerations_{};
    uint64_t planCalls_{};
    uint32_t warmStreak_{};
    bool     warm_{};
    bool     generating_{};
    // Adaptive mode, warm and armed: the pacer may plan zero generations for
    // some source frames (target < 2x source), so the input ring and the
    // shared chain are kept running on every frame to keep history continuous.
    bool     keepHistory_{};
    bool     unavailable_{};
};

} // namespace lsfg
