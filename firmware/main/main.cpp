#include <chrono>
#include <cctype>
#include <iostream>
#include <memory>
#include <sstream>
#include <thread>
#include <unordered_map>

#include "minitrain/command_channel.hpp"
#include "minitrain/command_processor.hpp"
#include "minitrain/secure_websocket_client.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

#include "tls_credentials.hpp"

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

    std::unique_ptr<minitrain::SecureWebSocketClient> websocket;
    try {
        websocket = std::make_unique<minitrain::SecureWebSocketClient>(minitrain::config::loadTlsCredentialConfig());
    } catch (const std::exception &ex) {
        std::cout << "WARN: secure WebSocket disabled - " << ex.what() << '\n';
    }

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

    if (websocket) {
        websocket->setOnConnected([]() { std::cout << "Secure command channel connected" << '\n'; });
        websocket->setOnDisconnected([]() { std::cout << "Secure command channel disconnected" << '\n'; });
        websocket->setMessageHandler([&processor](const std::string &payload) {
            minitrain::CommandFrame inbound;
            inbound.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::LegacyText);
            inbound.payload.assign(payload.begin(), payload.end());
            try {
                auto now = std::chrono::steady_clock::now();
                auto result = processor.processFrame(inbound, now);
                std::cout << (result.success ? "OK: " : "ERR: ") << result.message << " (secure)" << '\n';
            } catch (const std::exception &ex) {
                std::cout << "ERR: failed to process secure command: " << ex.what() << '\n';
            }
        });
        if (!websocket->connect()) {
            std::cout << "ERR: unable to open secure WebSocket session" << '\n';
        }
    }

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
        auto currentState = controller.state();
        TelemetrySample telemetry{};
        telemetry.speedMetersPerSecond = currentState.targetSpeed;
        telemetry.motorCurrentAmps = 0.5F;
        telemetry.batteryVoltage = 11.1F;
        telemetry.temperatureCelsius = 30.0F;
        telemetry.failSafeActive = currentState.failSafeActive;
        telemetry.lightsState = currentState.lightsState;
        telemetry.lightsSource = currentState.lightsSource;
        telemetry.activeCab = currentState.activeCab;
        telemetry.lightsOverrideMask = currentState.lightsOverrideMask;
        telemetry.lightsTelemetryOnly = currentState.lightsTelemetryOnly;
        telemetry.commandTimestamp = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                                   std::chrono::steady_clock::now().time_since_epoch())
                                                   .count());
        telemetry.sequence = 0;
        controller.onTelemetrySample(telemetry);

        if (websocket && websocket->isConnected()) {
            std::ostringstream serializedTelemetry;
            serializedTelemetry << "speed=" << telemetry.speedMetersPerSecond << ";battery=" << telemetry.batteryVoltage
                                << ";temperature=" << telemetry.temperatureCelsius;
            websocket->sendText(serializedTelemetry.str());
        }
    }

    return 0;
}
