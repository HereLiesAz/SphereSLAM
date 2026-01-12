#ifndef DB_OPTIMIZER_H
#define DB_OPTIMIZER_H

#include <vector>
#include "db_Types.h"

namespace lightcycle {

class Optimizer {
public:
    Optimizer();
    ~Optimizer();

    void optimize(std::vector<Frame>& frames, double& sharedFocalLength);
};

} // namespace lightcycle

#endif // DB_OPTIMIZER_H
