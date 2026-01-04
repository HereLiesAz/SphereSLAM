#ifndef SYSTEM_H
#define SYSTEM_H

#include <string>
#include <thread>
#include <opencv2/core/core.hpp>

#include "Tracking.h"
#include "GeometricCamera.h"
#include "Map.h"
#include "LocalMapping.h"

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

    // New: Process CubeMap (6 faces)
    cv::Mat TrackCubeMap(const std::vector<cv::Mat> &faces, const double &timestamp);

    void Shutdown();

private:
    eSensor mSensor;

    // Modules
    Tracking* mpTracker;
    LocalMapping* mpLocalMapper;
    Map* mpMap;

    GeometricCamera* mpCamera;

    // Threads
    std::thread* mptLocalMapping;
};

#endif // SYSTEM_H
