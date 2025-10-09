#pragma once

#include <cstdint>

#include "minitrain/train_state.hpp"

namespace minitrain {

class LightController {
  public:
    static void applyAutomaticLogic(TrainState &state);
};

} // namespace minitrain

