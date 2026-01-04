#include "System.h"
#include <iostream>

System::System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer)
    : mSensor(sensor), mpDensifier(nullptr) {

    std::cout << "SphereSLAM System Initializing..." << std::endl;

    // Initialize Camera Model (CubeMap)
    // Assuming 512x512 faces for now
    mpCamera = new CubeMapCamera(512, 512);

    // Initialize Map
    mpMap = new Map();

    // Initialize KeyFrame Database
    mpKeyFrameDatabase = new KeyFrameDatabase();

    // Initialize Local Mapping
    mpLocalMapper = new LocalMapping(this, mpMap);

    // Initialize Loop Closing
    mpLoopCloser = new LoopClosing(this, mpMap, mpKeyFrameDatabase, false);

    // Connect Modules
    mpLocalMapper->SetLoopCloser(mpLoopCloser);

    // Initialize Tracking
    mpTracker = new Tracking(this, mpCamera, mpMap, mpLocalMapper);

    // Start Threads
    mptLocalMapping = new std::thread(&LocalMapping::Run, mpLocalMapper);
    mptLoopClosing = new std::thread(&LoopClosing::Run, mpLoopCloser);
}

void System::SetDensifier(Densifier* pDensifier) {
    mpDensifier = pDensifier;
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

void System::ProcessIMU(const cv::Point3f &data, const double &timestamp, int type) {
    // Pass to Tracker (or a dedicated Preintegrator)
    // For Blueprint: Simple pass-through or queue
    // mpTracker->GrabIMU(data, timestamp, type);
}

void System::SaveMap(const std::string &filename) {
    if (mpMap) {
        mpMap->Serialize(filename);
    }
}

int System::GetTrackingState() {
    if (mpTracker) {
        return mpTracker->GetState();
    }
    return -1;
}

void System::Shutdown() {
    mpLocalMapper->RequestFinish();
    mpLoopCloser->RequestFinish();

    if (mptLocalMapping) {
        mptLocalMapping->join();
    }
    if (mptLoopClosing) {
        mptLoopClosing->join();
    }
}
