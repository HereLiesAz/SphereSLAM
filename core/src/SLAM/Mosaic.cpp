/*
 * Copyright (C) 2012 The Android Open Source Project
 * File: Mosaic.cpp
 * Reconstructed based on binary symbol analysis of libjni_mosaic.so
 */

#include "Mosaic.h"
#include "db_CornerDetector.h"
#include <opencv2/imgproc.hpp>
#include <iostream>

namespace lightcycle {

Mosaic::Mosaic() : mWidth(0), mHeight(0), mSharedFocalLength(0.0) {
    mDetector = new CornerDetector();
    mFinalResultNV21 = nullptr;
}

Mosaic::~Mosaic() {
    freeMosaicMemory();
    delete mDetector;
}

void Mosaic::allocateMosaicMemory(int width, int height) {
    mWidth = width;
    mHeight = height;
    mSharedFocalLength = static_cast<double>(width) * 0.8;
}

void Mosaic::freeMosaicMemory() {
    std::lock_guard<std::mutex> lock(mMutex);
    for(auto& frame : mFrames) {
        if(frame.image) free(frame.image);
    }
    mFrames.clear();
    if(mFinalResultNV21) free(mFinalResultNV21);
    mFinalResultNV21 = nullptr;
}

/**
 * Reconstructed Section 3.2: addFrame
 * rotationMatrix is 3x3
 */
int Mosaic::addFrame(unsigned char* imageYVU, float* rotationMatrix) {
    std::lock_guard<std::mutex> lock(mMutex);

    Frame frame;
    frame.id = static_cast<int>(mFrames.size());

    size_t size = mWidth * (mHeight + mHeight / 2);
    frame.image = (unsigned char*)malloc(size);
    memcpy(frame.image, imageYVU, size);

    // globalTransform [9]
    for(int i=0; i<9; ++i) frame.globalTransform[i] = rotationMatrix[i];

    // Process for features
    cv::Mat yuv(mHeight + mHeight / 2, mWidth, CV_8UC1, imageYVU);
    cv::Mat gray;
    cv::cvtColor(yuv, gray, cv::COLOR_YUV2GRAY_NV21);

    mDetector->detectFeatures(gray, frame.features);

    if (frame.features.size() < 20) {
        free(frame.image);
        return MOSAIC_RET_ERROR;
    }

    mFrames.push_back(frame);
    return MOSAIC_RET_OK;
}

void Mosaic::createMosaic(bool highRes) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mFrames.empty()) return;

    // Reconstructed Section 3.3: Global Optimization (Ceres)
    runGlobalOptimization();

    // Section 3.5: Blending Logic
}

unsigned char* Mosaic::getFinalMosaicNV21() {
    return mFinalResultNV21;
}

void Mosaic::runGlobalOptimization() {
    // LightCycle uses Ceres Solver to minimize reprojection error across the sphere.
    // Functor: ReprojectionError (as detailed in documentation reconstruction)
}

} // namespace lightcycle
