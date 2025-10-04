#include "minitrain/train_controller.hpp"

#include <algorithm>

namespace minitrain {

namespace {
constexpr float clampMotorCommand(float value) {
    return std::max(0.0F, std::min(1.0F, value));
}
} // namespace

TrainController::TrainController(PidController speedController, MotorCommandWriter motorWriter,
                                 TelemetryPublisher telemetryPublisher)
    : state_{}, pid_{std::move(speedController)}, motorWriter_{std::move(motorWriter)},
      telemetryPublisher_{std::move(telemetryPublisher)}, telemetryAggregator_{20} {}

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
    state_.updateAppliedSpeed(measuredSpeed);
    if (state_.emergencyStop) {
        motorWriter_(0.0F);
        return;
    }
    const float pidOutput = pid_.update(state_.targetSpeed, measuredSpeed, dt);
    motorWriter_(clampMotorCommand(pidOutput));
}

void TrainController::onTelemetrySample(const TelemetrySample &sample) {
    std::scoped_lock lock(mutex_);
    telemetryAggregator_.addSample(sample);
    state_.setBatteryVoltage(sample.batteryVoltage);
    telemetryPublisher_(sample);
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
