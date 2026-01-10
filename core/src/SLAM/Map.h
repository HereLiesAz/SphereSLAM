#ifndef MAP_H
#define MAP_H

#include "KeyFrame.h"
#include "MapPoint.h"
#include <set>
#include <mutex>

class Map {
public:
    Map();

    void AddKeyFrame(KeyFrame* pKF);
    void AddMapPoint(MapPoint* pMP);

    std::vector<KeyFrame*> GetAllKeyFrames();
    std::vector<MapPoint*> GetAllMapPoints();

    void SetReferenceMapPoints(const std::vector<MapPoint*> &vpMPs);

    void Serialize(const std::string& filename);
    bool Load(const std::string& filename);
    void Clear();

protected:
    std::set<MapPoint*> mspMapPoints;
    std::set<KeyFrame*> mspKeyFrames;

    std::vector<MapPoint*> mvpReferenceMapPoints;

    std::mutex mMutexMap;
};

#endif // MAP_H
