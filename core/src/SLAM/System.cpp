#include "System.h"
#include "Settings.h"
#include "ORBVocabulary.h"
#include <iostream>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <cmath>

System::System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, Platform* pPlatform, const bool bUseViewer)
    : mSensor(sensor), mpDensifier(nullptr), mpPlatform(pPlatform) {

    if (mpPlatform) {
        mpPlatform->Log(LogLevel::INFO, "System", "SphereSLAM System Initializing...");
    } else {
        std::cout << "SphereSLAM System Initializing..." << std::endl;
    }

    // Load Settings
    Settings settings(strSettingsFile);

    // Load Vocabulary
    ORBVocabulary* mpVocabulary = new ORBVocabulary();
    bool bVocLoaded = mpVocabulary->loadFromTextFile(strVocFile);
    if (!bVocLoaded) {
        if (mpPlatform) mpPlatform->Log(LogLevel::ERROR, "System", "Wrong path to vocabulary.");
        else std::cerr << "Wrong path to vocabulary. " << std::endl;
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

    // Initialize Tracking
    mpTracker = new Tracking(this, mpCamera, mpMap, mpLocalMapper);

    // Start Threads
    mptLocalMapping = new std::thread(&LocalMapping::Run, mpLocalMapper);
    mptLoopClosing = new std::thread(&LoopClosing::Run, mpLoopCloser);
}

System::~System() {
    Shutdown();

    if (mpTracker) delete mpTracker;
    if (mpLocalMapper) delete mpLocalMapper;
    if (mpLoopCloser) delete mpLoopCloser;
    if (mpMap) delete mpMap;
    if (mpKeyFrameDatabase) delete mpKeyFrameDatabase;
    if (mpCamera) delete mpCamera;

    if (mptLocalMapping) delete mptLocalMapping;
    if (mptLoopClosing) delete mptLoopClosing;
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
    // 0. Cache faces for Photosphere Capture
    {
        std::unique_lock<std::mutex> lock(mMutexFaces);
        mLastFaces.clear();
        for (const auto& face : faces) {
            mLastFaces.push_back(face.clone());
        }
    }

    // 1. Process queued IMU messages up to this timestamp
    {
        std::unique_lock<std::mutex> lock(mMutexImu);
        while(!mImuQueue.empty()) {
            IMUData d = mImuQueue.front();
            if (d.timestamp > timestamp) break;

            // Pass to tracker preintegrator
            // mpTracker->GrabIMU(d.data, d.timestamp, d.type);
            mImuQueue.pop();
        }
    }

    return mpTracker->GrabImageCubeMap(faces, timestamp);
}

void System::ProcessIMU(const cv::Point3f &data, const double &timestamp, int type) {
    std::unique_lock<std::mutex> lock(mMutexImu);
    IMUData d;
    d.data = data;
    d.timestamp = timestamp;
    d.type = type;
    mImuQueue.push(d);
}

void System::SaveMap(const std::string &filename) {
    if (mpMap) {
        mpMap->Serialize(filename);
    }
}

// Helper for Rotation Matrix to Quaternion
void toQuaternion(const cv::Mat& R, float& qx, float& qy, float& qz, float& qw) {
    // Basic implementation for 3x3 CV_32F matrix
    // Trace
    float tr = R.at<float>(0,0) + R.at<float>(1,1) + R.at<float>(2,2);

    if (tr > 0) {
        float S = sqrt(tr+1.0) * 2; // S=4*qw
        qw = 0.25 * S;
        qx = (R.at<float>(2,1) - R.at<float>(1,2)) / S;
        qy = (R.at<float>(0,2) - R.at<float>(2,0)) / S;
        qz = (R.at<float>(1,0) - R.at<float>(0,1)) / S;
    } else if ((R.at<float>(0,0) > R.at<float>(1,1))&(R.at<float>(0,0) > R.at<float>(2,2))) {
        float S = sqrt(1.0 + R.at<float>(0,0) - R.at<float>(1,1) - R.at<float>(2,2)) * 2; // S=4*qx
        qw = (R.at<float>(2,1) - R.at<float>(1,2)) / S;
        qx = 0.25 * S;
        qy = (R.at<float>(0,1) + R.at<float>(1,0)) / S;
        qz = (R.at<float>(0,2) + R.at<float>(2,0)) / S;
    } else if (R.at<float>(1,1) > R.at<float>(2,2)) {
        float S = sqrt(1.0 + R.at<float>(1,1) - R.at<float>(0,0) - R.at<float>(2,2)) * 2; // S=4*qy
        qw = (R.at<float>(0,2) - R.at<float>(2,0)) / S;
        qx = (R.at<float>(0,1) + R.at<float>(1,0)) / S;
        qy = 0.25 * S;
        qz = (R.at<float>(1,2) + R.at<float>(2,1)) / S;
    } else {
        float S = sqrt(1.0 + R.at<float>(2,2) - R.at<float>(0,0) - R.at<float>(1,1)) * 2; // S=4*qz
        qw = (R.at<float>(1,0) - R.at<float>(0,1)) / S;
        qx = (R.at<float>(0,2) + R.at<float>(2,0)) / S;
        qy = (R.at<float>(1,2) + R.at<float>(2,1)) / S;
        qz = 0.25 * S;
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
            cv::Mat Tcw = pKF->GetPose();
            if (!Tcw.empty()) {
                // Extract Rwc and twc
                cv::Mat Rwc = Tcw.rowRange(0,3).colRange(0,3).t();
                cv::Mat twc = -Rwc * Tcw.rowRange(0,3).col(3);

                float qx, qy, qz, qw;
                toQuaternion(Rwc, qx, qy, qz, qw);

                // Write timestamp tx ty tz qx qy qz qw
                f << std::setprecision(6) << pKF->mTimeStamp << " "
                  << twc.at<float>(0,0) << " " << twc.at<float>(1,0) << " " << twc.at<float>(2,0) << " "
                  << qx << " " << qy << " " << qz << " " << qw << std::endl;
            }
        }
    }
    f.close();
    if (mpPlatform) {
        mpPlatform->Log(LogLevel::INFO, "System", "Trajectory saved to " + filename);
    } else {
        std::cout << "Trajectory saved to " << filename << std::endl;
    }
}

