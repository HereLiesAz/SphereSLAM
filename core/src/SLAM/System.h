#ifndef SYSTEM_H
#define SYSTEM_H

#include <string>
#include <thread>
#include <opencv2/core.hpp>
#include <vector>
#include <mutex>
#include <queue>

#include "Tracking.h"
#include "GeometricCamera.h"
#include "Map.h"
#include "LocalMapping.h"
#include "LoopClosing.h"
#include "KeyFrameDatabase.h"
#include "Platform.h"

// New forward declaration
class Densifier;
class DepthAnyCamera;

class System {
public:
    enum eSensor {
        MONOCULAR = 0,
        STEREO = 1,
        RGBD = 2,
        IMU_MONOCULAR = 3,
        IMU_STEREO = 4
    };

    struct IMUData {
        cv::Point3f data;
        double timestamp;
        int type; // 0: Accel, 1: Gyro
    };

    System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, Platform* pPlatform, const bool bUseViewer = true);

    ~System();

    // Process a new image
    // Returns the camera pose (Tcw)
    cv::Mat TrackMonocular(const cv::Mat &im, const double &timestamp);

    // New: Process CubeMap (6 faces)
    cv::Mat TrackCubeMap(const std::vector<cv::Mat> &faces, const double &timestamp);

    // New: Process IMU
    void ProcessIMU(const cv::Point3f &data, const double &timestamp, int type);

    // Setters for Densification (called from JNI)
    void SetDensifier(Densifier* pDensifier);

    // New: Save Map
    void SaveMap(const std::string &filename);

    // New: Load Map
    bool LoadMap(const std::string &filename);

    // New: Save Trajectory
    void SaveTrajectoryTUM(const std::string &filename);

    // New: Save Photosphere
    void SavePhotosphere(const std::string &filename);

    int GetTrackingState();

    // Reset System
    void Reset();

    // Accessors
    std::vector<MapPoint*> GetAllMapPoints();

    // Statistics
    std::string GetMapStats();

    void Shutdown();

    Platform* mpPlatform; // Made public or accessor needed? Keeping public for internal modules ease for now.

private:
    eSensor mSensor;

    // Modules
    Tracking* mpTracker;
    LocalMapping* mpLocalMapper;
    LoopClosing* mpLoopCloser;
    Map* mpMap;
    KeyFrameDatabase* mpKeyFrameDatabase;

    GeometricCamera* mpCamera;

    // New: Densifier
    Densifier* mpDensifier;

    // Threads
    std::thread* mptLocalMapping;
    std::thread* mptLoopClosing;

    // IMU Buffer
    std::queue<IMUData> mImuQueue;
    std::mutex mMutexImu;

    // Photosphere Capture Cache
    std::vector<cv::Mat> mLastFaces;
    std::mutex mMutexFaces;
};

#endif // SYSTEM_H
