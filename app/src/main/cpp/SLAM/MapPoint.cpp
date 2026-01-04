#include "MapPoint.h"

long unsigned int MapPoint::nNextId = 0;

MapPoint::MapPoint(const cv::Point3f &Pos, KeyFrame* pRefKF, Map* pMap)
    : mWorldPos(Pos), mpRefKF(pRefKF), mpMap(pMap)
{
    mnId = nNextId++;
}

void MapPoint::SetWorldPos(const cv::Point3f &Pos) {
    std::unique_lock<std::mutex> lock(mMutexPos);
    mWorldPos = Pos;
}

cv::Point3f MapPoint::GetWorldPos() {
    std::unique_lock<std::mutex> lock(mMutexPos);
    return mWorldPos;
}

void MapPoint::AddObservation(KeyFrame* pKF, size_t idx) {
    // Stub
}
