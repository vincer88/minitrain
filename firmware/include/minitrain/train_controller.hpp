#pragma once

#include <chrono>
#include <functional>
#include <mutex>
#include <cstdint>

#include "minitrain/pid_controller.hpp"
#include "minitrain/telemetry.hpp"
#include "minitrain/train_state.hpp"

namespace minitrain {

#ifndef MINITRAIN_FAILSAFE_THRESHOLD_MS
#define MINITRAIN_FAILSAFE_THRESHOLD_MS 150
#endif

#ifndef MINITRAIN_PILOT_RELEASE_MS
#define MINITRAIN_PILOT_RELEASE_MS 5000
#endif

#ifndef MINITRAIN_FAILSAFE_RAMP_MS
#define MINITRAIN_FAILSAFE_RAMP_MS 1000
#endif

class TrainController {
  public:
    using MotorCommandWriter = std::function<void(float)>;
    using TelemetryPublisher = std::function<void(const TelemetrySample &)>;
    using Clock = std::function<std::chrono::steady_clock::time_point()>;

    TrainController(PidController speedController, MotorCommandWriter motorWriter, TelemetryPublisher telemetryPublisher,
                   std::chrono::steady_clock::duration staleCommandThreshold =
                       std::chrono::milliseconds{MINITRAIN_FAILSAFE_THRESHOLD_MS},
                   std::chrono::steady_clock::duration pilotReleaseDuration =
                       std::chrono::milliseconds{MINITRAIN_PILOT_RELEASE_MS},
                   std::chrono::steady_clock::duration failSafeRampDuration =
                       std::chrono::milliseconds{MINITRAIN_FAILSAFE_RAMP_MS},
                   Clock clock = {});

    void setTargetSpeed(float metersPerSecond);
    void setDirection(Direction direction);
    void toggleHeadlights(bool enabled);
    void toggleHorn(bool enabled);
    void triggerEmergencyStop();
    void setActiveCab(ActiveCab cab);
    void setLightsOverride(std::uint8_t mask, bool telemetryOnly);

    void onSpeedMeasurement(float measuredSpeed, std::chrono::steady_clock::duration dt);
    void onTelemetrySample(const TelemetrySample &sample);
    void registerCommandTimestamp(std::chrono::steady_clock::time_point timestamp);

    [[nodiscard]] TrainState state() const;
    [[nodiscard]] std::optional<TelemetrySample> aggregatedTelemetry() const;

  private:
    mutable std::mutex mutex_;
    TrainState state_;
    PidController pid_;
    MotorCommandWriter motorWriter_;
    TelemetryPublisher telemetryPublisher_;
    TelemetryAggregator telemetryAggregator_;
    std::chrono::steady_clock::duration staleCommandThreshold_;
    std::chrono::steady_clock::duration pilotReleaseDuration_;
    std::chrono::steady_clock::duration failSafeRampDuration_;
    Clock clock_;
};

} // namespace minitrain
