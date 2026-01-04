#include "KeyFrame.h"

KeyFrame::KeyFrame(Frame &F, Map* pMap, KeyFrameDatabase* pKFDB)
    : mnFrameId(F.mnId), mTimeStamp(F.mTimeStamp), mpMap(pMap)
{
    mnId = F.mnId; // Using same ID for simplicity in blueprint
    mTcw = F.mTcw.clone();

    mvpMapPoints = std::vector<MapPoint*>(F.N, static_cast<MapPoint*>(NULL));
}

void KeyFrame::SetPose(const cv::Mat &Tcw) {
    std::unique_lock<std::mutex> lock(mMutexPose);
    mTcw = Tcw.clone();
}

cv::Mat KeyFrame::GetPose() {
    std::unique_lock<std::mutex> lock(mMutexPose);
    return mTcw.clone();
}

void KeyFrame::AddConnection(KeyFrame* pKF, const int &weight) {
    // Stub
}

std::set<KeyFrame*> KeyFrame::GetConnectedKeyFrames() {
    return mspConnectedKeyFrames;
}
