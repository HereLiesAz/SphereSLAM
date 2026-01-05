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
    Profiler p("GrabImageCubeMap");

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

        // Check if lost (stub logic)
        // if (tracking_failed) mState = LOST;

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
        mpInitializer = new Initializer(mCurrentFrame, 1.0f, 200);
        return;
    }

    // Try to initialize
    std::vector<int> matches; // Stub matches
    cv::Mat R, t;
    std::vector<cv::Point3f> p3d;
    std::vector<bool> triangulated;

    // Fill dummy matches
    matches.resize(100, 1);

    if (mpInitializer->Initialize(mCurrentFrame, matches, R, t, p3d, triangulated)) {
        // Success
        mCurrentFrame.SetPose(cv::Mat::eye(4, 4, CV_32F));
        CreateNewKeyFrame();
        // Create MapPoints...

        mState = OK;
    }
}

bool Tracking::Relocalization() {
    // Stub Relocalization
    // Attempt to find matches with KeyFrameDatabase (Bag of Words)
    // For blueprint, we auto-recover after 1 frame
    // std::cout << "Tracking: Relocalization successful (Stub)" << std::endl;
    return true;
}

void Tracking::UpdateLastFrame() {
    // Save current frame as last frame
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
    // Clear other state...
}

Tracking::eTrackingState Tracking::GetState() {
    return mState;
}

void Tracking::SetState(eTrackingState state) {
    mState = state;
}
