#include "minitrain/light_controller.hpp"

namespace minitrain {
namespace {

constexpr std::uint8_t kWhiteFront = 0x01;
constexpr std::uint8_t kWhiteRear = 0x02;
constexpr std::uint8_t kRedFront = 0x04;
constexpr std::uint8_t kRedRear = 0x08;
enum class LightColor : std::uint8_t {
    Red,
    White,
    Off
};

struct EndColors {
    LightColor front{LightColor::Red};
    LightColor rear{LightColor::Red};
};

LightsState encode(const EndColors &colors) {
    if (colors.front == LightColor::White && colors.rear == LightColor::Red) {
        return LightsState::FrontWhiteRearRed;
    }
    if (colors.front == LightColor::Red && colors.rear == LightColor::White) {
        return LightsState::FrontRedRearWhite;
    }
    if (colors.front == LightColor::Off && colors.rear == LightColor::Off) {
        return LightsState::BothOff;
    }
    if (colors.front == LightColor::White && colors.rear == LightColor::White) {
        return LightsState::BothWhite;
    }
    return LightsState::BothRed;
}

EndColors computeAutomatic(const TrainState &state) {
    if (state.activeCab == ActiveCab::None || state.direction == Direction::Neutral) {
        return {LightColor::Red, LightColor::Red};
    }

    const bool movingForward = state.direction == Direction::Forward;
    if (state.activeCab == ActiveCab::Front) {
        if (movingForward) {
            return {LightColor::White, LightColor::Red};
        }
        return {LightColor::Red, LightColor::White};
    }

    if (state.activeCab == ActiveCab::Rear) {
        if (movingForward) {
            return {LightColor::Red, LightColor::White};
        }
        return {LightColor::White, LightColor::Red};
    }

    return {LightColor::Red, LightColor::Red};
}

LightColor selectColor(bool forceWhite, bool forceRed, LightColor fallback) {
    if (forceWhite) {
        return LightColor::White;
    }
    if (forceRed) {
        return LightColor::Red;
    }
    return fallback;
}

} // namespace

void LightController::applyAutomaticLogic(TrainState &state) {
    if (state.failSafeActive) {
        state.lightsState = LightsState::BothRed;
        state.lightsSource = LightsSource::FailSafe;
        return;
    }

    const auto baseColors = computeAutomatic(state);
    auto colors = baseColors;
    auto mask = static_cast<std::uint8_t>(state.lightsOverrideMask & 0x0F);

    if (state.lightsTelemetryOnly) {
        mask = 0;
    }

    if (mask != 0) {
        colors.front = selectColor((mask & kWhiteFront) != 0, (mask & kRedFront) != 0, colors.front);
        colors.rear = selectColor((mask & kWhiteRear) != 0, (mask & kRedRear) != 0, colors.rear);
        state.lightsState = encode(colors);
        state.lightsSource = LightsSource::Override;
    } else {
        state.lightsState = encode(colors);
        state.lightsSource = LightsSource::Automatic;
    }
}

} // namespace minitrain

