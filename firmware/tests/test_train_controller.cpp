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
    const auto rampDuration = std::chrono::milliseconds(300);

    TrainController controller(
        PidController{0.5F, 0.05F, 0.01F, 0.0F, 1.0F},
        [&motorCommands](float command) { motorCommands.push_back(command); },
        [&publishedTelemetry](const TelemetrySample &sample) { publishedTelemetry.push_back(sample); },
        staleThreshold, rampDuration, clock);

    controller.registerCommandTimestamp(now);
    controller.setTargetSpeed(1.5F);
    now += std::chrono::milliseconds(50);
    controller.onSpeedMeasurement(0.5F, std::chrono::milliseconds{50});
    controller.onTelemetrySample(TelemetrySample{1.2F, 0.4F, 11.2F, 29.0F, false});

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

    controller.toggleHeadlights(false);
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
    if (!failState.headlights) {
        std::cerr << "Headlights should be forced on during fail-safe ramp" << std::endl;
        return 1;
    }
    if (failState.targetSpeed > 0.5F) {
        std::cerr << "Target speed should start ramping down" << std::endl;
        return 1;
    }

    controller.onTelemetrySample(TelemetrySample{0.6F, 0.3F, 10.9F, 28.0F, false});
    if (publishedTelemetry.back().failSafeActive != true) {
        std::cerr << "Telemetry should expose fail-safe state" << std::endl;
        return 1;
    }

    now += rampDuration;
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

    controller.registerCommandTimestamp(now + staleThreshold);
    auto recoveredState = controller.state();
    if (recoveredState.failSafeActive) {
        std::cerr << "Fail-safe should clear after fresh command" << std::endl;
        return 1;
    }
    if (recoveredState.headlights) {
        std::cerr << "Headlights should restore user preference after fail-safe" << std::endl;
        return 1;
    }

    controller.setTargetSpeed(0.0F);
    controller.setDirection(Direction::Forward);

    return 0;
}

} // namespace minitrain::tests
