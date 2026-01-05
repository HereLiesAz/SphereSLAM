#ifndef KEYFRAMEDATABASE_H
#define KEYFRAMEDATABASE_H

#include <vector>
#include <list>
#include <set>
#include <mutex>
#include "KeyFrame.h"

class KeyFrameDatabase {
public:
    KeyFrameDatabase();

    void add(KeyFrame* pKF);
    void erase(KeyFrame* pKF);
    void clear();

    // Stub for detection
    std::vector<KeyFrame*> DetectLoopCandidates(KeyFrame* pKF, float minScore);

protected:
    // In real implementation, this holds the Inverted Index
    std::vector<std::list<KeyFrame*>> mvInvertedFile;
    std::mutex mMutex;
};

#endif // KEYFRAMEDATABASE_H
