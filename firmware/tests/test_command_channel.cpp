#include "minitrain/command_channel.hpp"
#include "minitrain/command_processor.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <queue>

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
    frame.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::Command);
    frame.payload.push_back(static_cast<std::uint8_t>(CommandKind::SetSpeed));
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(float));
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    bits = ((bits & 0x000000FFU) << 24U) | ((bits & 0x0000FF00U) << 8U) | ((bits & 0x00FF0000U) >> 8U) |
           ((bits & 0xFF000000U) >> 24U);
#endif
    frame.payload.push_back(static_cast<std::uint8_t>(bits & 0xFFU));
    frame.payload.push_back(static_cast<std::uint8_t>((bits >> 8U) & 0xFFU));
    frame.payload.push_back(static_cast<std::uint8_t>((bits >> 16U) & 0xFFU));
    frame.payload.push_back(static_cast<std::uint8_t>((bits >> 24U) & 0xFFU));
    return CommandChannel::encodeFrame(frame);
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

    TelemetrySample sample{3.0F, 0.4F, 11.1F, 35.0F};
    channel.publishTelemetry(sample, 42);
    if (clientPtr->sent.empty()) {
        std::cerr << "Telemetry frame should have been sent" << std::endl;
        ++failures;
    } else {
        auto frame = CommandChannel::decodeFrame(clientPtr->sent.back());
        if (frame.header.sequence != 42 || frame.header.payloadType != static_cast<std::uint16_t>(CommandPayloadType::Heartbeat)) {
            std::cerr << "Telemetry header invalid" << std::endl;
            ++failures;
        }
        if (frame.payload.size() != sizeof(float) * 4) {
            std::cerr << "Telemetry payload size mismatch" << std::endl;
            ++failures;
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
