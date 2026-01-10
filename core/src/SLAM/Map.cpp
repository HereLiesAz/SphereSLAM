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
    f << "MAP_V1" << std::endl;
    f << mspKeyFrames.size() << " " << mspMapPoints.size() << std::endl;

    // Save KeyFrames
    for (auto kf : mspKeyFrames) {
        cv::Mat Tcw = kf->GetPose();
        if (Tcw.empty()) continue;
        f << "KF " << kf->mnId << " " << kf->mTimeStamp;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                f << " " << Tcw.at<float>(i, j);
            }
        }
        f << std::endl;
    }

    // Save MapPoints
    for (auto mp : mspMapPoints) {
        cv::Point3f pos = mp->GetWorldPos();
        f << "MP " << mp->mnId << " " << pos.x << " " << pos.y << " " << pos.z << std::endl;
    }

    f.close();
    std::cout << "Map serialized to " << filename << std::endl;
}

bool Map::Load(const std::string& filename) {
    std::ifstream f(filename);
    if (!f.is_open()) return false;

    std::string header;
    f >> header;
    if (header != "MAP_V1") return false;

    size_t numKFs, numMPs;
    f >> numKFs >> numMPs;

    Clear();

    std::string type;
    while (f >> type) {
        if (type == "KF") {
            long unsigned int id;
            double timestamp;
            f >> id >> timestamp;
            cv::Mat Tcw(4, 4, CV_32F);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    f >> Tcw.at<float>(i, j);
                }
            }
            KeyFrame* kf = new KeyFrame(id, timestamp, Tcw, this);
            AddKeyFrame(kf);
        } else if (type == "MP") {
            long unsigned int id;
            float x, y, z;
            f >> id >> x >> y >> z;
            MapPoint* mp = new MapPoint(id, cv::Point3f(x, y, z), this);
            AddMapPoint(mp);
        }
    }
    f.close();
    return true;
}

void Map::Clear() {
    std::unique_lock<std::mutex> lock(mMutexMap);
    // Delete all KFs and MPs
    for (auto kf : mspKeyFrames) delete kf;
    mspKeyFrames.clear();
    for (auto mp : mspMapPoints) delete mp;
    mspMapPoints.clear();
    mvpReferenceMapPoints.clear();
}
