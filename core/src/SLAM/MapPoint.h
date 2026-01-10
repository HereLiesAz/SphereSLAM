#ifndef MAPPOINT_H
#define MAPPOINT_H

#include <opencv2/core.hpp>
#include <mutex>

class KeyFrame;
class Map;

class MapPoint {
public:
    MapPoint(const cv::Point3f &Pos, KeyFrame* pRefKF, Map* pMap);
    // Constructor for Loading
    MapPoint(long unsigned int id, const cv::Point3f &Pos, Map* pMap);

    void SetWorldPos(const cv::Point3f &Pos);
    cv::Point3f GetWorldPos();

    // For Blueprint: Simplified observations
    void AddObservation(KeyFrame* pKF, size_t idx);

public:
    long unsigned int mnId;
    static long unsigned int nNextId;

    long int mnFirstKFid;

protected:
    cv::Point3f mWorldPos;
    std::mutex mMutexPos;

    Map* mpMap;
    KeyFrame* mpRefKF;
};

#endif // MAPPOINT_H
