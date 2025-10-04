#pragma once

#include <array>
#include <cstddef>
#include <optional>
#include <vector>

namespace minitrain {

struct TelemetrySample {
    float speedMetersPerSecond{0.0F};
    float motorCurrentAmps{0.0F};
    float batteryVoltage{0.0F};
    float temperatureCelsius{0.0F};
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
