#include "minitrain/telemetry.hpp"

#include <iostream>

#include "test_suite.hpp"

namespace minitrain::tests {

int runTelemetryTests() {
    TelemetryAggregator aggregator{3};

    auto makeSample = [](float speed, float current, float voltage, float temperature, bool failSafe,
                         LightsState lightsState, LightsSource lightsSource, ActiveCab cab, std::uint8_t overrideMask,
                         bool telemetryOnly, std::uint32_t sequence, std::uint64_t timestamp,
                         Direction appliedDirection) {
        TelemetrySample sample{};
        sample.speedMetersPerSecond = speed;
        sample.motorCurrentAmps = current;
        sample.batteryVoltage = voltage;
        sample.temperatureCelsius = temperature;
        sample.failSafeActive = failSafe;
        sample.lightsState = lightsState;
        sample.lightsSource = lightsSource;
        sample.activeCab = cab;
        sample.lightsOverrideMask = overrideMask;
        sample.lightsTelemetryOnly = telemetryOnly;
        sample.sequence = sequence;
        sample.commandTimestamp = timestamp;
        sample.appliedSpeedMetersPerSecond = speed * 0.9F;
        sample.appliedDirection = appliedDirection;
        return sample;
    };

    aggregator.addSample(makeSample(1.0F, 0.5F, 11.1F, 30.0F, false, LightsState::BothRed, LightsSource::Automatic,
                                    ActiveCab::None, 0x00U, false, 10U, 100U, Direction::Forward));
    aggregator.addSample(makeSample(1.5F, 0.6F, 11.0F, 31.0F, false, LightsState::BothRed, LightsSource::Automatic,
                                    ActiveCab::None, 0x00U, false, 11U, 200U, Direction::Forward));
    aggregator.addSample(makeSample(2.0F, 0.7F, 10.9F, 32.0F, true, LightsState::FrontWhiteRearRed,
                                    LightsSource::Override, ActiveCab::Front, 0x01U, false, 12U, 300U,
                                    Direction::Reverse));

    auto avg = aggregator.average();
    if (!avg) {
        std::cerr << "Average should be available" << std::endl;
        return 1;
    }
    if (avg->speedMetersPerSecond < 1.4F || avg->speedMetersPerSecond > 1.6F) {
        std::cerr << "Unexpected average speed" << std::endl;
        return 1;
    }
    if (!avg->failSafeActive) {
        std::cerr << "Fail-safe flag should aggregate with OR" << std::endl;
        return 1;
    }
    if (avg->lightsState != LightsState::FrontWhiteRearRed || avg->lightsSource != LightsSource::Override) {
        std::cerr << "Latest lights metadata should be preserved in averages" << std::endl;
        return 1;
    }
    if (avg->sequence != 12U || avg->commandTimestamp != 300U || avg->appliedDirection != Direction::Reverse) {
        std::cerr << "Correlation metadata should track most recent sample" << std::endl;
        return 1;
    }
    if (avg->source != TelemetrySource::Aggregated) {
        std::cerr << "Averaged telemetry should mark aggregated source" << std::endl;
        return 1;
    }

    aggregator.addSample(makeSample(2.5F, 0.8F, 10.8F, 33.0F, false, LightsState::BothRed, LightsSource::Automatic,
                                    ActiveCab::None, 0x00U, false, 13U, 400U, Direction::Forward));
    auto history = aggregator.history();
    if (history.size() != 3 || history.front().speedMetersPerSecond != 1.5F) {
        std::cerr << "Aggregator should drop old samples" << std::endl;
        return 1;
    }

    aggregator.clear();
    if (aggregator.average()) {
        std::cerr << "Average should be empty after clear" << std::endl;
        return 1;
    }

    return 0;
}

} // namespace minitrain::tests
