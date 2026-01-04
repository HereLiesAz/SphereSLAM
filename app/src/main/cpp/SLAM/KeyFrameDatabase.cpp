#include "KeyFrameDatabase.h"

KeyFrameDatabase::KeyFrameDatabase() {
}

void KeyFrameDatabase::add(KeyFrame* pKF) {
    std::unique_lock<std::mutex> lock(mMutex);
    // Stub: mvInvertedFile.push_back(...)
}

void KeyFrameDatabase::erase(KeyFrame* pKF) {
    std::unique_lock<std::mutex> lock(mMutex);
    // Stub
}

void KeyFrameDatabase::clear() {
    std::unique_lock<std::mutex> lock(mMutex);
    mvInvertedFile.clear();
}

std::vector<KeyFrame*> KeyFrameDatabase::DetectLoopCandidates(KeyFrame* pKF, float minScore) {
    // Stub: Return empty candidates
    return std::vector<KeyFrame*>();
}
