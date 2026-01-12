#ifndef OPTIMIZER_H
#define OPTIMIZER_H

#include <vector>
#include "Mosaic.h"

namespace lightcycle {

class Optimizer {
public:
    Optimizer();
    ~Optimizer();

    // Section 3.3: Bundle Adjustment
    void optimize(std::vector<Frame>& frames, double& sharedFocalLength);
};

} // namespace lightcycle

#endif // OPTIMIZER_H
