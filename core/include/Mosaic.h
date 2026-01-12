/*
 * Copyright (C) 2012 The Android Open Source Project
 * File: Mosaic.h
 * Reconstructed based on binary symbol analysis of libjni_mosaic.so
 */

#ifndef MOSAIC_H
#define MOSAIC_H

#include <vector>
#include <mutex>
#include <string>
#include "db_CornerDetector.h"

namespace lightcycle {

// Return codes observed in debug strings
enum MosaicStatus {
    MOSAIC_RET_OK = 0,
    MOSAIC_RET_ERROR = -1,
    MOSAIC_RET_FEW_INLIERS = -2
};

/**
 * Orchestrates the stitching pipeline.
 */
class Mosaic {
public:
    Mosaic();
    ~Mosaic();

    // Initialization
    void allocateMosaicMemory(int width, int height);
    void freeMosaicMemory();

    // Core Capture Loop
    // rotationMatrix is the 3x3 orientation from Gyroscope
    int addFrame(unsigned char* imageYVU, float* rotationMatrix);

    // Final Stitching
    // Uses Ceres Solver for bundle adjustment
    void createMosaic(bool highRes, const std::string& path = "");

    // Rendering
    // Retrieves the Equirectangular projection
    unsigned char* getFinalMosaicNV21();

private:
    void runGlobalOptimization();

    int mWidth, mHeight;
    double mSharedFocalLength;

    // Internal Modules identified in symbols
    class Align* mAligner;
    class Blend* mBlender;
    class CDelaunay* mMesher;
    class CornerDetector* mDetector;
    class Optimizer* mOptimizer;

    // Frame storage (Section 3.1)
    std::vector<Frame> mFrames;
    std::mutex mMutex;

    unsigned char* mFinalResultNV21;
};

} // namespace lightcycle

#endif // MOSAIC_H
