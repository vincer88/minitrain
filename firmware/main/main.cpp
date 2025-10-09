#include <chrono>
#include <cctype>
#include <iostream>
#include <sstream>
#include <thread>
#include <unordered_map>

#include "minitrain/command_channel.hpp"
#include "minitrain/command_processor.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

using namespace std::chrono_literals;

namespace {

std::unordered_map<std::string, std::string> parseKeyValuePairs(const std::string &commandText) {
    std::unordered_map<std::string, std::string> result;
    std::stringstream stream(commandText);
    std::string token;
    while (std::getline(stream, token, ';')) {
        if (token.empty()) {
            continue;
        }
        const auto delimiterPos = token.find('=');
        if (delimiterPos == std::string::npos) {
            continue;
        }
        auto key = token.substr(0, delimiterPos);
        auto value = token.substr(delimiterPos + 1);
        auto trim = [](std::string &str) {
            while (!str.empty() && std::isspace(static_cast<unsigned char>(str.front()))) {
                str.erase(str.begin());
            }
            while (!str.empty() && std::isspace(static_cast<unsigned char>(str.back()))) {
                str.pop_back();
            }
        };
        trim(key);
        trim(value);
        result[key] = value;
    }
    return result;
}

} // namespace

int main() {
    using minitrain::CommandFrame;
    using minitrain::CommandPayloadType;
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

    CommandProcessor processor(
        controller, [&controller](const std::string &commandText) -> minitrain::CommandResult {
            const auto pairs = parseKeyValuePairs(commandText);
            const auto commandIt = pairs.find("command");
            if (commandIt == pairs.end()) {
                return {false, "Missing command key"};
            }

            const auto &command = commandIt->second;
            if (command == "set_speed") {
                auto valueIt = pairs.find("value");
                if (valueIt == pairs.end()) {
                    return {false, "Missing value"};
                }
                controller.setTargetSpeed(std::stof(valueIt->second));
                return {true, "Speed updated"};
            }
            if (command == "set_direction") {
                auto valueIt = pairs.find("value");
                if (valueIt == pairs.end()) {
                    return {false, "Missing value"};
                }
                controller.setDirection(valueIt->second == "reverse" ? Direction::Reverse : Direction::Forward);
                return {true, "Direction updated"};
            }
            if (command == "headlights") {
                auto valueIt = pairs.find("value");
                if (valueIt == pairs.end()) {
                    return {false, "Missing value"};
                }
                controller.toggleHeadlights(valueIt->second == "on");
                return {true, "Headlights toggled"};
            }
            if (command == "emergency") {
                controller.triggerEmergencyStop();
                return {true, "Emergency stop"};
            }
            return {false, "Unknown command"};
        });

    std::cout << "Controller ready. Type commands like 'command=set_speed;value=1.5' or 'command=emergency'" << '\n';
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "quit") {
            break;
        }
        minitrain::CommandFrame frame;
        frame.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::LegacyText);
        frame.payload.assign(line.begin(), line.end());
        try {
            auto result = processor.processFrame(frame, std::chrono::steady_clock::now());
            std::cout << (result.success ? "OK: " : "ERR: ") << result.message << '\n';
        } catch (const std::exception &ex) {
            std::cout << "ERR: " << ex.what() << '\n';
        }

        controller.onSpeedMeasurement(controller.state().targetSpeed * 0.8F, 100ms);
        controller.onTelemetrySample(TelemetrySample{controller.state().targetSpeed, 0.5F, 11.1F, 30.0F});
    }

    return 0;
}
