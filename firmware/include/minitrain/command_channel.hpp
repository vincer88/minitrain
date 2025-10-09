#pragma once

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace minitrain {

class CommandProcessor;
struct TelemetrySample;

struct CommandFrameHeader {
    std::array<std::uint8_t, 16> sessionId{};
    std::uint32_t sequence{0};
    std::uint64_t timestampNanoseconds{0};
    std::uint16_t payloadType{0};
    std::uint16_t payloadSize{0};
    std::uint8_t lightsOverride{0};
    std::uint8_t lightsFlags{0};
};

enum class CommandPayloadType : std::uint16_t {
    Command = 0x0001,
    LegacyText = 0x00FE,
    Heartbeat = 0x00FF,
};

struct CommandFrame {
    CommandFrameHeader header;
    std::vector<std::uint8_t> payload;
};

class WebSocketClient {
  public:
    virtual ~WebSocketClient() = default;

    virtual void connect(const std::string &uri) = 0;
    virtual void close() = 0;

    virtual void sendBinary(const std::vector<std::uint8_t> &data) = 0;
    virtual std::optional<std::vector<std::uint8_t>> receiveBinary(std::chrono::milliseconds timeout) = 0;
};

class CommandChannel {
  public:
    struct Config {
        std::string uri;
        std::array<std::uint8_t, 16> sessionId{};
        std::chrono::milliseconds receiveTimeout{50};
    };

    CommandChannel(Config config, std::unique_ptr<WebSocketClient> client, CommandProcessor &processor);
    ~CommandChannel();

    CommandChannel(const CommandChannel &) = delete;
    CommandChannel &operator=(const CommandChannel &) = delete;
    CommandChannel(CommandChannel &&) = delete;
    CommandChannel &operator=(CommandChannel &&) = delete;

    void start();
    void stop();

    void publishTelemetry(const TelemetrySample &sample, std::uint32_t sequence);
    void poll();

    static std::vector<std::uint8_t> encodeFrame(const CommandFrame &frame);
    static CommandFrame decodeFrame(const std::vector<std::uint8_t> &buffer);

  private:
    Config config_;
    std::unique_ptr<WebSocketClient> client_;
    CommandProcessor &processor_;
    bool running_{false};
};

std::array<std::uint8_t, 16> serializeUuidLittleEndian(const std::array<std::uint8_t, 16> &uuid);

} // namespace minitrain
