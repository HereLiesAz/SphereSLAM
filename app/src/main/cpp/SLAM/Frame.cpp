#include "Frame.h"

long unsigned int Frame::nNextId = 0;

Frame::Frame() {
}

Frame::Frame(const Frame &frame)
    : mnId(frame.mnId), mTimeStamp(frame.mTimeStamp), mpCamera(frame.mpCamera),
      mvKeys(frame.mvKeys), mDescriptors(frame.mDescriptors), N(frame.N), mTcw(frame.mTcw.clone()),
      mpORBextractor(frame.mpORBextractor)
{
}

Frame::Frame(const cv::Mat &imGray, const double &timeStamp, ORBextractor* extractor, GeometricCamera* camera)
    : mTimeStamp(timeStamp), mpORBextractor(extractor), mpCamera(camera)
{
    mnId = nNextId++;

    // Single image case
    std::vector<cv::KeyPoint> keys;
    cv::Mat descriptors;

    (*mpORBextractor)(imGray, cv::Mat(), keys, descriptors);

    mvKeys.push_back(keys);
    mDescriptors.push_back(descriptors);
    N = keys.size();
}

Frame::Frame(const std::vector<cv::Mat> &faceImgs, const double &timeStamp, ORBextractor* extractor, GeometricCamera* camera)
    : mTimeStamp(timeStamp), mpORBextractor(extractor), mpCamera(camera)
{
    mnId = nNextId++;
    N = 0;

    // Extract features for each face
    for (size_t i = 0; i < faceImgs.size(); ++i) {
        std::vector<cv::KeyPoint> keys;
        cv::Mat descriptors;

        (*mpORBextractor)(faceImgs[i], cv::Mat(), keys, descriptors);

        mvKeys.push_back(keys);
        mDescriptors.push_back(descriptors);
        N += keys.size();
    }
}

void Frame::SetPose(cv::Mat Tcw) {
    mTcw = Tcw.clone();
}

cv::Mat Frame::GetPoseInverse() {
    // Rigid body transformation inverse
    // R^T | -R^T * t
    // 0   | 1

    // Stub:
    return cv::Mat::eye(4,4, CV_32F);
}
