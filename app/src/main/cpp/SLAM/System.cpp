#include "System.h"
#include "Settings.h"
#include "ORBVocabulary.h"
#include <iostream>
#include <fstream>
#include <iomanip>
#include <sstream>

System::System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer)
    : mSensor(sensor), mpDensifier(nullptr) {

    std::cout << "SphereSLAM System Initializing..." << std::endl;

    // Load Settings
    Settings settings(strSettingsFile);

    // Load Vocabulary
    ORBVocabulary* mpVocabulary = new ORBVocabulary();
    bool bVocLoaded = mpVocabulary->loadFromTextFile(strVocFile);
    if (!bVocLoaded) {
        std::cerr << "Wrong path to vocabulary. " << std::endl;
    }

    // Initialize Camera Model (CubeMap)
    mpCamera = new CubeMapCamera(settings.width, settings.height);

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
    std::vector<cv::Mat> faces;
    faces.push_back(im);
    return mpTracker->GrabImageCubeMap(faces, timestamp);
}

cv::Mat System::TrackCubeMap(const std::vector<cv::Mat> &faces, const double &timestamp) {
    return mpTracker->GrabImageCubeMap(faces, timestamp);
}

void System::ProcessIMU(const cv::Point3f &data, const double &timestamp, int type) {
    // mpTracker->GrabIMU(data, timestamp, type);
}

void System::SaveMap(const std::string &filename) {
    if (mpMap) {
        mpMap->Serialize(filename);
    }
}

void System::SaveTrajectoryTUM(const std::string &filename) {
    std::vector<KeyFrame*> vpKFs = mpMap->GetAllKeyFrames();

    std::ofstream f;
    f.open(filename.c_str());
    f << std::fixed;

    for(size_t i=0; i<vpKFs.size(); i++)
    {
        KeyFrame* pKF = vpKFs[i];
        if(pKF)
        {
            cv::Mat R = pKF->GetPose().rowRange(0,3).colRange(0,3);
            f << std::setprecision(6) << pKF->mTimeStamp << " " << 0 << " " << 0 << " " << 0 << " " << 0 << " " << 0 << " " << 0 << " " << 1 << std::endl;
        }
    }
    f.close();
    std::cout << "Trajectory saved to " << filename << std::endl;
}

int System::GetTrackingState() {
    if (mpTracker) {
        return mpTracker->GetState();
    }
    return -1;
}

void System::Reset() {
    if (mpTracker) {
        mpTracker->Reset();
    }
    // Need to clear Map, KeyFrameDatabase, etc.
    // For blueprint, we assume Map and Database have Clear() methods
    // mpMap->Clear();
    // mpKeyFrameDatabase->Clear();
    std::cout << "System Reset" << std::endl;
}

std::string System::GetMapStats() {
    if (!mpMap) return "System Not Init";
    std::stringstream ss;
    ss << "KF: " << mpMap->GetAllKeyFrames().size()
       << " MP: " << mpMap->GetAllMapPoints().size();
    return ss.str();
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
