#ifndef SYSTEM_H
#define SYSTEM_H

#include <string>
#include <thread>
#include <opencv2/core/core.hpp>

// Forward declarations
class Tracking;
class LocalMapping;
class LoopClosing;
class KeyFrameDatabase;
class Map;
class Settings;

class System {
public:
    enum eSensor {
        MONOCULAR = 0,
        STEREO = 1,
        RGBD = 2,
        IMU_MONOCULAR = 3,
        IMU_STEREO = 4
    };

    System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer = true);

    // Process a new image
    // Returns the camera pose (Tcw)
    cv::Mat TrackMonocular(const cv::Mat &im, const double &timestamp);

    void Shutdown();

private:
    // Pointers to the main modules
    // Tracking *mpTracker;
    // LocalMapping *mpLocalMapper;
    // LoopClosing *mpLoopCloser;

    // Map *mpMap;
    // KeyFrameDatabase *mpKeyFrameDatabase;

    // Settings *mpSettings;

    // System threads
    std::thread *mptLocalMapping;
    std::thread *mptLoopClosing;

    eSensor mSensor;
};

#endif // SYSTEM_H
