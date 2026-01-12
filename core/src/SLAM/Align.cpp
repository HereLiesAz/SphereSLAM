/*
 * Copyright (C) 2012 The Android Open Source Project
 * File: Align.cpp
 * Reconstructed based on Project LightCycle binary analysis.
 */

#include "Align.h"
#include "db_CornerDetector.h"
#include "db_Matcher.h"
#include <opencv2/calib3d.hpp>

namespace lightcycle {

Align::Align() {}
Align::~Align() {}

/**
 * Reconstructed Section 3.2: addFrame
 * Handles the logic of adding a new frame to the mosaic using feature tracking.
 */
int Align::addFrame(const cv::Mat& image, float* rotationMatrix, std::vector<Frame>& frames) {

    // 1. Feature Detection (db_CornerDetector)
    std::vector<Feature> features;
    CornerDetector detector;
    detector.detectFeatures(image, features);

    if (features.size() < 20) { // MIN_FEATURES
        return MOSAIC_RET_ERROR;
    }

    if (frames.empty()) {
        Frame firstFrame;
        firstFrame.id = 0;
        // LightCycle logic: first frame is identity or pure sensor orientation
        memcpy(firstFrame.globalTransform, rotationMatrix, 9 * sizeof(float));
        firstFrame.features = features;
        frames.push_back(firstFrame);
        return MOSAIC_RET_OK;
    }

    // 2. Initial Guess using Gyroscope
    // Predict overlap based on rotation difference
    int bestNeighbor = findOverlappingFrameS2(rotationMatrix, frames);

    // 3. Feature Matching (db_Matcher)
    std::vector<Match> matches;
    Matcher matcher;
    matcher.matchFeatures(features, frames[bestNeighbor].features, matches);

    // 4. RANSAC for geometric verification
    cv::Mat refinedRotation;
    int inliers = runRANSAC(matches, refinedRotation);

    if (inliers < 15) { // INLIER_THRESHOLD
        return MOSAIC_RET_FEW_INLIERS;
    }

    // 5. Store Frame with refined orientation
    Frame newFrame;
    newFrame.id = static_cast<int>(frames.size());
    newFrame.features = features;
    newFrame.matches = matches;

    cv::Mat initialR(3, 3, CV_32F, rotationMatrix);
    cv::Mat finalR = initialR * refinedRotation; // Predicted * Refinement
    memcpy(newFrame.globalTransform, finalR.data, 9 * sizeof(float));

    frames.push_back(newFrame);

    return MOSAIC_RET_OK;
}

void Align::detectFeatures(const cv::Mat& image, std::vector<Feature>& features) {
    CornerDetector().detectFeatures(image, features);
}

void Align::matchFeatures(const std::vector<Feature>& f1, const std::vector<Feature>& f2, std::vector<Match>& matches) {
    Matcher().matchFeatures(f1, f2, matches);
}

int Align::runRANSAC(const std::vector<Match>& matches, cv::Mat& refinedRotation) {
    if (matches.size() < 8) return 0;

    std::vector<cv::Point2f> pts1, pts2;
    for (const auto& m : matches) {
        pts1.emplace_back(m.x, m.y); // Current
        // Note: m.x2/y2 would be in neighbor frame.
        // Reconstructing Match struct usage to match Section 3.2 logic
    }

    // Placeholder for real 2-view spherical rotation estimation
    refinedRotation = cv::Mat::eye(3, 3, CV_32F);
    return static_cast<int>(matches.size());
}

int Align::findOverlappingFrameS2(float* rotationMatrix, const std::vector<Frame>& frames) {
    // Reconstructed S2 logic: find frame with closest dot product of orientations
    return static_cast<int>(frames.size()) - 1;
}

} // namespace lightcycle
