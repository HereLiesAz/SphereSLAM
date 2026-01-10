#include "KeyFrame.h"

KeyFrame::KeyFrame(Frame &F, Map* pMap, KeyFrameDatabase* pKFDB)
    : mnFrameId(F.mnId), mTimeStamp(F.mTimeStamp), mpMap(pMap)
{
    mnId = F.mnId; // Using same ID for simplicity in blueprint
    mTcw = F.mTcw.clone();

    mvpMapPoints = std::vector<MapPoint*>(F.N, nullptr);
}

KeyFrame::KeyFrame(long unsigned int id, double timeStamp, const cv::Mat &Tcw, Map* pMap)
    : mnId(id), mnFrameId(id), mTimeStamp(timeStamp), mpMap(pMap)
{
    this->mTcw = Tcw.clone();
    // No Frame reference, so no features or map points initialization from Frame
    if (mnId >= Frame::nNextId) {
        Frame::nNextId = mnId + 1;
    }
}

void KeyFrame::SetPose(const cv::Mat &Tcw) {
    std::unique_lock<std::mutex> lock(mMutexPose);
    mTcw = Tcw.clone();
}

cv::Mat KeyFrame::GetPose() {
    std::unique_lock<std::mutex> lock(mMutexPose);
    return mTcw.clone();
}

cv::Mat KeyFrame::GetPoseInverse() {
    std::unique_lock<std::mutex> lock(mMutexPose);
    // T = [R | t]
    // T_inv = [R^T | -R^T * t]

    cv::Mat Rwc = mTcw.rowRange(0,3).colRange(0,3).t();
    cv::Mat twc = -Rwc * mTcw.rowRange(0,3).col(3);

    cv::Mat Twc = cv::Mat::eye(4,4, CV_32F);
    // Copy Rwc
    Rwc.copyTo(Twc.rowRange(0,3).colRange(0,3));
    // Copy twc
    twc.copyTo(Twc.rowRange(0,3).col(3));

    return Twc;
}

void KeyFrame::AddConnection(KeyFrame* pKF, const int &weight) {
    // Stub
}

std::set<KeyFrame*> KeyFrame::GetConnectedKeyFrames() {
    return mspConnectedKeyFrames;
}
