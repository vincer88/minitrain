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

CommandFrame makeFrame(float speed, Direction direction, std::uint8_t lightsOverride,
                       std::uint8_t controlFlags = 0,
                       const std::vector<std::uint8_t> &aux = {}) {
    CommandFrame frame;
    frame.header.targetSpeedMetersPerSecond = speed;
    frame.header.direction = direction;
    frame.header.lightsOverride = lightsOverride;
    frame.payload.push_back(controlFlags);
    frame.payload.insert(frame.payload.end(), aux.begin(), aux.end());
    frame.header.auxPayloadLength = static_cast<std::uint16_t>(frame.payload.size());
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
        auto frame = makeFrame(2.5F, Direction::Neutral, 0x00U);
        auto result = processor.processFrame(frame, baseTime);
        if (!result.success || controller.state().targetSpeed != 2.5F) {
            std::cerr << "SetSpeed command failed" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(0.0F, Direction::Neutral, 0x00U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(18));
        if (!result.success || controller.state().direction != Direction::Neutral) {
            std::cerr << "SetDirection command failed to set neutral" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(0.0F, Direction::Forward, 0x00U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(30));
        if (!result.success || controller.state().direction != Direction::Forward) {
            std::cerr << "SetDirection command failed to set forward" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(0.0F, Direction::Reverse, 0x00U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(42));
        if (!result.success || controller.state().direction != Direction::Reverse) {
            std::cerr << "SetDirection command failed to set reverse" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(1.0F, Direction::Forward, 0x02U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(54));
        if (!result.success || controller.state().lightsOverrideMask != 0x02U) {
            std::cerr << "Header lights override mask should update controller state" << std::endl;
            ++failures;
        }
    }

    {
        auto frame = makeFrame(0.0F, Direction::Reverse, 0x00U, 0x01U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(120));
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
        auto frame = makeFrame(0.0F, Direction::Forward, 0x00U, 0x02U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(130));
        if (!result.success || !controller.state().horn) {
            std::cerr << "Horn command should enable horn" << std::endl;
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
        auto frame = makeFrame(0.0F, Direction::Forward, 0x00U, 0x00U, {'o', 'l', 'd'});
        auto result = legacyProcessor.processFrame(frame, std::chrono::steady_clock::now());
        if (!result.success || !legacyCalled) {
            std::cerr << "Legacy parser should have been invoked" << std::endl;
            ++failures;
        }
    }

    {
        auto baseline = controller.state();
        auto frame = makeFrame(0.0F, Direction::Forward, 0x80U);
        auto result = processor.processFrame(frame, baseTime + std::chrono::milliseconds(260));
        auto after = controller.state();
        if (!result.success || result.message != "Telemetry frame") {
            std::cerr << "Telemetry frame should short-circuit" << std::endl;
            ++failures;
        }
        if (after.targetSpeed != baseline.targetSpeed || after.direction != baseline.direction) {
            std::cerr << "Telemetry frame should not modify state" << std::endl;
            ++failures;
        }
        if (!after.lightsTelemetryOnly) {
            std::cerr << "Telemetry flag should set telemetry-only state" << std::endl;
            ++failures;
        }
    }

    {
        auto deterministicNow = std::chrono::steady_clock::now();
        auto clock = [&deterministicNow]() { return deterministicNow; };
        TrainController deterministicController(
            PidController{0.5F, 0.0F, 0.0F, 0.0F, 1.0F},
            [](float) {},
            [](const TelemetrySample &) {},
            std::chrono::milliseconds{MINITRAIN_FAILSAFE_THRESHOLD_MS},
            std::chrono::milliseconds{MINITRAIN_PILOT_RELEASE_MS},
            std::chrono::milliseconds{MINITRAIN_FAILSAFE_RAMP_MS},
            clock);
        CommandProcessor deterministicProcessor(deterministicController);

        auto frame = makeFrame(0.0F, Direction::Neutral, 0x00U);
        const auto remoteSystem = std::chrono::system_clock::now() - std::chrono::milliseconds(5);
        frame.header.timestampMicros = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(remoteSystem.time_since_epoch()).count());
        auto arrival = deterministicNow;
        auto result = deterministicProcessor.processFrame(frame, arrival);
        if (!result.success) {
            std::cerr << "Deterministic controller should accept timestamped frame" << std::endl;
            ++failures;
        }

        const auto lastTimestamp = deterministicController.state().realtime.lastCommandTimestamp;
        if (lastTimestamp > arrival) {
            std::cerr << "Remote timestamp should not land in the future" << std::endl;
            ++failures;
        }

        auto staleFrame = makeFrame(0.0F, Direction::Neutral, 0x00U);
        const auto staleRemoteSystem = remoteSystem - std::chrono::seconds(1);
        staleFrame.header.timestampMicros = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(staleRemoteSystem.time_since_epoch()).count());
        deterministicNow += std::chrono::milliseconds(10);
        auto staleResult = deterministicProcessor.processFrame(staleFrame, deterministicNow);
        if (!staleResult.success) {
            std::cerr << "Stale frame should still be processed" << std::endl;
            ++failures;
        }

        const auto staleThreshold = std::chrono::milliseconds{MINITRAIN_FAILSAFE_THRESHOLD_MS};
        deterministicNow += staleThreshold + std::chrono::milliseconds(10);
        deterministicController.onSpeedMeasurement(0.0F, std::chrono::milliseconds(10));
        if (!deterministicController.state().failSafeActive) {
            std::cerr << "Stale timestamp should trigger fail-safe" << std::endl;
            ++failures;
        }
    }

    return failures;
}

} // namespace minitrain::tests
