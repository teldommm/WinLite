
#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <optional>

namespace aifg {

constexpr size_t AIFG_MAX_MULTIPLIER = 4;

struct AifgPacerConfig {
    uint32_t multiplier{2};
    uint32_t target_rate{};
    float refresh_rate{};
};

struct AifgPlan {
    size_t generations{};
    bool warm{};
};

struct AifgPacerStats {
    float source_rate{};
    float loop_rate{};
    float refresh_rate{};
    float target_rate{};
    float slots{};
    size_t limit{};
    bool rates_settled{};
    uint64_t last_drawn{};
    float last_elapsed{};
    uint64_t source_frames{};
};

class AifgPacer {
public:
    void SetConfig(const AifgPacerConfig& config_) {
        config = config_;
    }

    [[nodiscard]] const AifgPacerConfig& Config() const {
        return config;
    }

    [[nodiscard]] size_t MaxGenerations() const;

    [[nodiscard]] AifgPlan Plan(size_t capacity, uint64_t source_frames);

    [[nodiscard]] AifgPacerStats Stats() const;

    void Reset();

private:
    using Clock = std::chrono::steady_clock;

    void TrackSourceRate(Clock::time_point now, uint64_t source_frames);
    void TrackLoopRate(float interval_seconds);
    [[nodiscard]] bool RatesSettled() const;
    [[nodiscard]] size_t HeadroomLimit() const;

    AifgPacerConfig config;

    std::optional<Clock::time_point> last_frame;
    std::optional<Clock::time_point> last_source_sample;
    uint64_t last_source_frames{};
    float source_interval{};
    float source_frame_accum{};
    float source_time_accum{};
    float loop_interval{};
    uint32_t source_samples{};
    uint32_t loop_samples{};
    uint64_t last_drawn{};
    float last_elapsed{};
    float output_credit{};
    size_t limit{};
};

}
