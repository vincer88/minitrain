#include "minitrain/command_processor.hpp"

#include <cctype>
#include <sstream>
#include <stdexcept>

namespace minitrain {

CommandProcessor::CommandProcessor() = default;

CommandResult CommandProcessor::process(const std::string &commandText) {
    auto pairs = parseKeyValuePairs(commandText);
    auto commandIt = pairs.find("command");
    if (commandIt == pairs.end()) {
        return {false, "Missing command key"};
    }

    const auto handlerIt = handlers_.find(commandIt->second);
    if (handlerIt == handlers_.end()) {
        return {false, "Unknown command: " + commandIt->second};
    }

    return handlerIt->second(pairs);
}

void CommandProcessor::registerHandler(const std::string &name, CommandHandler handler) {
    handlers_[name] = std::move(handler);
}

std::unordered_map<std::string, std::string> CommandProcessor::parseKeyValuePairs(const std::string &commandText) {
    std::unordered_map<std::string, std::string> result;
    std::stringstream stream(commandText);
    std::string token;

    while (std::getline(stream, token, ';')) {
        if (token.empty()) {
            continue;
        }
        const auto delimiterPos = token.find('=');
        if (delimiterPos == std::string::npos) {
            throw std::invalid_argument("Invalid token: " + token);
        }
        std::string key = token.substr(0, delimiterPos);
        std::string value = token.substr(delimiterPos + 1);
        // trim spaces
        auto trim = [](std::string &str) {
            while (!str.empty() && std::isspace(static_cast<unsigned char>(str.front()))) {
                str.erase(str.begin());
            }
            while (!str.empty() && std::isspace(static_cast<unsigned char>(str.back()))) {
                str.pop_back();
            }
        };
        trim(key);
        trim(value);
        result[key] = value;
    }

    return result;
}

} // namespace minitrain
