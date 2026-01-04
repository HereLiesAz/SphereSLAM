#include "System.h"
#include <iostream>

System::System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer)
    : mSensor(sensor) {

    std::cout << "SphereSLAM System Initializing..." << std::endl;

    // Load Vocabulary
    // Load Settings

    // Initialize Map
    // Initialize KeyFrameDatabase

    // Initialize Drawers (if viewer is used - but we pruned it)

    // Initialize Local Mapping
    // Initialize Loop Closing
    // Initialize Tracking

    // Create threads
}

cv::Mat System::TrackMonocular(const cv::Mat &im, const double &timestamp) {
    // Check mode change

    // Pass to Tracker
    // return mpTracker->GrabImageMonocular(im, timestamp);

    // Placeholder return identity
    return cv::Mat::eye(4, 4, CV_32F);
}

void System::Shutdown() {
    // Request finish to threads
    // Wait for threads
}
