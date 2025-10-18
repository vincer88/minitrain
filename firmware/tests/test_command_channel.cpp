#include "minitrain/command_channel.hpp"
#include "minitrain/command_processor.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <queue>
#include <stdexcept>

#include "test_suite.hpp"

namespace minitrain::tests {
namespace {

class FakeWebSocketClient : public WebSocketClient {
  public:
    void connect(const std::string &uri) override {
        connected = true;
        lastUri = uri;
    }

    void close() override { connected = false; }

    void sendBinary(const std::vector<std::uint8_t> &data) override { sent.emplace_back(data); }

    std::optional<std::vector<std::uint8_t>> receiveBinary(std::chrono::milliseconds) override {
        if (incoming.empty()) {
            return std::nullopt;
        }
        auto data = incoming.front();
        incoming.pop();
        return data;
    }

    void queueIncoming(const std::vector<std::uint8_t> &data) { incoming.push(data); }

    bool connected{false};
    std::string lastUri;
    std::vector<std::vector<std::uint8_t>> sent;
    std::queue<std::vector<std::uint8_t>> incoming;
};

std::vector<std::uint8_t> buildSpeedPayload(float value) {
    CommandFrame frame;
    frame.header.targetSpeedMetersPerSecond = value;
    frame.header.direction = Direction::Forward;
    frame.header.lightsOverride = 0x00U;
    frame.payload.push_back(0x00U);
    frame.header.auxPayloadLength = static_cast<std::uint16_t>(frame.payload.size());
    return CommandChannel::encodeFrame(frame);
}

std::vector<std::uint8_t> readFixture(const std::string &relative) {
    const auto base = std::filesystem::path(__FILE__).parent_path() / ".." / ".." / relative;
    std::ifstream file(base, std::ios::binary);
    if (!file) {
        throw std::runtime_error("Unable to open fixture: " + base.string());
    }
    return std::vector<std::uint8_t>(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
}

} // namespace

int runCommandChannelTests() {
    int failures = 0;

    auto fakeClient = std::make_unique<FakeWebSocketClient>();
    auto *clientPtr = fakeClient.get();

    TrainController controller(
        PidController{0.8F, 0.1F, 0.0F, 0.0F, 1.0F}, [](float) {}, [](const TelemetrySample &) {});
    CommandProcessor processor(controller);

    CommandChannel::Config config;
    config.uri = "wss://example.com/socket";
    config.sessionId = {0};
    config.receiveTimeout = std::chrono::milliseconds(5);

    CommandChannel channel(config, std::move(fakeClient), processor);
    channel.start();

    if (!clientPtr->connected || clientPtr->lastUri != config.uri) {
        std::cerr << "WebSocket should have connected" << std::endl;
        ++failures;
    }

    clientPtr->queueIncoming(buildSpeedPayload(3.0F));
    channel.poll();
    if (controller.state().targetSpeed != 3.0F) {
        std::cerr << "Command frame should update speed" << std::endl;
        ++failures;
    }

    TelemetrySample sample{};
    sample.speedMetersPerSecond = 3.0F;
    sample.motorCurrentAmps = 0.4F;
    sample.batteryVoltage = 11.1F;
    sample.temperatureCelsius = 35.0F;
    sample.failSafeActive = true;
    sample.failSafeProgress = 0.5F;
    sample.failSafeElapsedMillis = 450U;
    sample.lightsState = LightsState::FrontWhiteRearRed;
    sample.lightsSource = LightsSource::FailSafe;
    sample.activeCab = ActiveCab::Front;
    sample.lightsOverrideMask = 0x03U;
    sample.lightsTelemetryOnly = false;
    sample.appliedSpeedMetersPerSecond = 2.8F;
    sample.appliedDirection = Direction::Forward;
    sample.sequence = 99U;
    sample.commandTimestamp = 123456789ULL;
    sample.source = TelemetrySource::Instantaneous;
    sample.sessionId = {0x10U, 0x32U, 0x54U, 0x76U, 0x98U, 0xBAU, 0xDCU, 0xFEU, 0x10U, 0x32U, 0x54U, 0x76U, 0x98U, 0xBAU,
                       0xDCU, 0xFEU};
    channel.publishTelemetry(sample, 42);
    if (clientPtr->sent.empty()) {
        std::cerr << "Telemetry frame should have been sent" << std::endl;
        ++failures;
    } else {
        auto frame = CommandChannel::decodeFrame(clientPtr->sent.back());
        if (frame.header.sequence != sample.sequence) {
            std::cerr << "Telemetry header invalid" << std::endl;
            ++failures;
        }
        if (frame.header.timestampMicros != sample.commandTimestamp) {
            std::cerr << "Telemetry header should mirror command timestamp" << std::endl;
            ++failures;
        }
        if (frame.header.sessionId != sample.sessionId) {
            std::cerr << "Telemetry header should mirror session id" << std::endl;
            ++failures;
        }
        const std::uint8_t expectedLights = static_cast<std::uint8_t>((sample.lightsOverrideMask & 0x7FU) | 0x80U);
        if (frame.header.lightsOverride != expectedLights) {
            std::cerr << "Telemetry header should mirror override mask and telemetry flag" << std::endl;
            ++failures;
        }
        if (frame.header.targetSpeedMetersPerSecond != sample.appliedSpeedMetersPerSecond) {
            std::cerr << "Telemetry header should include applied speed" << std::endl;
            ++failures;
        }
        if (frame.header.direction != sample.appliedDirection) {
            std::cerr << "Telemetry header should include applied direction" << std::endl;
            ++failures;
        }
        if (frame.payload.size() != 36U) {
            std::cerr << "Telemetry payload size mismatch" << std::endl;
            ++failures;
        } else {
            try {
                const auto expectedPayload = readFixture("fixtures/telemetry/sample_payload.bin");
                if (frame.payload != expectedPayload) {
                    std::cerr << "Telemetry payload did not match fixture" << std::endl;
                    ++failures;
                }
            } catch (const std::exception &ex) {
                std::cerr << ex.what() << std::endl;
                ++failures;
            }
        }
    }

    channel.stop();
    if (clientPtr->connected) {
        std::cerr << "Channel should be stopped" << std::endl;
        ++failures;
    }

    return failures;
}

} // namespace minitrain::tests
