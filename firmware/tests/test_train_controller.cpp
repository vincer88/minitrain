#include "minitrain/train_controller.hpp"

#include <chrono>
#include <iostream>
#include <vector>

#include "test_suite.hpp"

namespace minitrain::tests {

int runTrainControllerTests() {
    std::vector<float> motorCommands;
    std::vector<TelemetrySample> publishedTelemetry;

    using Clock = std::chrono::steady_clock;
    Clock::time_point now{};
    auto clock = [&now]() { return now; };
    const auto staleThreshold = std::chrono::milliseconds(120);
    const auto pilotReleaseDuration = std::chrono::milliseconds(500);
    const auto rampDuration = std::chrono::milliseconds(300);

    TrainController controller(
        PidController{0.5F, 0.05F, 0.01F, 0.0F, 1.0F},
        [&motorCommands](float command) { motorCommands.push_back(command); },
        [&publishedTelemetry](const TelemetrySample &sample) { publishedTelemetry.push_back(sample); },
        staleThreshold, pilotReleaseDuration, rampDuration, clock);

    controller.registerCommandTimestamp(now);
    controller.setTargetSpeed(1.5F);
    now += std::chrono::milliseconds(50);
    controller.onSpeedMeasurement(0.5F, std::chrono::milliseconds{50});
    TelemetrySample initialSample{};
    initialSample.speedMetersPerSecond = 1.2F;
    initialSample.motorCurrentAmps = 0.4F;
    initialSample.batteryVoltage = 11.2F;
    initialSample.temperatureCelsius = 29.0F;
    initialSample.failSafeActive = false;
    controller.onTelemetrySample(initialSample);
    controller.setActiveCab(ActiveCab::Front);
    controller.setDirection(Direction::Forward);

    if (motorCommands.empty() || motorCommands.back() <= 0.0F) {
        std::cerr << "Motor command should be positive" << std::endl;
        return 1;
    }

    auto state = controller.state();
    if (state.targetSpeed < 1.49F || state.targetSpeed > 1.51F) {
        std::cerr << "Target speed should be stored" << std::endl;
        return 1;
    }
    if (state.failSafeActive) {
        std::cerr << "Fail-safe should not be active under nominal cadence" << std::endl;
        return 1;
    }
    if (state.lightsState != LightsState::FrontWhiteRearRed || state.lightsSource != LightsSource::Automatic ||
        state.activeCab != ActiveCab::Front) {
        std::cerr << "Automatic lighting should prefer front white / rear red with front cab" << std::endl;
        return 1;
    }

    controller.setActiveCab(ActiveCab::Rear);
    controller.setDirection(Direction::Reverse);
    auto reverseState = controller.state();
    if (reverseState.lightsState != LightsState::FrontWhiteRearRed || reverseState.activeCab != ActiveCab::Rear) {
        std::cerr << "Reverse movement with rear cab should light the leading end" << std::endl;
        return 1;
    }

    controller.setActiveCab(ActiveCab::None);
    auto noCabState = controller.state();
    if (noCabState.lightsState != LightsState::BothRed || noCabState.lightsSource != LightsSource::Automatic) {
        std::cerr << "Absence of cab should yield bilateral red lights" << std::endl;
        return 1;
    }

    controller.setActiveCab(ActiveCab::Front);
    controller.setDirection(Direction::Forward);
    controller.setLightsOverride(0x02U | 0x04U, false);
    auto overrideState = controller.state();
    if (overrideState.lightsSource != LightsSource::Override ||
        overrideState.lightsState != LightsState::FrontRedRearWhite || overrideState.lightsOverrideMask != (0x02U | 0x04U)) {
        std::cerr << "Override mask should force rear white and front red" << std::endl;
        return 1;
    }

    controller.triggerEmergencyStop();
    if (!controller.state().emergencyStop) {
        std::cerr << "Emergency stop flag should be set" << std::endl;
        return 1;
    }

    controller.onSpeedMeasurement(1.0F, std::chrono::milliseconds{50});
    if (!motorCommands.empty() && motorCommands.back() != 0.0F) {
        std::cerr << "Motor command should be zero after emergency" << std::endl;
        return 1;
    }

    if (publishedTelemetry.empty()) {
        std::cerr << "Telemetry should be published" << std::endl;
        return 1;
    }
    if (publishedTelemetry.back().failSafeActive) {
        std::cerr << "Fail-safe flag should be false in nominal telemetry" << std::endl;
        return 1;
    }

    auto aggregated = controller.aggregatedTelemetry();
    if (!aggregated || aggregated->batteryVoltage < 11.0F) {
        std::cerr << "Aggregated telemetry should track battery voltage" << std::endl;
        return 1;
    }

    controller.setTargetSpeed(0.0F);
    if (!controller.state().emergencyStop) {
        std::cerr << "Emergency should persist while zero speed requested" << std::endl;
        return 1;
    }

    controller.setTargetSpeed(0.5F);
    if (controller.state().emergencyStop) {
        std::cerr << "Emergency flag should reset when non-zero speed requested" << std::endl;
        return 1;
    }

    motorCommands.clear();
    controller.registerCommandTimestamp(now - staleThreshold - std::chrono::milliseconds(50));
    now += std::chrono::milliseconds(200);
    controller.onSpeedMeasurement(0.4F, std::chrono::milliseconds{50});

    auto failState = controller.state();
    if (!failState.failSafeActive) {
        std::cerr << "Fail-safe should activate when command timestamp is stale" << std::endl;
        return 1;
    }
    if (motorCommands.empty() || motorCommands.back() != 0.0F) {
        std::cerr << "Motor command should be forced to zero during fail-safe" << std::endl;
        return 1;
    }
    if (failState.lightsState != LightsState::BothRed || failState.lightsSource != LightsSource::FailSafe) {
        std::cerr << "Fail-safe should force bilateral red lights" << std::endl;
        return 1;
    }
    if (failState.targetSpeed > 0.5F) {
        std::cerr << "Target speed should start ramping down" << std::endl;
        return 1;
    }

    TelemetrySample failSafeSample{};
    failSafeSample.speedMetersPerSecond = 0.6F;
    failSafeSample.motorCurrentAmps = 0.3F;
    failSafeSample.batteryVoltage = 10.9F;
    failSafeSample.temperatureCelsius = 28.0F;
    failSafeSample.failSafeActive = false;
    controller.onTelemetrySample(failSafeSample);
    if (publishedTelemetry.back().failSafeActive != true ||
        publishedTelemetry.back().lightsSource != LightsSource::FailSafe) {
        std::cerr << "Telemetry should expose fail-safe state" << std::endl;
        return 1;
    }

    now += rampDuration;
    const auto telemetryBeforeRelease = publishedTelemetry.size();
    controller.onSpeedMeasurement(0.2F, std::chrono::milliseconds{50});
    failState = controller.state();
    if (failState.targetSpeed > 0.01F) {
        std::cerr << "Target speed should reach zero after ramp" << std::endl;
        return 1;
    }
    if (failState.direction != Direction::Neutral) {
        std::cerr << "Direction should lock to neutral after ramp" << std::endl;
        return 1;
    }
    if (!failState.pilotReleaseActive) {
        std::cerr << "Pilot release should activate after extended inactivity" << std::endl;
        return 1;
    }
    if (failState.activeCab != ActiveCab::None) {
        std::cerr << "Pilot release should clear active cab" << std::endl;
        return 1;
    }
    if (failState.lightsState != LightsState::BothRed || failState.lightsSource != LightsSource::Automatic) {
        std::cerr << "Pilot release should force bilateral red lights" << std::endl;
        return 1;
    }
    if (publishedTelemetry.size() != telemetryBeforeRelease + 1 ||
        publishedTelemetry.back().activeCab != ActiveCab::None ||
        publishedTelemetry.back().lightsState != LightsState::BothRed ||
        publishedTelemetry.back().lightsSource != LightsSource::Automatic ||
        publishedTelemetry.back().failSafeActive) {
        std::cerr << "Pilot release should publish availability telemetry" << std::endl;
        return 1;
    }

    controller.registerCommandTimestamp(now + staleThreshold);
    auto recoveredState = controller.state();
    if (recoveredState.failSafeActive) {
        std::cerr << "Fail-safe should clear after fresh command" << std::endl;
        return 1;
    }
    if (recoveredState.pilotReleaseActive) {
        std::cerr << "Pilot release should clear after fresh command" << std::endl;
        return 1;
    }
    if (recoveredState.lightsSource != LightsSource::Override ||
        recoveredState.lightsState != LightsState::FrontRedRearWhite) {
        std::cerr << "Override lighting should be restored after fail-safe" << std::endl;
        return 1;
    }

    controller.setLightsOverride(0x00U, false);
    controller.setTargetSpeed(0.0F);
    controller.setDirection(Direction::Forward);

    return 0;
}

} // namespace minitrain::tests
