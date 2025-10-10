#include "minitrain/command_channel.hpp"

#include "minitrain/command_processor.hpp"
#include "minitrain/telemetry.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <stdexcept>

namespace minitrain {
namespace {

#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
constexpr bool kIsLittleEndian = true;
#else
constexpr bool kIsLittleEndian = false;
#endif

std::uint16_t swap16(std::uint16_t value) {
#if defined(__has_builtin)
#  if __has_builtin(__builtin_bswap16)
    return __builtin_bswap16(value);
#  endif
#endif
    return static_cast<std::uint16_t>(((value & 0x00FFU) << 8U) | ((value & 0xFF00U) >> 8U));
}

std::uint32_t swap32(std::uint32_t value) {
#if defined(__has_builtin)
#  if __has_builtin(__builtin_bswap32)
    return __builtin_bswap32(value);
#  endif
#endif
    return ((value & 0x000000FFU) << 24U) | ((value & 0x0000FF00U) << 8U) | ((value & 0x00FF0000U) >> 8U) |
           ((value & 0xFF000000U) >> 24U);
}

std::uint64_t swap64(std::uint64_t value) {
#if defined(__has_builtin)
#  if __has_builtin(__builtin_bswap64)
    return __builtin_bswap64(value);
#  endif
#endif
    std::uint64_t result = 0;
    for (int i = 0; i < 8; ++i) {
        result |= ((value >> (i * 8)) & 0xFFULL) << (56 - i * 8);
    }
    return result;
}

std::uint16_t hostToLittle16(std::uint16_t value) { return kIsLittleEndian ? value : swap16(value); }

std::uint32_t hostToLittle32(std::uint32_t value) { return kIsLittleEndian ? value : swap32(value); }

std::uint64_t hostToLittle64(std::uint64_t value) { return kIsLittleEndian ? value : swap64(value); }

std::uint16_t littleToHost16(std::uint16_t value) { return hostToLittle16(value); }

std::uint32_t littleToHost32(std::uint32_t value) { return hostToLittle32(value); }

std::uint64_t littleToHost64(std::uint64_t value) { return hostToLittle64(value); }

} // namespace

CommandChannel::CommandChannel(Config config, std::unique_ptr<WebSocketClient> client, CommandProcessor &processor)
    : config_(std::move(config)), client_(std::move(client)), processor_(processor) {}

CommandChannel::~CommandChannel() { stop(); }

void CommandChannel::start() {
    if (running_) {
        return;
    }
    client_->connect(config_.uri);
    running_ = true;
}

void CommandChannel::stop() {
    if (!running_) {
        return;
    }
    client_->close();
    running_ = false;
}

void CommandChannel::publishTelemetry(const TelemetrySample &sample, std::uint32_t sequence) {
    if (!running_) {
        return;
    }

    CommandFrame frame;
    frame.header.sessionId = sample.sessionId == std::array<std::uint8_t, 16>{}
                                 ? config_.sessionId
                                 : sample.sessionId;
    frame.header.sequence = sample.sequence != 0 ? sample.sequence : sequence;
    const auto timestampFallback = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                              std::chrono::steady_clock::now().time_since_epoch())
                                              .count());
    frame.header.timestampNanoseconds = sample.commandTimestamp != 0 ? sample.commandTimestamp : timestampFallback;
    frame.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::Heartbeat);
    frame.header.lightsOverride = sample.lightsOverrideMask;
    frame.header.lightsFlags = sample.lightsTelemetryOnly ? 0x80U : 0x00U;
    frame.payload.resize(sizeof(float) * 12);

    auto encodeFloat = [](float value, std::uint8_t *out) {
        std::uint32_t bits;
        std::memcpy(&bits, &value, sizeof(float));
        bits = hostToLittle32(bits);
        std::memcpy(out, &bits, sizeof(float));
    };

    auto *payload = frame.payload.data();
    encodeFloat(sample.speedMetersPerSecond, payload);
    encodeFloat(sample.motorCurrentAmps, payload + sizeof(float));
    encodeFloat(sample.batteryVoltage, payload + 2 * sizeof(float));
    encodeFloat(sample.temperatureCelsius, payload + 3 * sizeof(float));
    encodeFloat(sample.failSafeActive ? 1.0F : 0.0F, payload + 4 * sizeof(float));
    encodeFloat(static_cast<float>(sample.lightsState), payload + 5 * sizeof(float));
    encodeFloat(static_cast<float>(sample.lightsSource), payload + 6 * sizeof(float));
    encodeFloat(static_cast<float>(sample.activeCab), payload + 7 * sizeof(float));
    encodeFloat(static_cast<float>(sample.lightsOverrideMask), payload + 8 * sizeof(float));
    encodeFloat(sample.appliedSpeedMetersPerSecond, payload + 9 * sizeof(float));
    encodeFloat(static_cast<float>(sample.appliedDirection), payload + 10 * sizeof(float));
    encodeFloat(static_cast<float>(sample.source), payload + 11 * sizeof(float));

    client_->sendBinary(encodeFrame(frame));
}

std::vector<std::uint8_t> CommandChannel::encodeFrame(const CommandFrame &frame) {
    const std::size_t totalSize = sizeof(CommandFrameHeader) + frame.payload.size();
    std::vector<std::uint8_t> buffer(totalSize);

    CommandFrameHeader header = frame.header;
    header.sequence = hostToLittle32(header.sequence);
    header.timestampNanoseconds = hostToLittle64(header.timestampNanoseconds);
    header.payloadType = hostToLittle16(header.payloadType);
    header.payloadSize = hostToLittle16(static_cast<std::uint16_t>(frame.payload.size()));

    std::memcpy(buffer.data(), &header, sizeof(CommandFrameHeader));
    if (!frame.payload.empty()) {
        std::memcpy(buffer.data() + sizeof(CommandFrameHeader), frame.payload.data(), frame.payload.size());
    }
    return buffer;
}

CommandFrame CommandChannel::decodeFrame(const std::vector<std::uint8_t> &buffer) {
    if (buffer.size() < sizeof(CommandFrameHeader)) {
        throw std::invalid_argument("Buffer too small for command frame");
    }
    CommandFrame frame{};
    std::memcpy(&frame.header, buffer.data(), sizeof(CommandFrameHeader));
    frame.header.sequence = littleToHost32(frame.header.sequence);
    frame.header.timestampNanoseconds = littleToHost64(frame.header.timestampNanoseconds);
    frame.header.payloadType = littleToHost16(frame.header.payloadType);
    frame.header.payloadSize = littleToHost16(frame.header.payloadSize);

    const std::size_t expectedSize = sizeof(CommandFrameHeader) + frame.header.payloadSize;
    if (buffer.size() < expectedSize) {
        throw std::invalid_argument("Incomplete payload");
    }

    frame.payload.resize(frame.header.payloadSize);
    if (frame.header.payloadSize > 0) {
        std::memcpy(frame.payload.data(), buffer.data() + sizeof(CommandFrameHeader), frame.payload.size());
    }

    return frame;
}

std::array<std::uint8_t, 16> serializeUuidLittleEndian(const std::array<std::uint8_t, 16> &uuid) {
    std::array<std::uint8_t, 16> result{};
    std::copy(uuid.rbegin(), uuid.rend(), result.begin());
    return result;
}

void CommandChannel::poll() {
    if (!running_) {
        return;
    }
    auto data = client_->receiveBinary(config_.receiveTimeout);
    if (!data || data->empty()) {
        return;
    }
    const auto frame = decodeFrame(*data);
    (void)processor_.processFrame(frame, std::chrono::steady_clock::now());
}

} // namespace minitrain
