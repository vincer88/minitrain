#include "minitrain/train_state.hpp"

#include <algorithm>

namespace minitrain {

namespace {
constexpr float clamp(float value, float minValue, float maxValue) {
    return std::max(minValue, std::min(maxValue, value));
}
} // namespace

void TrainState::applyEmergencyStop() {
    emergencyStop = true;
    targetSpeed = 0.0F;
    appliedSpeed = 0.0F;
    lastUpdated = std::chrono::steady_clock::now();
    failSafeActive = false;
    realtime.failSafeRampStart.reset();
}

void TrainState::updateTargetSpeed(float newTarget) {
    targetSpeed = clamp(newTarget, 0.0F, 5.0F);
    if (!emergencyStop) {
        lastUpdated = std::chrono::steady_clock::now();
    }
}

void TrainState::updateAppliedSpeed(float measuredSpeed) {
    appliedSpeed = clamp(measuredSpeed, 0.0F, 5.0F);
    lastUpdated = std::chrono::steady_clock::now();
}

void TrainState::setDirection(Direction newDirection) {
    direction = newDirection;
    lastUpdated = std::chrono::steady_clock::now();
}

void TrainState::setHeadlights(bool enabled) {
    headlights = enabled;
    lastUpdated = std::chrono::steady_clock::now();
}

void TrainState::setHorn(bool enabled) {
    horn = enabled;
    lastUpdated = std::chrono::steady_clock::now();
}

void TrainState::setBatteryVoltage(float voltage) {
    batteryVoltage = clamp(voltage, 0.0F, 12.6F);
    lastUpdated = std::chrono::steady_clock::now();
}

void TrainState::updateCommandTimestamp(std::chrono::steady_clock::time_point timestamp) {
    realtime.lastCommandTimestamp = timestamp;
    lastUpdated = std::chrono::steady_clock::now();
}

} // namespace minitrain
