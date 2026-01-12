#include "Tracking.h"
#include "Optimizer.h"
#include "Utils/Profiler.h"
#include <iostream>

Tracking::Tracking(System* pSys, GeometricCamera* pCam, Map* pMap, LocalMapping* pLM)
    : mpSystem(pSys), mpCamera(pCam), mpMap(pMap), mpLocalMapper(pLM), mState(NO_IMAGES_YET), mpInitializer(nullptr) {

    // Initialize ORB Extractor
    // nFeatures, scaleFactor, nLevels, iniThFAST, minThFAST
    mpORBextractor = new ORBextractor(1000, 1.2f, 8, 20, 7);
}

cv::Mat Tracking::GrabImageCubeMap(const std::vector<cv::Mat>& faces, const double& timestamp) {
    SphereSLAM::Profiler p("GrabImageCubeMap");

    // 1. Create Frame
    mCurrentFrame = Frame(faces, timestamp, mpORBextractor, mpCamera);

    // 2. Track
    Track();

    return mCurrentFrame.mTcw.clone();
}

void Tracking::Track() {
    if (mState == NO_IMAGES_YET) {
        mState = NOT_INITIALIZED;
    }

    if (mState == NOT_INITIALIZED) {
        MonocularInitialization();
        return;
    }

    if (mState == OK) {
        // Propagate pose (Motion Model)
        if (!mLastFrame.mTcw.empty()) {
            mCurrentFrame.SetPose(mLastFrame.mTcw);
        } else {
             mCurrentFrame.SetPose(cv::Mat::eye(4, 4, CV_32F));
        }

        // Track Local Map / Optimize Pose
        Optimizer::PoseOptimization(&mCurrentFrame);

        // Simple Keyframe insertion policy (every 10 frames for blueprint)
        static int frameCount = 0;
        if (frameCount++ % 10 == 0) {
            CreateNewKeyFrame();
        }
    } else if (mState == LOST) {
        if (Relocalization()) {
            mState = OK;
        }
    }

    UpdateLastFrame();
}

void Tracking::MonocularInitialization() {
    if (!mpInitializer) {
        // Set Reference Frame
        if (mCurrentFrame.mvKeys.size() > 0 && mCurrentFrame.mvKeys[0].size() > 100) {
            mpInitializer = new Initializer(mCurrentFrame, 1.0f, 200);
        }
        return;
    }

    // Try to initialize
    // We need real matches here for the initializer to work.
    // For the blueprint, we'll simulate a perfect identity match if the camera hasn't moved much.
    // But importantly, the size must match the number of keypoints in the INITIAL frame.

    // Initializer stores mInitialFrame.
    // matches vector size must be equal to mInitialFrame.mvKeys[0].size()

    const Frame& initialFrame = mpInitializer->GetReferenceFrame();
    if (initialFrame.mvKeys.empty() || initialFrame.mvKeys[0].empty()) return;

    std::vector<int> matches(initialFrame.mvKeys[0].size(), -1);

    // Simple proximity match for simulation
    // In a real system, we'd use ORB matcher here.
    const std::vector<cv::KeyPoint>& keys1 = initialFrame.mvKeys[0];
    const std::vector<cv::KeyPoint>& keys2 = mCurrentFrame.mvKeys[0];

    for(size_t i=0; i<keys1.size(); ++i) {
        // For simulation, match same index if within 5 pixels
        if (i < keys2.size()) {
             float dist = cv::norm(keys1[i].pt - keys2[i].pt);
             if (dist < 10.0f) {
                 matches[i] = i;
             }
        }
    }

    cv::Mat R, t;
    std::vector<cv::Point3f> p3d;
    std::vector<bool> triangulated;

    if (mpInitializer->Initialize(mCurrentFrame, matches, R, t, p3d, triangulated)) {
        // Success
        mCurrentFrame.SetPose(cv::Mat::eye(4, 4, CV_32F));
        CreateNewKeyFrame();
        mState = OK;
        std::cout << "Tracking: Monocular Initialization successful" << std::endl;
    }
}

bool Tracking::Relocalization() {
    return true;
}

void Tracking::UpdateLastFrame() {
    mLastFrame = Frame(mCurrentFrame);
}

void Tracking::CreateNewKeyFrame() {
    KeyFrame* pKF = new KeyFrame(mCurrentFrame, mpMap, nullptr);
    mpLocalMapper->InsertKeyFrame(pKF);
}

void Tracking::Reset() {
    mState = NO_IMAGES_YET;
    if (mpInitializer) {
        delete mpInitializer;
        mpInitializer = nullptr;
    }
}

Tracking::eTrackingState Tracking::GetState() {
    return mState;
}

void Tracking::SetState(eTrackingState state) {
    mState = state;
}
