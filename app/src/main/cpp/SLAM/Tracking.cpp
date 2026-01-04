#include "Tracking.h"
#include "Optimizer.h"
#include "Utils/Profiler.h"
#include <iostream>

Tracking::Tracking(System* pSys, GeometricCamera* pCam, Map* pMap, LocalMapping* pLM)
    : mpSystem(pSys), mpCamera(pCam), mpMap(pMap), mpLocalMapper(pLM), mState(NO_IMAGES_YET) {

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
        // Initialization Logic
        mCurrentFrame.SetPose(cv::Mat::eye(4, 4, CV_32F));

        // Create initial KeyFrame
        CreateNewKeyFrame();

        mState = OK;
        return;
    }

    if (mState == OK) {
        // Propagate pose (Motion Model)
        if (!mLastFrame.mTcw.empty()) {
            mCurrentFrame.SetPose(mLastFrame.mTcw);
        } else {
             mCurrentFrame.SetPose(cv::Mat::eye(4, 4, CV_32F));
        }

        // Optimize Pose
        Optimizer::PoseOptimization(&mCurrentFrame);

        // Simple Keyframe insertion policy (every 10 frames for blueprint)
        static int frameCount = 0;
        if (frameCount++ % 10 == 0) {
            CreateNewKeyFrame();
        }
    }

    UpdateLastFrame();
}

void Tracking::UpdateLastFrame() {
    // Save current frame as last frame
    mLastFrame = Frame(mCurrentFrame);
}

void Tracking::CreateNewKeyFrame() {
    KeyFrame* pKF = new KeyFrame(mCurrentFrame, mpMap, nullptr);
    mpLocalMapper->InsertKeyFrame(pKF);
}
