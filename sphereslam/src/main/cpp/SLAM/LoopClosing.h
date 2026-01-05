#ifndef LOOPCLOSING_H
#define LOOPCLOSING_H

#include "KeyFrame.h"
#include "KeyFrameDatabase.h"
#include "Map.h"
#include "LocalMapping.h"
#include <list>
#include <mutex>
#include <thread>

class System;

class LoopClosing {
public:
    LoopClosing(System* pSys, Map* pMap, KeyFrameDatabase* pDB, bool bFixScale = false);

    void Run();

    void InsertKeyFrame(KeyFrame* pKF);
    void RequestFinish();
    bool isFinished();

protected:
    bool CheckNewKeyFrames();
    bool DetectLoop();
    bool ComputeSim3();
    void CorrectLoop();

    System* mpSystem;
    Map* mpMap;
    KeyFrameDatabase* mpKeyFrameDatabase;
    LocalMapping* mpLocalMapper;

    std::list<KeyFrame*> mlpLoopKeyFrameQueue;

    std::mutex mMutexLoopQueue;
    std::mutex mMutexFinish;

    bool mbFinishRequested;
    bool mbFinished;
};

#endif // LOOPCLOSING_H
