#include "KeyFrame.h"

// Initialize static member
std::string KeyFrame::msCacheDir = "";

#include <opencv2/imgcodecs.hpp>
#include <iostream>

KeyFrame::KeyFrame(Frame &F, Map* pMap, KeyFrameDatabase* pKFDB)
    : mnFrameId(F.mnId), mTimeStamp(F.mTimeStamp), mpMap(pMap)
{
    mnId = F.mnId; // Using same ID for simplicity in blueprint
    mTcw = F.mTcw.clone();

    // Store Images to Disk to prevent OOM
    if (!msCacheDir.empty()) {
        for(size_t i=0; i<F.mImgs.size(); ++i) {
            std::stringstream ss;
            ss << "kf_" << mnId << "_" << i << ".jpg";
            std::string filename = ss.str();
            std::string fullPath = msCacheDir + "/" + filename;

            if (cv::imwrite(fullPath, F.mImgs[i])) {
                mImgFilenames.push_back(filename);
            } else {
                std::cerr << "KeyFrame: Failed to save image " << fullPath << std::endl;
            }
        }
    }

    // Store Intrinsics
    if (F.mpCamera) {
        mK = F.mpCamera->GetK();
    }

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
