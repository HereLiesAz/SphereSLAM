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

    // Store Image
    mImgs.push_back(imGray.clone());

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

    // Store Images
    for(const auto& img : faceImgs) {
        mImgs.push_back(img.clone());
    }

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
    // T = [R | t]
    // T_inv = [R^T | -R^T * t]

    if (mTcw.empty()) return cv::Mat::eye(4,4, CV_32F);

    cv::Mat Rwc = mTcw.rowRange(0,3).colRange(0,3).t();
    cv::Mat twc = -Rwc * mTcw.rowRange(0,3).col(3);

    cv::Mat Twc = cv::Mat::eye(4,4, CV_32F);
    // Copy Rwc
    Rwc.copyTo(Twc.rowRange(0,3).colRange(0,3));
    // Copy twc
    twc.copyTo(Twc.rowRange(0,3).col(3));

    return Twc;
}
