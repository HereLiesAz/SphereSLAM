#ifndef DB_OPTIMIZER_H
#define DB_OPTIMIZER_H

#include <vector>
#include "db_Types.h"

namespace lightcycle {

class dbOptimizer {
public:
    dbOptimizer();
    ~dbOptimizer();

    void optimize(std::vector<Frame>& frames, double& sharedFocalLength);
};

} // namespace lightcycle

#endif // DB_OPTIMIZER_H
