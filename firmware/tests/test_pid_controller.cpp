#include "minitrain/pid_controller.hpp"

#include <chrono>
#include <cmath>
#include <iostream>

#include "test_suite.hpp"

namespace minitrain::tests {

int runPidControllerTests() {
    PidController controller{1.0F, 0.1F, 0.01F, 0.0F, 1.0F};
    auto dt = std::chrono::milliseconds{100};

    float command = controller.update(1.0F, 0.0F, dt);
    if (command <= 0.0F || command > 1.0F) {
        std::cerr << "PID initial command out of range" << std::endl;
        return 1;
    }

    controller.reset();
    float command2 = controller.update(0.0F, 1.0F, dt);
    if (command2 > 0.1F) {
        std::cerr << "PID command should be near zero when measurement exceeds target" << std::endl;
        return 1;
    }

    controller.reset();
    float previous = 0.0F;
    for (int i = 0; i < 10; ++i) {
        previous = controller.update(2.0F, 1.0F + 0.05F * static_cast<float>(i), dt);
    }
    if (previous < 0.2F) {
        std::cerr << "PID should accumulate integral contribution" << std::endl;
        return 1;
    }

    return 0;
}

} // namespace minitrain::tests
