#include "System.h"
#include <iostream>

System::System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer)
    : mSensor(sensor) {

    std::cout << "SphereSLAM System Initializing..." << std::endl;

    // Initialize Camera Model (CubeMap)
    // Assuming 512x512 faces for now
    mpCamera = new CubeMapCamera(512, 512);

    // Initialize Map
    mpMap = new Map();

    // Initialize Local Mapping
    mpLocalMapper = new LocalMapping(this, mpMap);

    // Initialize Tracking
    mpTracker = new Tracking(this, mpCamera, mpMap, mpLocalMapper);

    // Start Threads
    mptLocalMapping = new std::thread(&LocalMapping::Run, mpLocalMapper);
}

cv::Mat System::TrackMonocular(const cv::Mat &im, const double &timestamp) {
    // Deprecated for SphereSLAM, or used if we just pass one face
    std::vector<cv::Mat> faces;
    faces.push_back(im);
    return mpTracker->GrabImageCubeMap(faces, timestamp);
}

cv::Mat System::TrackCubeMap(const std::vector<cv::Mat> &faces, const double &timestamp) {
    return mpTracker->GrabImageCubeMap(faces, timestamp);
}

void System::Shutdown() {
    mpLocalMapper->RequestFinish();

    if (mptLocalMapping) {
        mptLocalMapping->join();
    }
}
