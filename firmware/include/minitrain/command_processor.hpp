#pragma once

#include <chrono>
#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "minitrain/command_channel.hpp"
#include "minitrain/train_state.hpp"

namespace minitrain {

class TrainController;

struct CommandResult {
    bool success{false};
    std::string message;
};

enum class CommandKind : std::uint8_t {
    SetSpeed = 0x01,
    SetDirection = 0x02,
    ToggleHeadlights = 0x03,
    ToggleHorn = 0x04,
    EmergencyStop = 0x05,
    Legacy = 0xFE,
};

struct BinaryCommandPayload {
    CommandKind kind{CommandKind::SetSpeed};
    std::vector<std::uint8_t> data;
};

class CommandProcessor {
  public:
    using LegacyParser = std::function<CommandResult(const std::string &)>;

    CommandProcessor(TrainController &controller, std::optional<LegacyParser> legacyParser = std::nullopt);

    CommandResult processFrame(const CommandFrame &frame, std::chrono::steady_clock::time_point arrival);

    [[nodiscard]] bool lowFrequencyFallbackActive() const;

  private:
    CommandResult handlePayload(const BinaryCommandPayload &payload);
    CommandResult handleLegacyPayload(const std::vector<std::uint8_t> &payload);

    BinaryCommandPayload parsePayload(const std::vector<std::uint8_t> &payload) const;

    TrainController &controller_;
    std::optional<LegacyParser> legacyParser_;
    std::optional<std::chrono::steady_clock::time_point> lastArrival_;
    bool lowFrequencyFallback_{false};
};

} // namespace minitrain
