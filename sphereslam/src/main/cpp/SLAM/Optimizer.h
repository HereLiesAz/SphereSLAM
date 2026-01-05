#ifndef OPTIMIZER_H
#define OPTIMIZER_H

#include "Map.h"
#include "MapPoint.h"
#include "KeyFrame.h"
#include "LoopClosing.h"
#include "Frame.h"

class Optimizer {
public:
    void static PoseOptimization(Frame* pFrame);
    void static LocalBundleAdjustment(KeyFrame* pKF, bool* pbStopFlag, Map* pMap);
    void static GlobalBundleAdjustment(Map* pMap, int nIterations, bool* pbStopFlag, const unsigned long nLoopKF, bool bRobust);

    // Sim3 Optimization for Loop Closing
    void static OptimizeSim3(KeyFrame* pKF1, KeyFrame* pKF2, std::vector<MapPoint*> &vpMatches1, cv::Mat &g2oScw, const float th2, bool bFixScale);
};

#endif // OPTIMIZER_H
