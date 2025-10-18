#include "minitrain/train_controller.hpp"

#include "minitrain/light_controller.hpp"

#include <algorithm>
#include <chrono>
#include <cstdint>

namespace minitrain {

namespace {
constexpr float clampMotorCommand(float value) {
    return std::max(0.0F, std::min(1.0F, value));
}

void updateLights(TrainState &state) {
    LightController::applyAutomaticLogic(state);
}

struct FailSafeTelemetryMetrics {
    float progress{0.0F};
    std::uint32_t elapsedMillis{0};
};

FailSafeTelemetryMetrics computeFailSafeTelemetry(const TrainState &state,
                                                  std::chrono::steady_clock::time_point now) {
    FailSafeTelemetryMetrics metrics{};
    if (!state.failSafeActive) {
        return metrics;
    }

    if (state.realtime.failSafeRampStart) {
        const auto elapsed = now - *state.realtime.failSafeRampStart;
        const auto elapsedMillis =
            std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
        metrics.elapsedMillis = elapsedMillis < 0 ? 0U : static_cast<std::uint32_t>(elapsedMillis);

        const auto duration = state.failSafeRampDuration;
        if (duration <= std::chrono::steady_clock::duration::zero()) {
            metrics.progress = 1.0F;
        } else {
            const auto durationMillis =
                std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
            if (durationMillis > 0) {
                const float ratio = static_cast<float>(metrics.elapsedMillis) /
                                    static_cast<float>(durationMillis);
                metrics.progress = std::max(0.0F, std::min(ratio, 1.0F));
            }
        }
    } else {
        metrics.elapsedMillis = 0;
        metrics.progress = 0.0F;
    }

    return metrics;
}

TelemetrySample makeAvailabilitySample(const TrainState &state,
                                       std::chrono::steady_clock::time_point now) {
    TelemetrySample sample{};
    sample.speedMetersPerSecond = state.appliedSpeed;
    sample.batteryVoltage = state.batteryVoltage;
    sample.failSafeActive = state.failSafeActive;
    const auto metrics = computeFailSafeTelemetry(state, now);
    sample.failSafeProgress = metrics.progress;
    sample.failSafeElapsedMillis = metrics.elapsedMillis;
    sample.lightsState = state.lightsState;
    sample.lightsSource = state.lightsSource;
    sample.activeCab = state.activeCab;
    sample.lightsOverrideMask = state.lightsOverrideMask;
    sample.lightsTelemetryOnly = state.lightsTelemetryOnly;
    sample.appliedSpeedMetersPerSecond = state.appliedSpeed;
    sample.appliedDirection = state.direction;
    sample.source = TelemetrySource::Instantaneous;
    return sample;
}
} // namespace

TrainController::TrainController(PidController speedController, MotorCommandWriter motorWriter,
                                 TelemetryPublisher telemetryPublisher,
                                 std::chrono::steady_clock::duration staleCommandThreshold,
                                 std::chrono::steady_clock::duration pilotReleaseDuration,
                                 std::chrono::steady_clock::duration failSafeRampDuration, Clock clock)
    : state_{}, pid_{std::move(speedController)}, motorWriter_{std::move(motorWriter)},
      telemetryPublisher_{std::move(telemetryPublisher)}, telemetryAggregator_{20},
      staleCommandThreshold_{staleCommandThreshold}, pilotReleaseDuration_{pilotReleaseDuration},
      failSafeRampDuration_{failSafeRampDuration},
      clock_{std::move(clock)} {
    if (!clock_) {
        clock_ = [] { return std::chrono::steady_clock::now(); };
    }
    state_.realtime.lastCommandTimestamp = clock_();
    state_.failSafeRampDuration = failSafeRampDuration_;
    state_.pilotReleaseDuration = pilotReleaseDuration_;
    state_.pilotReleaseActive = false;
    state_.realtime.pilotReleaseTelemetrySent = false;
}

void TrainController::setTargetSpeed(float metersPerSecond) {
    std::scoped_lock lock(mutex_);
    state_.updateTargetSpeed(metersPerSecond);
    if (state_.emergencyStop && metersPerSecond > 0.0F) {
        state_.emergencyStop = false;
    }
    updateLights(state_);
}

void TrainController::setDirection(Direction direction) {
    std::scoped_lock lock(mutex_);
    state_.setDirection(direction);
    if (direction == Direction::Neutral) {
        state_.setActiveCab(ActiveCab::None);
    } else if (state_.activeCab == ActiveCab::None) {
        state_.setActiveCab(direction == Direction::Forward ? ActiveCab::Front : ActiveCab::Rear);
    }
    updateLights(state_);
}

void TrainController::toggleHeadlights(bool enabled) {
    std::scoped_lock lock(mutex_);
    const std::uint8_t mask = enabled ? 0x01U : 0x00U;
    state_.setLightsOverride(mask, false);
    updateLights(state_);
}

void TrainController::toggleHorn(bool enabled) {
    std::scoped_lock lock(mutex_);
    state_.setHorn(enabled);
}

void TrainController::setActiveCab(ActiveCab cab) {
    std::scoped_lock lock(mutex_);
    state_.setActiveCab(cab);
    updateLights(state_);
}

void TrainController::setLightsOverride(std::uint8_t mask, bool telemetryOnly) {
    std::scoped_lock lock(mutex_);
    state_.setLightsOverride(mask, telemetryOnly);
    if (!telemetryOnly) {
        updateLights(state_);
    }
}

void TrainController::triggerEmergencyStop() {
    std::scoped_lock lock(mutex_);
    state_.applyEmergencyStop();
    pid_.reset();
    motorWriter_(0.0F);
    updateLights(state_);
}

void TrainController::onSpeedMeasurement(float measuredSpeed, std::chrono::steady_clock::duration dt) {
    std::scoped_lock lock(mutex_);
    const auto now = clock_();
    state_.updateAppliedSpeed(measuredSpeed);
    if (state_.emergencyStop) {
        motorWriter_(0.0F);
        return;
    }
    const auto age = now - state_.realtime.lastCommandTimestamp;
    const bool pilotReleaseEnabled = pilotReleaseDuration_ > std::chrono::steady_clock::duration::zero();
    bool pilotReleaseTriggered = false;

    if (!state_.pilotReleaseActive && pilotReleaseEnabled && age > pilotReleaseDuration_) {
        state_.pilotReleaseActive = true;
        pilotReleaseTriggered = true;
        state_.failSafeActive = false;
        state_.realtime.failSafeRampStart.reset();
        state_.realtime.lightsLatched = false;
        if (!state_.realtime.pilotReleaseLightsLatched) {
            state_.realtime.lightsOverrideMaskBeforePilotRelease = state_.lightsOverrideMask;
            state_.realtime.lightsTelemetryOnlyBeforePilotRelease = state_.lightsTelemetryOnly;
            state_.realtime.pilotReleaseLightsLatched = true;
        }
        state_.lightsOverrideMask = 0;
        state_.lightsTelemetryOnly = false;
        state_.setDirection(Direction::Neutral);
        state_.setActiveCab(ActiveCab::None);
        state_.updateTargetSpeed(0.0F);
        pid_.reset();
    }

    if (!state_.pilotReleaseActive && age > staleCommandThreshold_) {
        if (!state_.failSafeActive) {
            state_.failSafeActive = true;
            state_.realtime.failSafeRampStart = now;
            state_.realtime.failSafeInitialTarget = state_.targetSpeed;
            state_.realtime.lightsBeforeFailSafe = state_.lightsState;
            state_.realtime.lightsSourceBeforeFailSafe = state_.lightsSource;
            state_.realtime.lightsLatched = true;
        }
    } else if (state_.failSafeActive && (age <= staleCommandThreshold_ || state_.pilotReleaseActive)) {
        state_.failSafeActive = false;
        state_.realtime.failSafeRampStart.reset();
        if (state_.realtime.lightsLatched && !state_.pilotReleaseActive) {
            state_.lightsState = state_.realtime.lightsBeforeFailSafe;
            state_.lightsSource = state_.realtime.lightsSourceBeforeFailSafe;
        }
        state_.realtime.lightsLatched = false;
    }

    updateLights(state_);

    if (state_.pilotReleaseActive && (!state_.realtime.pilotReleaseTelemetrySent || pilotReleaseTriggered)) {
        telemetryPublisher_(makeAvailabilitySample(state_, now));
        state_.realtime.pilotReleaseTelemetrySent = true;
    }

    if (state_.failSafeActive) {
        const auto rampDuration = state_.failSafeRampDuration.count() <= 0
                                      ? std::chrono::steady_clock::duration::zero()
                                      : state_.failSafeRampDuration;
        float newTarget = 0.0F;
        if (state_.realtime.failSafeRampStart) {
            const auto elapsed = now - *state_.realtime.failSafeRampStart;
            if (rampDuration > std::chrono::steady_clock::duration::zero() && elapsed < rampDuration) {
                const auto elapsedSeconds = std::chrono::duration_cast<std::chrono::duration<float>>(elapsed);
                const auto rampSeconds = std::chrono::duration_cast<std::chrono::duration<float>>(rampDuration);
                float ratio = 1.0F;
                if (rampSeconds.count() > 0.0F) {
                    ratio = std::max(0.0F, 1.0F - (elapsedSeconds.count() / rampSeconds.count()));
                }
                newTarget = state_.realtime.failSafeInitialTarget * ratio;
            }
            if (rampDuration == std::chrono::steady_clock::duration::zero() || elapsed >= rampDuration) {
                state_.setDirection(Direction::Neutral);
                state_.setActiveCab(ActiveCab::None);
            }
        } else {
            state_.realtime.failSafeRampStart = now;
        }
        state_.updateTargetSpeed(newTarget);
        motorWriter_(0.0F);
        return;
    }

    if (state_.pilotReleaseActive) {
        motorWriter_(0.0F);
        return;
    }

    const float pidOutput = pid_.update(state_.targetSpeed, measuredSpeed, dt);
    motorWriter_(clampMotorCommand(pidOutput));
}

void TrainController::onTelemetrySample(const TelemetrySample &sample) {
    std::scoped_lock lock(mutex_);
    const auto now = clock_();
    TelemetrySample enriched = sample;
    enriched.failSafeActive = state_.failSafeActive;
    const auto metrics = computeFailSafeTelemetry(state_, now);
    enriched.failSafeProgress = metrics.progress;
    enriched.failSafeElapsedMillis = metrics.elapsedMillis;
    enriched.lightsState = state_.lightsState;
    enriched.lightsSource = state_.lightsSource;
    enriched.activeCab = state_.activeCab;
    enriched.lightsOverrideMask = state_.lightsOverrideMask;
    enriched.lightsTelemetryOnly = state_.lightsTelemetryOnly;
    enriched.appliedSpeedMetersPerSecond = state_.appliedSpeed;
    enriched.appliedDirection = state_.direction;
    enriched.source = TelemetrySource::Instantaneous;
    telemetryAggregator_.addSample(enriched);
    state_.setBatteryVoltage(sample.batteryVoltage);
    telemetryPublisher_(enriched);
}

void TrainController::registerCommandTimestamp(std::chrono::steady_clock::time_point timestamp) {
    std::scoped_lock lock(mutex_);
    const bool wasFailSafeActive = state_.failSafeActive;
    const bool wasPilotReleased = state_.pilotReleaseActive;
    state_.updateCommandTimestamp(timestamp);
    if (wasFailSafeActive) {
        if (state_.realtime.lightsLatched) {
            state_.lightsState = state_.realtime.lightsBeforeFailSafe;
            state_.lightsSource = state_.realtime.lightsSourceBeforeFailSafe;
            state_.realtime.lightsLatched = false;
        }
    }
    if (wasPilotReleased) {
        state_.pilotReleaseActive = false;
        if (state_.realtime.pilotReleaseLightsLatched) {
            state_.lightsOverrideMask = state_.realtime.lightsOverrideMaskBeforePilotRelease;
            state_.lightsTelemetryOnly = state_.realtime.lightsTelemetryOnlyBeforePilotRelease;
            state_.realtime.pilotReleaseLightsLatched = false;
        }
    }
    updateLights(state_);
}

TrainState TrainController::state() const {
    std::scoped_lock lock(mutex_);
    return state_;
}

std::optional<TelemetrySample> TrainController::aggregatedTelemetry() const {
    std::scoped_lock lock(mutex_);
    return telemetryAggregator_.average();
}

} // namespace minitrain
