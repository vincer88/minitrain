#include "minitrain/command_processor.hpp"

#include "minitrain/train_controller.hpp"

#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace minitrain {
namespace {

float readFloatLE(const std::vector<std::uint8_t> &payload) {
    if (payload.size() < sizeof(float)) {
        throw std::invalid_argument("Payload too small for float");
    }
    float value;
    std::memcpy(&value, payload.data(), sizeof(float));
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    auto *bytes = reinterpret_cast<std::uint8_t *>(&value);
    std::reverse(bytes, bytes + sizeof(float));
#endif
    return value;
}

} // namespace

CommandProcessor::CommandProcessor(TrainController &controller, std::optional<LegacyParser> legacyParser)
    : controller_(controller), legacyParser_(std::move(legacyParser)) {}

CommandResult CommandProcessor::processFrame(const CommandFrame &frame, std::chrono::steady_clock::time_point arrival) {
    if (frame.header.payloadType != static_cast<std::uint16_t>(CommandPayloadType::Command) &&
        frame.header.payloadType != static_cast<std::uint16_t>(CommandPayloadType::LegacyText)) {
        return {false, "Unsupported payload type"};
    }

    if (lastArrival_) {
        const auto delta = arrival - *lastArrival_;
        if (delta <= std::chrono::milliseconds(30)) {
            lowFrequencyFallback_ = false;
        } else if (delta <= std::chrono::milliseconds(120)) {
            lowFrequencyFallback_ = true;
        } else {
            return {false, "Frame rate below 10Hz"};
        }
    }
    lastArrival_ = arrival;

    if (frame.header.payloadType == static_cast<std::uint16_t>(CommandPayloadType::LegacyText)) {
        return handleLegacyPayload(frame.payload);
    }

    const auto payload = parsePayload(frame.payload);
    return handlePayload(payload);
}

bool CommandProcessor::lowFrequencyFallbackActive() const { return lowFrequencyFallback_; }

CommandResult CommandProcessor::handlePayload(const BinaryCommandPayload &payload) {
    switch (payload.kind) {
    case CommandKind::SetSpeed: {
        float speed = readFloatLE(payload.data);
        controller_.setTargetSpeed(speed);
        return {true, "Speed updated"};
    }
    case CommandKind::SetDirection: {
        if (payload.data.empty()) {
            return {false, "Missing direction payload"};
        }
        controller_.setDirection(payload.data.front() == 0 ? Direction::Forward : Direction::Reverse);
        return {true, "Direction updated"};
    }
    case CommandKind::ToggleHeadlights: {
        if (payload.data.empty()) {
            return {false, "Missing headlight payload"};
        }
        controller_.toggleHeadlights(payload.data.front() != 0);
        return {true, "Headlights toggled"};
    }
    case CommandKind::ToggleHorn: {
        if (payload.data.empty()) {
            return {false, "Missing horn payload"};
        }
        controller_.toggleHorn(payload.data.front() != 0);
        return {true, "Horn toggled"};
    }
    case CommandKind::EmergencyStop: {
        controller_.triggerEmergencyStop();
        return {true, "Emergency stop"};
    }
    case CommandKind::Legacy:
        return handleLegacyPayload(payload.data);
    default:
        return {false, "Unknown command kind"};
    }
}

CommandResult CommandProcessor::handleLegacyPayload(const std::vector<std::uint8_t> &payload) {
    if (!legacyParser_) {
        return {false, "Legacy parser disabled"};
    }
    std::string text(payload.begin(), payload.end());
    return (*legacyParser_)(text);
}

BinaryCommandPayload CommandProcessor::parsePayload(const std::vector<std::uint8_t> &payload) const {
    if (payload.empty()) {
        throw std::invalid_argument("Empty payload");
    }
    BinaryCommandPayload result;
    result.kind = static_cast<CommandKind>(payload.front());
    result.data.assign(payload.begin() + 1, payload.end());
    return result;
}

} // namespace minitrain
