#pragma once

#include <functional>
#include <optional>
#include <string>
#include <unordered_map>

#include "minitrain/train_state.hpp"

namespace minitrain {

struct CommandResult {
    bool success{false};
    std::string message;
};

class CommandProcessor {
  public:
    using CommandHandler = std::function<CommandResult(const std::unordered_map<std::string, std::string> &)>;

    CommandProcessor();

    CommandResult process(const std::string &commandText);
    void registerHandler(const std::string &name, CommandHandler handler);

  private:
    std::unordered_map<std::string, CommandHandler> handlers_;

    static std::unordered_map<std::string, std::string> parseKeyValuePairs(const std::string &commandText);
};

} // namespace minitrain
