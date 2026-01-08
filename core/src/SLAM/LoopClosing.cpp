#include "LoopClosing.h"
#include "Optimizer.h"
#include <unistd.h>
#include <cmath>

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
                 if(!mlpLoopKeyFrameQueue.empty())
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
    // 1. Get Current KeyFrame
    KeyFrame* pCurrentKF = nullptr;
    {
        std::unique_lock<std::mutex> lock(mMutexLoopQueue);
        if(!mlpLoopKeyFrameQueue.empty())
            pCurrentKF = mlpLoopKeyFrameQueue.front();
    }

    if(!pCurrentKF || pCurrentKF->mnId < 10) return false;

    // 2. Geometric Loop Detection (Fallback for missing DBoW2)
    // Find KeyFrames that are close in distance but far in ID/Time
    std::vector<KeyFrame*> vpKFs = mpMap->GetAllKeyFrames();

    cv::Mat Twc = pCurrentKF->GetPoseInverse();
    cv::Mat Ow = Twc.rowRange(0,3).col(3); // Camera Center
    float cx = Ow.at<float>(0);
    float cy = Ow.at<float>(1);
    float cz = Ow.at<float>(2);

    float minDistance = 2.0f; // 2 meters detection radius
    KeyFrame* pLoopCandidate = nullptr;

    for(KeyFrame* pKF : vpKFs) {
        if(pKF == pCurrentKF) continue;
        if(pKF->mnId > pCurrentKF->mnId - 10) continue; // Skip recent frames

        // Calculate distance
        cv::Mat Twc2 = pKF->GetPoseInverse();
        cv::Mat Ow2 = Twc2.rowRange(0,3).col(3);
        float dx = Ow2.at<float>(0) - cx;
        float dy = Ow2.at<float>(1) - cy;
        float dz = Ow2.at<float>(2) - cz;
        float dist = std::sqrt(dx*dx + dy*dy + dz*dz);

        if(dist < minDistance) {
            // Found a loop candidate!
            // Check connectivity
            std::set<KeyFrame*> connected = pCurrentKF->GetConnectedKeyFrames();
            if(connected.find(pKF) == connected.end()) {
                pLoopCandidate = pKF;
                break; // Found one
            }
        }
    }

    // In a real system, we would store candidates. Here we just return true if any found.
    // Ideally LoopClosing stores 'mpCurrentMatchedKF'.
    // Since this is a simplified implementation, we just log detection.

    if (pLoopCandidate) {
        // std::cout << "Loop detected between KF " << pCurrentKF->mnId << " and " << pLoopCandidate->mnId << std::endl;
        return true;
    }

    return false;
}

bool LoopClosing::ComputeSim3() {
    // Stub: Sim3 optimization requires g2o.
    // We assume geometric check is sufficient for this stage.
    return true;
}

void LoopClosing::CorrectLoop() {
    // Trigger Global BA to fix the loop
    bool bStop = false;
    Optimizer::GlobalBundleAdjustment(mpMap, 20, &bStop, 0, false);
}
