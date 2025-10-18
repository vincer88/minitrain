#pragma once

#include <chrono>
#include <cstdint>
#include <optional>

namespace minitrain {

enum class Direction {
    Reverse = -1,
    Neutral = 0,
    Forward = 1
};

enum class ActiveCab : std::uint8_t {
    None = 0,
    Front = 1,
    Rear = 2
};

enum class LightsState : std::uint8_t {
    BothRed = 0,
    FrontWhiteRearRed = 1,
    FrontRedRearWhite = 2,
    BothOff = 3,
    BothWhite = 4,
    BothRedFlashing = 5
};

enum class LightsSource : std::uint8_t {
    Automatic = 0,
    Override = 1,
    FailSafe = 2
};

struct RealtimeSession {
    std::chrono::steady_clock::time_point lastCommandTimestamp{std::chrono::steady_clock::now()};
    std::optional<std::chrono::steady_clock::time_point> failSafeRampStart{};
    float failSafeInitialTarget{0.0F};
    LightsState lightsBeforeFailSafe{LightsState::BothRed};
    LightsSource lightsSourceBeforeFailSafe{LightsSource::Automatic};
    bool lightsLatched{false};
    bool pilotReleaseTelemetrySent{false};
    std::uint8_t lightsOverrideMaskBeforePilotRelease{0};
    bool lightsTelemetryOnlyBeforePilotRelease{false};
    bool pilotReleaseLightsLatched{false};
};

struct TrainState {
    Direction direction{Direction::Forward};
    float targetSpeed{0.0F};
    float appliedSpeed{0.0F};
    bool horn{false};
    bool emergencyStop{false};
    float batteryVoltage{0.0F};
    std::chrono::steady_clock::time_point lastUpdated{std::chrono::steady_clock::now()};
    std::chrono::steady_clock::duration failSafeRampDuration{};
    std::chrono::steady_clock::duration pilotReleaseDuration{};
    bool failSafeActive{false};
    bool pilotReleaseActive{false};
    ActiveCab activeCab{ActiveCab::None};
    LightsState lightsState{LightsState::BothRed};
    LightsSource lightsSource{LightsSource::Automatic};
    std::uint8_t lightsOverrideMask{0};
    bool lightsTelemetryOnly{false};
    RealtimeSession realtime{};

    void applyEmergencyStop();
    void updateTargetSpeed(float newTarget);
    void updateAppliedSpeed(float measuredSpeed);
    void setDirection(Direction newDirection);
    void setActiveCab(ActiveCab cab);
    void setLightsOverride(std::uint8_t mask, bool telemetryOnly);
    void setHorn(bool enabled);
    void setBatteryVoltage(float voltage);
    void updateCommandTimestamp(std::chrono::steady_clock::time_point timestamp);
};

} // namespace minitrain
