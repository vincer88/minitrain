#include "minitrain/train_controller.hpp"

#include <chrono>
#include <iostream>
#include <vector>

#include "test_suite.hpp"

namespace minitrain::tests {

int runTrainControllerTests() {
    std::vector<float> motorCommands;
    std::vector<TelemetrySample> publishedTelemetry;

    TrainController controller(PidController{0.5F, 0.05F, 0.01F, 0.0F, 1.0F},
                               [&motorCommands](float command) { motorCommands.push_back(command); },
                               [&publishedTelemetry](const TelemetrySample &sample) {
                                   publishedTelemetry.push_back(sample);
                               });

    controller.setTargetSpeed(1.5F);
    controller.onSpeedMeasurement(0.5F, std::chrono::milliseconds{50});
    controller.onTelemetrySample(TelemetrySample{1.2F, 0.4F, 11.2F, 29.0F});

    if (motorCommands.empty() || motorCommands.back() <= 0.0F) {
        std::cerr << "Motor command should be positive" << std::endl;
        return 1;
    }

    auto state = controller.state();
    if (state.targetSpeed < 1.49F || state.targetSpeed > 1.51F) {
        std::cerr << "Target speed should be stored" << std::endl;
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

    return 0;
}

} // namespace minitrain::tests
