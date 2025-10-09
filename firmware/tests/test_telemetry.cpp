#include "minitrain/telemetry.hpp"

#include <iostream>

#include "test_suite.hpp"

namespace minitrain::tests {

int runTelemetryTests() {
    TelemetryAggregator aggregator{3};
    aggregator.addSample(TelemetrySample{1.0F, 0.5F, 11.1F, 30.0F, false});
    aggregator.addSample(TelemetrySample{1.5F, 0.6F, 11.0F, 31.0F, false});
    aggregator.addSample(TelemetrySample{2.0F, 0.7F, 10.9F, 32.0F, true});

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

    aggregator.addSample(TelemetrySample{2.5F, 0.8F, 10.8F, 33.0F, false});
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
