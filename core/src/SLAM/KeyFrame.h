#ifndef KEYFRAME_H
#define KEYFRAME_H

#include "Frame.h"
#include "MapPoint.h"
#include <set>

class Map;
class KeyFrameDatabase;

class KeyFrame {
public:
    KeyFrame(Frame &F, Map* pMap, KeyFrameDatabase* pKFDB);
    // Constructor for Loading
    KeyFrame(long unsigned int id, double timeStamp, const cv::Mat &Tcw, Map* pMap);

    void SetPose(const cv::Mat &Tcw);
    cv::Mat GetPose();
    cv::Mat GetPoseInverse();

    // Connections
    void AddConnection(KeyFrame* pKF, const int &weight);
    std::set<KeyFrame*> GetConnectedKeyFrames();

public:
    long unsigned int mnId;
    long unsigned int mnFrameId;

    double mTimeStamp;

    // Pose
    cv::Mat mTcw;

    // Intrinsics (Cached for Photosphere Stitching)
    cv::Mat mK;

    // Source Images (Filenames in Cache) - Avoid OOM
    std::vector<std::string> mImgFilenames;
    static std::string msCacheDir;

    // MapPoints
    std::vector<MapPoint*> mvpMapPoints;

    // Graph
    std::set<KeyFrame*> mspConnectedKeyFrames;

private:
    Map* mpMap;
    std::mutex mMutexPose;
};

#endif // KEYFRAME_H
