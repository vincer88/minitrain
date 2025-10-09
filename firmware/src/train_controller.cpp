#include "minitrain/train_controller.hpp"

#include <algorithm>

namespace minitrain {

namespace {
constexpr float clampMotorCommand(float value) {
    return std::max(0.0F, std::min(1.0F, value));
}
} // namespace

TrainController::TrainController(PidController speedController, MotorCommandWriter motorWriter,
                                 TelemetryPublisher telemetryPublisher,
                                 std::chrono::steady_clock::duration staleCommandThreshold,
                                 std::chrono::steady_clock::duration failSafeRampDuration, Clock clock)
    : state_{}, pid_{std::move(speedController)}, motorWriter_{std::move(motorWriter)},
      telemetryPublisher_{std::move(telemetryPublisher)}, telemetryAggregator_{20},
      staleCommandThreshold_{staleCommandThreshold}, failSafeRampDuration_{failSafeRampDuration},
      clock_{std::move(clock)} {
    if (!clock_) {
        clock_ = [] { return std::chrono::steady_clock::now(); };
    }
    state_.realtime.lastCommandTimestamp = clock_();
    state_.failSafeRampDuration = failSafeRampDuration_;
}

void TrainController::setTargetSpeed(float metersPerSecond) {
    std::scoped_lock lock(mutex_);
    state_.updateTargetSpeed(metersPerSecond);
    if (state_.emergencyStop && metersPerSecond > 0.0F) {
        state_.emergencyStop = false;
    }
}

void TrainController::setDirection(Direction direction) {
    std::scoped_lock lock(mutex_);
    state_.setDirection(direction);
}

void TrainController::toggleHeadlights(bool enabled) {
    std::scoped_lock lock(mutex_);
    state_.setHeadlights(enabled);
}

void TrainController::toggleHorn(bool enabled) {
    std::scoped_lock lock(mutex_);
    state_.setHorn(enabled);
}

void TrainController::triggerEmergencyStop() {
    std::scoped_lock lock(mutex_);
    state_.applyEmergencyStop();
    pid_.reset();
    motorWriter_(0.0F);
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
    if (age > staleCommandThreshold_) {
        if (!state_.failSafeActive) {
            state_.failSafeActive = true;
            state_.realtime.failSafeRampStart = now;
            state_.realtime.failSafeInitialTarget = state_.targetSpeed;
            state_.realtime.headlightsBeforeFailSafe = state_.headlights;
            state_.realtime.headlightsLatched = true;
            state_.setHeadlights(true);
        }
    } else if (state_.failSafeActive) {
        state_.failSafeActive = false;
        state_.realtime.failSafeRampStart.reset();
        if (state_.realtime.headlightsLatched) {
            state_.setHeadlights(state_.realtime.headlightsBeforeFailSafe);
            state_.realtime.headlightsLatched = false;
        }
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
            }
        } else {
            state_.realtime.failSafeRampStart = now;
        }
        state_.updateTargetSpeed(newTarget);
        motorWriter_(0.0F);
        return;
    }
    const float pidOutput = pid_.update(state_.targetSpeed, measuredSpeed, dt);
    motorWriter_(clampMotorCommand(pidOutput));
}

void TrainController::onTelemetrySample(const TelemetrySample &sample) {
    std::scoped_lock lock(mutex_);
    TelemetrySample enriched = sample;
    enriched.failSafeActive = state_.failSafeActive;
    telemetryAggregator_.addSample(enriched);
    state_.setBatteryVoltage(sample.batteryVoltage);
    telemetryPublisher_(enriched);
}

void TrainController::registerCommandTimestamp(std::chrono::steady_clock::time_point timestamp) {
    std::scoped_lock lock(mutex_);
    state_.updateCommandTimestamp(timestamp);
    if (state_.failSafeActive) {
        state_.failSafeActive = false;
        state_.realtime.failSafeRampStart.reset();
        if (state_.realtime.headlightsLatched) {
            state_.setHeadlights(state_.realtime.headlightsBeforeFailSafe);
            state_.realtime.headlightsLatched = false;
        }
    }
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
