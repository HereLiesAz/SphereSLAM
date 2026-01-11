#ifndef FRAME_H
#define FRAME_H

#include <vector>
#include <opencv2/core.hpp>
#include "GeometricCamera.h"
#include "ORBextractor.h"

class Frame {
public:
    Frame();

    // Copy Constructor
    Frame(const Frame &frame);

    // Constructor for Monocular/CubeMap
    Frame(const cv::Mat &imGray, const double &timeStamp, ORBextractor* extractor, GeometricCamera* camera);

    // Constructor for explicit CubeMap (6 images)
    Frame(const std::vector<cv::Mat> &faceImgs, const double &timeStamp, ORBextractor* extractor, GeometricCamera* camera);

    // Destructor
    ~Frame() {}

    void ExtractORB(int flag, const cv::Mat &im);
    void SetPose(cv::Mat Tcw);
    cv::Mat GetPoseInverse();

public:
    // Frame Metadata
    long unsigned int mnId;
    double mTimeStamp;
    static long unsigned int nNextId;

    // Camera
    GeometricCamera* mpCamera;

    // Features (Vector of vectors for multi-camera/cubemap)
    std::vector<std::vector<cv::KeyPoint>> mvKeys;
    std::vector<cv::Mat> mDescriptors;

    // Number of features
    int N;

    // Pose (World to Camera)
    cv::Mat mTcw;

    // Source Images (Color) - Stored for Photosphere Creation
    std::vector<cv::Mat> mImgs;

private:
    ORBextractor* mpORBextractor;
};

#endif // FRAME_H
