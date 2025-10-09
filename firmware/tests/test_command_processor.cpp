#include "minitrain/command_processor.hpp"
#include "minitrain/pid_controller.hpp"
#include "minitrain/train_controller.hpp"

#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>

#include "test_suite.hpp"

namespace minitrain::tests {
namespace {

std::vector<std::uint8_t> encodeFloat(float value) {
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(float));
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    bits = ((bits & 0x000000FFU) << 24U) | ((bits & 0x0000FF00U) << 8U) | ((bits & 0x00FF0000U) >> 8U) |
           ((bits & 0xFF000000U) >> 24U);
#endif
    return {static_cast<std::uint8_t>(bits & 0xFFU), static_cast<std::uint8_t>((bits >> 8U) & 0xFFU),
            static_cast<std::uint8_t>((bits >> 16U) & 0xFFU), static_cast<std::uint8_t>((bits >> 24U) & 0xFFU)};
}

CommandFrame makeFrame(CommandKind kind, const std::vector<std::uint8_t> &payload) {
    CommandFrame frame;
    frame.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::Command);
    frame.payload.push_back(static_cast<std::uint8_t>(kind));
    frame.payload.insert(frame.payload.end(), payload.begin(), payload.end());
    return frame;
}

} // namespace

int runCommandProcessorTests() {
    int failures = 0;
    TrainController controller(
        PidController{0.5F, 0.0F, 0.0F, 0.0F, 1.0F},
        [](float) {},
        [](const TelemetrySample &) {});

    CommandProcessor processor(controller);
    const auto baseTime = std::chrono::steady_clock::now();

    {
        auto frame = makeFrame(CommandKind::SetSpeed, encodeFloat(2.5F));
        auto result = processor.processFrame(frame, baseTime);
        if (!result.success || controller.state().targetSpeed != 2.5F) {
            std::cerr << "SetSpeed command failed" << std::endl;
            ++failures;
        }
    }

    {
        std::vector<std::uint8_t> payload = {1};
        auto frame = makeFrame(CommandKind::SetDirection, payload);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(18));
        if (!result.success || controller.state().direction != Direction::Reverse) {
            std::cerr << "SetDirection command failed" << std::endl;
            ++failures;
        }
    }

    {
        std::vector<std::uint8_t> payload = {1};
        auto frame = makeFrame(CommandKind::ToggleHeadlights, payload);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(60));
        if (!result.success || controller.state().lightsOverrideMask != 0x01U ||
            controller.state().lightsSource != LightsSource::Override) {
            std::cerr << "ToggleHeadlights command should enable override mask" << std::endl;
            ++failures;
        }
        if (!processor.lowFrequencyFallbackActive()) {
            std::cerr << "Expected low frequency fallback" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(CommandKind::SetSpeed, encodeFloat(1.0F));
        frame.header.lightsOverride = 0x02U;
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(65));
        if (!result.success || controller.state().lightsOverrideMask != 0x02U) {
            std::cerr << "Header lights override mask should update controller state" << std::endl;
            ++failures;
        }
    }

    {
        std::vector<std::uint8_t> payload = {0};
        auto frame = makeFrame(CommandKind::ToggleHorn, payload);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(200));
        if (result.success || controller.state().horn) {
            std::cerr << "Expected horn command to fail due to rate" << std::endl;
            ++failures;
        }
    }

    {
        bool legacyCalled = false;
        CommandProcessor legacyProcessor(
            controller, [&legacyCalled](const std::string &text) {
                legacyCalled = true;
                return CommandResult{true, text};
            });
        CommandFrame frame;
        frame.header.payloadType = static_cast<std::uint16_t>(CommandPayloadType::LegacyText);
        frame.payload = {'o', 'l', 'd'};
        auto result = legacyProcessor.processFrame(frame, std::chrono::steady_clock::now());
        if (!result.success || !legacyCalled) {
            std::cerr << "Legacy parser should have been invoked" << std::endl;
            ++failures;
        }
    }

    {
        CommandFrame frame;
        frame.header.payloadType = 0x1234;
        auto result = processor.processFrame(frame, std::chrono::steady_clock::now());
        if (result.success) {
            std::cerr << "Unsupported payload type should fail" << std::endl;
            ++failures;
        }
    }

    return failures;
}

} // namespace minitrain::tests
