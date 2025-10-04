#pragma once

#include <chrono>
#include <functional>
#include <mutex>

#include "minitrain/pid_controller.hpp"
#include "minitrain/telemetry.hpp"
#include "minitrain/train_state.hpp"

namespace minitrain {

class TrainController {
  public:
    using MotorCommandWriter = std::function<void(float)>;
    using TelemetryPublisher = std::function<void(const TelemetrySample &)>;

    TrainController(PidController speedController, MotorCommandWriter motorWriter, TelemetryPublisher telemetryPublisher);

    void setTargetSpeed(float metersPerSecond);
    void setDirection(Direction direction);
    void toggleHeadlights(bool enabled);
    void toggleHorn(bool enabled);
    void triggerEmergencyStop();

    void onSpeedMeasurement(float measuredSpeed, std::chrono::steady_clock::duration dt);
    void onTelemetrySample(const TelemetrySample &sample);

    [[nodiscard]] TrainState state() const;
    [[nodiscard]] std::optional<TelemetrySample> aggregatedTelemetry() const;

  private:
    mutable std::mutex mutex_;
    TrainState state_;
    PidController pid_;
    MotorCommandWriter motorWriter_;
    TelemetryPublisher telemetryPublisher_;
    TelemetryAggregator telemetryAggregator_;
};

} // namespace minitrain