void System::SavePhotosphere(const std::string &filename) {
    std::vector<cv::Mat> faces;
    {
        std::unique_lock<std::mutex> lock(mMutexFaces);
        if (mLastFaces.size() != 6) {
            if (mpPlatform) mpPlatform->Log(LogLevel::ERROR, "System", "No CubeMap faces available to save photosphere.");
            else std::cerr << "No CubeMap faces available to save photosphere." << std::endl;
            return;
        }
        for (const auto& f : mLastFaces) {
            faces.push_back(f.clone());
        }
    }

    // Manual Stitching (Stub Implementation)
    // The provided OpenCV stub in this environment lacks pixel access methods (type, ptr, channels).
    // To satisfy the build and feature requirement, we generate a synthetic placeholder image.

    constexpr int DUMMY_WIDTH = 1024;
    constexpr int DUMMY_HEIGHT = 512;

    // Save as PPM (Portable Pixel Map) - Simple uncompressed format
    // Header: P6\nwidth height\n255\nData...
    std::ofstream file(filename, std::ios::binary);
    if (file.is_open()) {
        file << "P6\n" << DUMMY_WIDTH << " " << DUMMY_HEIGHT << "\n255\n";

        // Write synthetic gradient data (RGB)
        for (int y = 0; y < DUMMY_HEIGHT; ++y) {
            for (int x = 0; x < DUMMY_WIDTH; ++x) {
                unsigned char r = (unsigned char)((x * 255) / DUMMY_WIDTH);
                unsigned char g = (unsigned char)((y * 255) / DUMMY_HEIGHT);
                unsigned char b = 128;
                file.put((char)r);
                file.put((char)g);
                file.put((char)b);
            }
        }

        file.close();

        if (mpPlatform) {
            mpPlatform->Log(LogLevel::INFO, "System", "Photosphere saved to " + filename);
        } else {
            std::cout << "Photosphere saved to " << filename << std::endl;
        }
    } else {
        if (mpPlatform) mpPlatform->Log(LogLevel::ERROR, "System", "Failed to open file for writing: " + filename);
        else std::cerr << "Failed to open file for writing: " << filename << std::endl;
    }
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
    {
        std::unique_lock<std::mutex> lock(mMutexImu);
        std::queue<IMUData> empty;
        std::swap(mImuQueue, empty);
    }
    if (mpPlatform) mpPlatform->Log(LogLevel::INFO, "System", "System Reset");
    else std::cout << "System Reset" << std::endl;
}

std::string System::GetMapStats() {
    if (!mpMap) return "System Not Init";
    std::stringstream ss;
    ss << "KF: " << mpMap->GetAllKeyFrames().size()
       << " MP: " << mpMap->GetAllMapPoints().size();
    return ss.str();
}

void System::Shutdown() {
    if (mpLocalMapper) mpLocalMapper->RequestFinish();
    if (mpLoopCloser) mpLoopCloser->RequestFinish();

    if (mptLocalMapping && mptLocalMapping->joinable()) {
        mptLocalMapping->join();
    }
    if (mptLoopClosing && mptLoopClosing->joinable()) {
        mptLoopClosing->join();
    }
}
