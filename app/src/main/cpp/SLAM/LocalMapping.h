#ifndef LOCALMAPPING_H
#define LOCALMAPPING_H

#include "KeyFrame.h"
#include "Map.h"
#include <list>
#include <mutex>
#include <thread>

class System;
class LoopClosing;

class LocalMapping {
public:
    LocalMapping(System* pSys, Map* pMap);

    // Set Loop Closer
    void SetLoopCloser(LoopClosing* pLoopCloser);

    // Main loop
    void Run();

    // Interface
    void InsertKeyFrame(KeyFrame* pKF);
    void RequestFinish();
    bool isFinished();

    // Thread management
    void SetAcceptKeyFrames(bool flag);
    bool SetNotStop(bool flag);

protected:
    void ProcessNewKeyFrame();
    void MapPointCreation();
    void SearchInNeighbors();
    void KeyFrameCulling(); // New: Culling

    bool CheckNewKeyFrames();

    std::list<KeyFrame*> mlNewKeyFrames;

    Map* mpMap;
    System* mpSystem;
    LoopClosing* mpLoopCloser;

    bool mbFinishRequested;
    bool mbFinished;
    std::mutex mMutexNewKFs;
    std::mutex mMutexFinish;
};

#endif // LOCALMAPPING_H
