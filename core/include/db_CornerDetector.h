/*
 * Copyright (C) 2012 The Android Open Source Project
 * Reconstructed based on Project LightCycle binary analysis.
 */

#ifndef DB_CORNERDETECTOR_H
#define DB_CORNERDETECTOR_H

#include <vector>
#include <opencv2/core.hpp>

namespace lightcycle {

struct Feature {
    float x, y;
};

class CornerDetector {
public:
    CornerDetector();
    ~CornerDetector();

    void detectFeatures(const cv::Mat& image, std::vector<Feature>& features);
};

} // namespace lightcycle

#endif // DB_CORNERDETECTOR_H
