#include "minitrain/command_processor.hpp"

#include <iostream>

#include "test_suite.hpp"

namespace minitrain::tests {

int runCommandProcessorTests() {
    CommandProcessor processor;
    bool handlerCalled = false;
    processor.registerHandler("set_speed", [&handlerCalled](const auto &params) {
        handlerCalled = true;
        auto it = params.find("value");
        if (it == params.end() || it->second != "1.2") {
            return CommandResult{false, "Unexpected value"};
        }
        return CommandResult{true, "OK"};
    });

    auto result = processor.process("command=set_speed;value=1.2");
    if (!result.success || !handlerCalled) {
        std::cerr << "Handler should have been executed" << std::endl;
        return 1;
    }

    auto invalid = processor.process("command=unknown");
    if (invalid.success || invalid.message.find("Unknown") == std::string::npos) {
        std::cerr << "Unknown command should fail" << std::endl;
        return 1;
    }

    auto malformed = processor.process("command=set_speed value=1.0");
    if (malformed.success) {
        std::cerr << "Malformed command should fail" << std::endl;
        return 1;
    }

    return 0;
}

} // namespace minitrain::tests
