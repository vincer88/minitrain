#pragma once

#include <chrono>
#include <optional>

namespace minitrain {

enum class Direction {
    Reverse = -1,
    Neutral = 0,
    Forward = 1
};

struct RealtimeSession {
    std::chrono::steady_clock::time_point lastCommandTimestamp{std::chrono::steady_clock::now()};
    std::optional<std::chrono::steady_clock::time_point> failSafeRampStart{};
    float failSafeInitialTarget{0.0F};
    bool headlightsBeforeFailSafe{false};
    bool headlightsLatched{false};
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
    std::chrono::steady_clock::duration failSafeRampDuration{};
    bool failSafeActive{false};
    RealtimeSession realtime{};

    void applyEmergencyStop();
    void updateTargetSpeed(float newTarget);
    void updateAppliedSpeed(float measuredSpeed);
    void setDirection(Direction newDirection);
    void setHeadlights(bool enabled);
    void setHorn(bool enabled);
    void setBatteryVoltage(float voltage);
    void updateCommandTimestamp(std::chrono::steady_clock::time_point timestamp);
};

} // namespace minitrain
