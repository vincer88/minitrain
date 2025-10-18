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

std::uint8_t encodeDirection(Direction direction) {
    switch (direction) {
    case Direction::Neutral:
        return 0U;
    case Direction::Forward:
        return 1U;
    case Direction::Reverse:
        return 2U;
    }
    return 0U;
}

Direction decodeDirection(std::uint8_t code) {
    switch (code) {
    case 0U:
        return Direction::Neutral;
    case 1U:
        return Direction::Forward;
    case 2U:
        return Direction::Reverse;
    default:
        return Direction::Neutral;
    }
}

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
    const auto timestampFallback = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::system_clock::now().time_since_epoch()).count());
    frame.header.timestampMicros = sample.commandTimestamp != 0 ? sample.commandTimestamp : timestampFallback;
    frame.header.targetSpeedMetersPerSecond = sample.appliedSpeedMetersPerSecond;
    frame.header.direction = sample.appliedDirection;
    const std::uint8_t telemetryFlag = 0x80U;
    frame.header.lightsOverride = static_cast<std::uint8_t>((sample.lightsOverrideMask & 0x7FU) | telemetryFlag);
    frame.payload.resize(sizeof(float) * 6 + sizeof(std::uint32_t) + 8);

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
    encodeFloat(sample.appliedSpeedMetersPerSecond, payload + 4 * sizeof(float));
    encodeFloat(sample.failSafeProgress, payload + 5 * sizeof(float));

    std::uint8_t *byteOut = payload + 6 * sizeof(float);
    const std::uint32_t failSafeElapsed = hostToLittle32(sample.failSafeElapsedMillis);
    std::memcpy(byteOut, &failSafeElapsed, sizeof(failSafeElapsed));
    byteOut += sizeof(failSafeElapsed);

    std::uint8_t flags = 0U;
    if (sample.failSafeActive) {
        flags |= 0x01U;
    }
    if (sample.lightsTelemetryOnly) {
        flags |= 0x02U;
    }
    *byteOut++ = flags;
    *byteOut++ = static_cast<std::uint8_t>(sample.activeCab);
    *byteOut++ = static_cast<std::uint8_t>(sample.lightsState);
    *byteOut++ = static_cast<std::uint8_t>(sample.lightsSource);
    *byteOut++ = sample.lightsOverrideMask;
    *byteOut++ = static_cast<std::uint8_t>(sample.source);
    *byteOut++ = encodeDirection(sample.appliedDirection);
    *byteOut++ = 0U;

    frame.header.auxPayloadLength = static_cast<std::uint16_t>(frame.payload.size());
    client_->sendBinary(encodeFrame(frame));
}

std::vector<std::uint8_t> CommandChannel::encodeFrame(const CommandFrame &frame) {
    const std::size_t totalSize = kCommandFrameHeaderSize + frame.payload.size();
    std::vector<std::uint8_t> buffer(totalSize);

    std::uint8_t *out = buffer.data();
    std::memcpy(out, frame.header.sessionId.data(), frame.header.sessionId.size());
    out += frame.header.sessionId.size();

    const std::uint32_t sequence = hostToLittle32(frame.header.sequence);
    std::memcpy(out, &sequence, sizeof(sequence));
    out += sizeof(sequence);

    const std::uint64_t timestamp = hostToLittle64(frame.header.timestampMicros);
    std::memcpy(out, &timestamp, sizeof(timestamp));
    out += sizeof(timestamp);

    static_assert(sizeof(float) == sizeof(std::uint32_t), "Unexpected float size");
    std::uint32_t speedBits;
    std::memcpy(&speedBits, &frame.header.targetSpeedMetersPerSecond, sizeof(float));
    speedBits = hostToLittle32(speedBits);
    std::memcpy(out, &speedBits, sizeof(speedBits));
    out += sizeof(speedBits);

    const std::uint8_t direction = encodeDirection(frame.header.direction);
    *out++ = direction;

    *out++ = frame.header.lightsOverride;

    const std::uint16_t auxLength = hostToLittle16(static_cast<std::uint16_t>(frame.payload.size()));
    std::memcpy(out, &auxLength, sizeof(auxLength));
    out += sizeof(auxLength);

    if (!frame.payload.empty()) {
        std::memcpy(out, frame.payload.data(), frame.payload.size());
    }
    return buffer;
}

CommandFrame CommandChannel::decodeFrame(const std::vector<std::uint8_t> &buffer) {
    if (buffer.size() < kCommandFrameHeaderSize) {
        throw std::invalid_argument("Buffer too small for command frame");
    }
    CommandFrame frame{};
    const std::uint8_t *in = buffer.data();
    std::memcpy(frame.header.sessionId.data(), in, frame.header.sessionId.size());
    in += frame.header.sessionId.size();

    std::uint32_t sequence;
    std::memcpy(&sequence, in, sizeof(sequence));
    frame.header.sequence = littleToHost32(sequence);
    in += sizeof(sequence);

    std::uint64_t timestamp;
    std::memcpy(&timestamp, in, sizeof(timestamp));
    frame.header.timestampMicros = littleToHost64(timestamp);
    in += sizeof(timestamp);

    std::uint32_t speedBits;
    std::memcpy(&speedBits, in, sizeof(speedBits));
    speedBits = littleToHost32(speedBits);
    std::memcpy(&frame.header.targetSpeedMetersPerSecond, &speedBits, sizeof(speedBits));
    in += sizeof(speedBits);

    const std::uint8_t directionCode = *in++;
    frame.header.direction = decodeDirection(directionCode);

    frame.header.lightsOverride = *in++;

    std::uint16_t auxLength;
    std::memcpy(&auxLength, in, sizeof(auxLength));
    frame.header.auxPayloadLength = littleToHost16(auxLength);
    in += sizeof(auxLength);

    const std::size_t expectedSize = kCommandFrameHeaderSize + frame.header.auxPayloadLength;
    if (buffer.size() < expectedSize) {
        throw std::invalid_argument("Incomplete payload");
    }

    frame.payload.resize(frame.header.auxPayloadLength);
    if (frame.header.auxPayloadLength > 0) {
        std::memcpy(frame.payload.data(), in, frame.payload.size());
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
