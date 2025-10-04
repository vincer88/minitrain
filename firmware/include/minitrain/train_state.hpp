#pragma once

#include <chrono>

namespace minitrain {

enum class Direction {
    Forward = 1,
    Reverse = -1
};

struct TrainState {
    Direction direction{Direction::Forward};
    float targetSpeed{0.0F};
    float appliedSpeed{0.0F};
    bool headlights{false};
    bool horn{false};
    bool emergencyStop{false};
    float batteryVoltage{0.0F};
    std::chrono::steady_clock::time_point lastUpdated{std::chrono::steady_clock::now()};

    void applyEmergencyStop();
    void updateTargetSpeed(float newTarget);
    void updateAppliedSpeed(float measuredSpeed);
    void setDirection(Direction newDirection);
    void setHeadlights(bool enabled);
    void setHorn(bool enabled);
    void setBatteryVoltage(float voltage);
};

} // namespace minitrain
