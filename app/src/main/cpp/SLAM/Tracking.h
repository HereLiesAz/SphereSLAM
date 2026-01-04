#ifndef TRACKING_H
#define TRACKING_H

#include <opencv2/core/core.hpp>
#include <mutex>
#include "Frame.h"
#include "GeometricCamera.h"
#include "ORBextractor.h"

class System;

class Tracking {
public:
    enum eTrackingState {
        SYSTEM_NOT_READY=-1,
        NO_IMAGES_YET=0,
        NOT_INITIALIZED=1,
        OK=2,
        LOST=3
    };

    Tracking(System* pSys, GeometricCamera* pCam);

    // Main tracking function for CubeMap
    cv::Mat GrabImageCubeMap(const std::vector<cv::Mat>& faces, const double& timestamp);

    void SetState(eTrackingState state);
    eTrackingState GetState();

public:
    eTrackingState mState;

    // Current Frame
    Frame mCurrentFrame;
    Frame mLastFrame;

    // Camera
    GeometricCamera* mpCamera;

    // ORB Extractor
    ORBextractor* mpORBextractor;

    // System
    System* mpSystem;

    // Pose
    cv::Mat mVelocity;

private:
    void Track();
    bool TrackReferenceKeyFrame();
    bool TrackWithMotionModel();
    bool Relocalization();

    void UpdateLastFrame();
};

#endif // TRACKING_H
