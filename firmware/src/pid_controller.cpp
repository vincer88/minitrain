#include "minitrain/pid_controller.hpp"

#include <algorithm>

namespace minitrain {

PidController::PidController(float kp, float ki, float kd, float minOutput, float maxOutput)
    : kp_{kp}, ki_{ki}, kd_{kd}, minOutput_{minOutput}, maxOutput_{maxOutput}, integral_{0.0F}, previousError_{0.0F},
      hasPreviousError_{false} {}

float PidController::update(float target, float measurement, std::chrono::steady_clock::duration dt) {
    const float error = target - measurement;
    const float seconds = std::chrono::duration<float>(dt).count();

    if (seconds > 0.0F) {
        integral_ += error * seconds;
    }

    float derivative = 0.0F;
    if (hasPreviousError_ && seconds > 0.0F) {
        derivative = (error - previousError_) / seconds;
    }

    previousError_ = error;
    hasPreviousError_ = true;

    float output = kp_ * error + ki_ * integral_ + kd_ * derivative;
    output = std::clamp(output, minOutput_, maxOutput_);
    return output;
}

void PidController::reset() {
    integral_ = 0.0F;
    previousError_ = 0.0F;
    hasPreviousError_ = false;
}

} // namespace minitrain
