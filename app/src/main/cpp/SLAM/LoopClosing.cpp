#include "LoopClosing.h"
#include "Optimizer.h"
#include <unistd.h>

LoopClosing::LoopClosing(System* pSys, Map* pMap, KeyFrameDatabase* pDB, bool bFixScale)
    : mpSystem(pSys), mpMap(pMap), mpKeyFrameDatabase(pDB), mbFinishRequested(false), mbFinished(true)
{
}

void LoopClosing::Run() {
    mbFinished = false;

    while(1) {
        bool bNewKeyFrames = false;

        {
            std::unique_lock<std::mutex> lock(mMutexLoopQueue);
            bNewKeyFrames = !mlpLoopKeyFrameQueue.empty();
        }

        if (bNewKeyFrames) {
            // Process Loop
            if (DetectLoop()) {
                if (ComputeSim3()) {
                    CorrectLoop();
                }
            }

            // Pop queue
            {
                 std::unique_lock<std::mutex> lock(mMutexLoopQueue);
                 mlpLoopKeyFrameQueue.pop_front();
            }
        } else {
            usleep(5000);
        }

        // Check finish
        {
            std::unique_lock<std::mutex> lock(mMutexFinish);
            if(mbFinishRequested && mlpLoopKeyFrameQueue.empty())
                break;
        }
    }

    mbFinished = true;
}

void LoopClosing::InsertKeyFrame(KeyFrame* pKF) {
    std::unique_lock<std::mutex> lock(mMutexLoopQueue);
    mlpLoopKeyFrameQueue.push_back(pKF);
}

void LoopClosing::RequestFinish() {
    std::unique_lock<std::mutex> lock(mMutexFinish);
    mbFinishRequested = true;
}

bool LoopClosing::isFinished() {
    std::unique_lock<std::mutex> lock(mMutexFinish);
    return mbFinished;
}

bool LoopClosing::DetectLoop() {
    // Stub
    return false;
}

bool LoopClosing::ComputeSim3() {
    // Stub
    return false;
}

void LoopClosing::CorrectLoop() {
    // Stub
    bool bStop = false;
    Optimizer::GlobalBundleAdjustment(mpMap, 20, &bStop, 0, false);
}
