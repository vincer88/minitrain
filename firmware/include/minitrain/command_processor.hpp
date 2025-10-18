#pragma once

#include <chrono>
#include <cstdint>
#include <functional>
#include <optional>
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

class CommandProcessor {
  public:
    using LegacyParser = std::function<CommandResult(const std::string &)>;

    CommandProcessor(TrainController &controller, std::optional<LegacyParser> legacyParser = std::nullopt);

    CommandResult processFrame(const CommandFrame &frame, std::chrono::steady_clock::time_point arrival);

    [[nodiscard]] bool lowFrequencyFallbackActive() const;

  private:
    CommandResult handleLegacyPayload(const std::vector<std::uint8_t> &payload);

    TrainController &controller_;
    std::optional<LegacyParser> legacyParser_;
    std::optional<std::chrono::steady_clock::time_point> lastArrival_;
    bool lowFrequencyFallback_{false};
};

} // namespace minitrain
