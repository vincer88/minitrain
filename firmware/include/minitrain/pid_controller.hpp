#pragma once

#include <chrono>

namespace minitrain {

class PidController {
  public:
    PidController(float kp, float ki, float kd, float minOutput, float maxOutput);

    float update(float target, float measurement, std::chrono::steady_clock::duration dt);
    void reset();

  private:
    float kp_;
    float ki_;
    float kd_;
    float minOutput_;
    float maxOutput_;
    float integral_;
    float previousError_;
    bool hasPreviousError_;
};

} // namespace minitrain
