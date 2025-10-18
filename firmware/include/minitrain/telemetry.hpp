#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

#include "minitrain/train_state.hpp"

namespace minitrain {

enum class TelemetrySource : std::uint8_t {
    Instantaneous = 0,
    Aggregated = 1,
};

struct TelemetrySample {
    float speedMetersPerSecond{0.0F};
    float motorCurrentAmps{0.0F};
    float batteryVoltage{0.0F};
    float temperatureCelsius{0.0F};
    bool failSafeActive{false};
    float failSafeProgress{0.0F};
    std::uint32_t failSafeElapsedMillis{0};
    LightsState lightsState{LightsState::BothRed};
    LightsSource lightsSource{LightsSource::Automatic};
    ActiveCab activeCab{ActiveCab::None};
    std::uint8_t lightsOverrideMask{0};
    bool lightsTelemetryOnly{false};
    std::array<std::uint8_t, 16> sessionId{};
    std::uint32_t sequence{0};
    std::uint64_t commandTimestamp{0};
    float appliedSpeedMetersPerSecond{0.0F};
    Direction appliedDirection{Direction::Neutral};
    TelemetrySource source{TelemetrySource::Instantaneous};
};

class TelemetryAggregator {
  public:
    explicit TelemetryAggregator(std::size_t windowSize = 10);

    void addSample(const TelemetrySample &sample);
    [[nodiscard]] std::optional<TelemetrySample> average() const;
    [[nodiscard]] std::vector<TelemetrySample> history() const;
    void clear();

  private:
    std::vector<TelemetrySample> samples_;
    std::size_t windowSize_;
};

} // namespace minitrain
