#include <iostream>

#include "test_suite.hpp"

int main() {
    using namespace minitrain::tests;

    int failures = 0;
    failures += runPidControllerTests();
    failures += runTelemetryTests();
    failures += runCommandProcessorTests();
    failures += runTrainControllerTests();

    if (failures == 0) {
        std::cout << "All firmware tests passed" << std::endl;
    } else {
        std::cout << failures << " firmware tests failed" << std::endl;
    }

    return failures == 0 ? 0 : 1;
}
