cmake_minimum_required(VERSION 3.16)
project(minitrain_firmware LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(minitrain_core
    src/pid_controller.cpp
    src/train_state.cpp
    src/telemetry.cpp
    src/command_processor.cpp
    src/train_controller.cpp
)

target_include_directories(minitrain_core PUBLIC include)

target_compile_options(minitrain_core PRIVATE -Wall -Wextra -Wpedantic)

add_executable(minitrain_sim main/main.cpp)
target_link_libraries(minitrain_sim PRIVATE minitrain_core)

add_executable(minitrain_tests
    tests/test_main.cpp
    tests/test_pid_controller.cpp
    tests/test_telemetry.cpp
    tests/test_command_processor.cpp
    tests/test_train_controller.cpp
)

target_link_libraries(minitrain_tests PRIVATE minitrain_core)

target_compile_options(minitrain_tests PRIVATE -Wall -Wextra -Wpedantic)

enable_testing()
add_test(NAME firmware_tests COMMAND minitrain_tests)
