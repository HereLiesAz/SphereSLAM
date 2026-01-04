#include "LocalMapping.h"
#include "LoopClosing.h"
#include <unistd.h>

LocalMapping::LocalMapping(System* pSys, Map* pMap)
    : mpSystem(pSys), mpMap(pMap), mpLoopCloser(nullptr), mbFinishRequested(false), mbFinished(true)
{
}

void LocalMapping::SetLoopCloser(LoopClosing* pLoopCloser) {
    mpLoopCloser = pLoopCloser;
}

void LocalMapping::Run() {
    mbFinished = false;

    while(1) {
        bool bNewKeyFrames = false;

        {
            std::unique_lock<std::mutex> lock(mMutexNewKFs);
            bNewKeyFrames = !mlNewKeyFrames.empty();
        }

        if (bNewKeyFrames) {
            // Process new KeyFrame
            ProcessNewKeyFrame();

            // Create MapPoints (Triangulation)
            MapPointCreation();

            // Search neighbors
            SearchInNeighbors();
        } else {
            // Sleep to avoid busy wait
             usleep(3000);
        }

        // Check finish
        {
            std::unique_lock<std::mutex> lock(mMutexFinish);
            if(mbFinishRequested && mlNewKeyFrames.empty())
                break;
        }
    }

    mbFinished = true;
}

void LocalMapping::InsertKeyFrame(KeyFrame* pKF) {
    std::unique_lock<std::mutex> lock(mMutexNewKFs);
    mlNewKeyFrames.push_back(pKF);
}

void LocalMapping::ProcessNewKeyFrame() {
    // 1. Get KF from queue
    KeyFrame* pKF;
    {
        std::unique_lock<std::mutex> lock(mMutexNewKFs);
        pKF = mlNewKeyFrames.front();
        mlNewKeyFrames.pop_front();
    }

    // 2. Compute BoW (if used)

    // 3. Insert into Map
    mpMap->AddKeyFrame(pKF);

    // 4. Update Connections

    // 5. Send to LoopClosing
    if (mpLoopCloser) {
        mpLoopCloser->InsertKeyFrame(pKF);
    }
}

void LocalMapping::MapPointCreation() {
    // Stub
}

void LocalMapping::SearchInNeighbors() {
    // Stub
}

void LocalMapping::RequestFinish() {
    std::unique_lock<std::mutex> lock(mMutexFinish);
    mbFinishRequested = true;
}

bool LocalMapping::isFinished() {
    std::unique_lock<std::mutex> lock(mMutexFinish);
    return mbFinished;
}
