#include <chrono>
#include <iostream>
#include <thread>

#include "minitrain/command_processor.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

using namespace std::chrono_literals;

int main() {
    using minitrain::CommandProcessor;
    using minitrain::Direction;
    using minitrain::PidController;
    using minitrain::TelemetrySample;
    using minitrain::TrainController;

    auto motorWriter = [](float command) { std::cout << "Motor PWM command: " << command << '\n'; };
    auto telemetryPublisher = [](const TelemetrySample &sample) {
        std::cout << "Telemetry: speed=" << sample.speedMetersPerSecond << " m/s, battery=" << sample.batteryVoltage
                  << " V" << '\n';
    };

    TrainController controller(PidController{0.8F, 0.2F, 0.05F, 0.0F, 1.0F}, motorWriter, telemetryPublisher);

    CommandProcessor processor;
    processor.registerHandler("set_speed", [&controller](const auto &params) {
        auto it = params.find("value");
        if (it == params.end()) {
            return minitrain::CommandResult{false, "Missing value"};
        }
        controller.setTargetSpeed(std::stof(it->second));
        return minitrain::CommandResult{true, "Speed updated"};
    });
    processor.registerHandler("set_direction", [&controller](const auto &params) {
        auto it = params.find("value");
        if (it == params.end()) {
            return minitrain::CommandResult{false, "Missing value"};
        }
        controller.setDirection(it->second == "reverse" ? Direction::Reverse : Direction::Forward);
        return minitrain::CommandResult{true, "Direction updated"};
    });
    processor.registerHandler("headlights", [&controller](const auto &params) {
        auto it = params.find("value");
        if (it == params.end()) {
            return minitrain::CommandResult{false, "Missing value"};
        }
        controller.toggleHeadlights(it->second == "on");
        return minitrain::CommandResult{true, "Headlights toggled"};
    });
    processor.registerHandler("emergency", [&controller](const auto &params) {
        (void)params;
        controller.triggerEmergencyStop();
        return minitrain::CommandResult{true, "Emergency stop"};
    });

    std::cout << "Controller ready. Type commands like 'command=set_speed;value=1.5' or 'command=emergency'" << '\n';
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "quit") {
            break;
        }
        try {
            auto result = processor.process(line);
            std::cout << (result.success ? "OK: " : "ERR: ") << result.message << '\n';
        } catch (const std::exception &ex) {
            std::cout << "ERR: " << ex.what() << '\n';
        }

        controller.onSpeedMeasurement(controller.state().targetSpeed * 0.8F, 100ms);
        controller.onTelemetrySample(TelemetrySample{controller.state().targetSpeed, 0.5F, 11.1F, 30.0F});
    }

    return 0;
}
