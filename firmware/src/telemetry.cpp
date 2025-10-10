#include "minitrain/telemetry.hpp"

#include <numeric>

namespace minitrain {

TelemetryAggregator::TelemetryAggregator(std::size_t windowSize) : windowSize_{windowSize} {
    samples_.reserve(windowSize_);
}

void TelemetryAggregator::addSample(const TelemetrySample &sample) {
    if (samples_.size() == windowSize_) {
        samples_.erase(samples_.begin());
    }
    samples_.push_back(sample);
}

std::optional<TelemetrySample> TelemetryAggregator::average() const {
    if (samples_.empty()) {
        return std::nullopt;
    }

    TelemetrySample result{};
    bool anyFailSafe = false;
    for (const auto &sample : samples_) {
        result.speedMetersPerSecond += sample.speedMetersPerSecond;
        result.motorCurrentAmps += sample.motorCurrentAmps;
        result.batteryVoltage += sample.batteryVoltage;
        result.temperatureCelsius += sample.temperatureCelsius;
        result.appliedSpeedMetersPerSecond += sample.appliedSpeedMetersPerSecond;
        anyFailSafe = anyFailSafe || sample.failSafeActive;
    }

    const float size = static_cast<float>(samples_.size());
    result.speedMetersPerSecond /= size;
    result.motorCurrentAmps /= size;
    result.batteryVoltage /= size;
    result.temperatureCelsius /= size;
    result.appliedSpeedMetersPerSecond /= size;
    result.failSafeActive = anyFailSafe;
    const auto &latest = samples_.back();
    result.sessionId = latest.sessionId;
    result.sequence = latest.sequence;
    result.commandTimestamp = latest.commandTimestamp;
    result.lightsState = latest.lightsState;
    result.lightsSource = latest.lightsSource;
    result.activeCab = latest.activeCab;
    result.lightsOverrideMask = latest.lightsOverrideMask;
    result.lightsTelemetryOnly = latest.lightsTelemetryOnly;
    result.appliedDirection = latest.appliedDirection;
    result.source = TelemetrySource::Aggregated;

    return result;
}

std::vector<TelemetrySample> TelemetryAggregator::history() const {
    return samples_;
}

void TelemetryAggregator::clear() {
    samples_.clear();
}

} // namespace minitrain
