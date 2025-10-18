cmake_minimum_required(VERSION 3.16)
project(minitrain_firmware LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(MINITRAIN_FAILSAFE_THRESHOLD_MS 150 CACHE STRING "Maximum age in milliseconds before fail-safe engages")
set(MINITRAIN_FAILSAFE_RAMP_MS 1000 CACHE STRING "Duration in milliseconds of the fail-safe ramp down")

add_library(minitrain_core
    src/pid_controller.cpp
    src/train_state.cpp
    src/telemetry.cpp
    src/command_processor.cpp
    src/command_channel.cpp
    src/train_controller.cpp
    src/light_controller.cpp
    src/camera_streamer.cpp
)

target_include_directories(minitrain_core PUBLIC include)

target_compile_options(minitrain_core PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(minitrain_core PUBLIC
    MINITRAIN_FAILSAFE_THRESHOLD_MS=${MINITRAIN_FAILSAFE_THRESHOLD_MS}
    MINITRAIN_FAILSAFE_RAMP_MS=${MINITRAIN_FAILSAFE_RAMP_MS}
)

if(ESP_PLATFORM)
    target_link_libraries(minitrain_core PUBLIC esp_camera esp_timer)
endif()

add_executable(minitrain_sim
    main/main.cpp
    main/secure_websocket_client.cpp
    main/tls_credentials.cpp
)
target_link_libraries(minitrain_sim PRIVATE minitrain_core)

add_executable(minitrain_tests
    tests/test_main.cpp
    tests/test_pid_controller.cpp
    tests/test_telemetry.cpp
    tests/test_command_processor.cpp
    tests/test_train_controller.cpp
    tests/test_command_channel.cpp
)

target_link_libraries(minitrain_tests PRIVATE minitrain_core)

target_compile_options(minitrain_tests PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(minitrain_tests PUBLIC
    MINITRAIN_FAILSAFE_THRESHOLD_MS=${MINITRAIN_FAILSAFE_THRESHOLD_MS}
    MINITRAIN_FAILSAFE_RAMP_MS=${MINITRAIN_FAILSAFE_RAMP_MS}
)

enable_testing()
add_test(NAME firmware_tests COMMAND minitrain_tests)
