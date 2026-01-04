#ifndef KEYFRAME_H
#define KEYFRAME_H

#include "Frame.h"
#include "MapPoint.h"
#include <set>

class Map;

class KeyFrame {
public:
    KeyFrame(Frame &F, Map* pMap, KeyFrameDatabase* pKFDB);

    void SetPose(const cv::Mat &Tcw);
    cv::Mat GetPose();

    // Connections
    void AddConnection(KeyFrame* pKF, const int &weight);
    std::set<KeyFrame*> GetConnectedKeyFrames();

public:
    long unsigned int mnId;
    long unsigned int mnFrameId;

    double mTimeStamp;

    // Pose
    cv::Mat mTcw;

    // MapPoints
    std::vector<MapPoint*> mvpMapPoints;

    // Graph
    std::set<KeyFrame*> mspConnectedKeyFrames;

private:
    Map* mpMap;
    std::mutex mMutexPose;
};

#endif // KEYFRAME_H
