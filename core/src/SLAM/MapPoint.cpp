#include "MapPoint.h"

long unsigned int MapPoint::nNextId = 0;

MapPoint::MapPoint(const cv::Point3f &Pos, KeyFrame* pRefKF, Map* pMap)
    : mWorldPos(Pos), mpRefKF(pRefKF), mpMap(pMap)
{
    mnId = nNextId++;
}

MapPoint::MapPoint(long unsigned int id, const cv::Point3f &Pos, Map* pMap)
    : mnId(id), mWorldPos(Pos), mpRefKF(nullptr), mpMap(pMap)
{
    if (mnId >= nNextId) {
        nNextId = mnId + 1;
    }
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
