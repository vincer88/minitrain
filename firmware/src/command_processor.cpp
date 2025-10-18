#include "minitrain/command_processor.hpp"

#include "minitrain/train_controller.hpp"

#include <cstring>
#include <stdexcept>

namespace minitrain {
CommandProcessor::CommandProcessor(TrainController &controller, std::optional<LegacyParser> legacyParser)
    : controller_(controller), legacyParser_(std::move(legacyParser)) {}

CommandResult CommandProcessor::processFrame(const CommandFrame &frame, std::chrono::steady_clock::time_point arrival) {
    const bool telemetryOnly = (frame.header.lightsOverride & 0x80U) != 0;
    const std::uint8_t lightsMask = static_cast<std::uint8_t>(frame.header.lightsOverride & 0x7FU);
    controller_.setLightsOverride(lightsMask, telemetryOnly);

    if (telemetryOnly) {
        return {true, "Telemetry frame"};
    }

    const auto arrivalSystem = std::chrono::system_clock::now();

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

    auto remoteTimestamp = arrival;
    if (frame.header.timestampMicros != 0) {
        // The frame timestamp is expressed in microseconds since the Unix epoch on the sender's
        // system clock. Convert it to a local system_clock reference so we can normalize it onto
        // our steady clock timeline before registering it with the controller.
        const auto remoteSystem = std::chrono::system_clock::time_point{
            std::chrono::duration_cast<std::chrono::system_clock::duration>(
                std::chrono::microseconds{frame.header.timestampMicros})};
        auto commandAgeSystem = arrivalSystem - remoteSystem;
        if (commandAgeSystem < std::chrono::system_clock::duration::zero()) {
            commandAgeSystem = std::chrono::system_clock::duration::zero();
        }
        const auto commandAgeSteady = std::chrono::duration_cast<std::chrono::steady_clock::duration>(commandAgeSystem);
        remoteTimestamp = arrival - commandAgeSteady;
    }

    controller_.setTargetSpeed(frame.header.targetSpeedMetersPerSecond);
    controller_.setDirection(frame.header.direction);

    std::uint8_t controlFlags = frame.payload.empty() ? 0 : frame.payload.front();
    if (lightsMask == 0x00U) {
        controller_.toggleHeadlights((controlFlags & 0x01U) != 0);
    }
    controller_.toggleHorn((controlFlags & 0x02U) != 0);
    const bool emergency = (controlFlags & 0x04U) != 0;
    if (emergency) {
        controller_.triggerEmergencyStop();
    }

    controller_.registerCommandTimestamp(remoteTimestamp);

    if (!emergency && frame.payload.size() > 1 && legacyParser_) {
        std::vector<std::uint8_t> legacyPayload(frame.payload.begin() + 1, frame.payload.end());
        auto legacyResult = handleLegacyPayload(legacyPayload);
        if (!legacyResult.success) {
            return legacyResult;
        }
        if (!legacyResult.message.empty()) {
            return legacyResult;
        }
    }

    if (emergency) {
        return {true, "Emergency stop"};
    }

    return {true, "State updated"};
}

bool CommandProcessor::lowFrequencyFallbackActive() const { return lowFrequencyFallback_; }

CommandResult CommandProcessor::handleLegacyPayload(const std::vector<std::uint8_t> &payload) {
    if (!legacyParser_) {
        return {false, "Legacy parser disabled"};
    }
    std::string text(payload.begin(), payload.end());
    return (*legacyParser_)(text);
}

} // namespace minitrain
