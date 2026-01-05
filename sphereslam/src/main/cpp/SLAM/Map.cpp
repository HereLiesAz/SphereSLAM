#include "Map.h"
#include <fstream>
#include <iostream>

Map::Map() {
}

void Map::AddKeyFrame(KeyFrame* pKF) {
    std::unique_lock<std::mutex> lock(mMutexMap);
    mspKeyFrames.insert(pKF);
}

void Map::AddMapPoint(MapPoint* pMP) {
    std::unique_lock<std::mutex> lock(mMutexMap);
    mspMapPoints.insert(pMP);
}

std::vector<KeyFrame*> Map::GetAllKeyFrames() {
    std::unique_lock<std::mutex> lock(mMutexMap);
    return std::vector<KeyFrame*>(mspKeyFrames.begin(), mspKeyFrames.end());
}

std::vector<MapPoint*> Map::GetAllMapPoints() {
    std::unique_lock<std::mutex> lock(mMutexMap);
    return std::vector<MapPoint*>(mspMapPoints.begin(), mspMapPoints.end());
}

void Map::SetReferenceMapPoints(const std::vector<MapPoint*> &vpMPs) {
    std::unique_lock<std::mutex> lock(mMutexMap);
    mvpReferenceMapPoints = vpMPs;
}

void Map::Serialize(const std::string& filename) {
    std::unique_lock<std::mutex> lock(mMutexMap);
    std::ofstream f(filename);
    if (!f.is_open()) return;

    // Header
    f << "Map v1.0" << std::endl;
    f << "KeyFrames: " << mspKeyFrames.size() << std::endl;
    f << "MapPoints: " << mspMapPoints.size() << std::endl;

    // Save KeyFrames
    for (auto kf : mspKeyFrames) {
        cv::Mat Tcw = kf->GetPose();
        f << "KF " << kf->mnId << " " << kf->mTimeStamp << std::endl;
        // ... Save Pose ...
    }

    // Save MapPoints
    for (auto mp : mspMapPoints) {
        cv::Point3f pos = mp->GetWorldPos();
        f << "MP " << mp->mnId << " " << pos.x << " " << pos.y << " " << pos.z << std::endl;
    }

    f.close();
    std::cout << "Map serialized to " << filename << std::endl;
}
