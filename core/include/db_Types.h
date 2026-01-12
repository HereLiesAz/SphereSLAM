/*
 * Copyright (C) 2012 The Android Open Source Project
 * Common types for the Mosaic pipeline.
 */

#ifndef DB_TYPES_H
#define DB_TYPES_H

#include <vector>

namespace lightcycle {

// Return codes observed in debug strings
enum MosaicStatus {
    MOSAIC_RET_OK = 0,
    MOSAIC_RET_ERROR = -1,
    MOSAIC_RET_FEW_INLIERS = -2
};

struct Feature {
    float x, y;
};

struct Match {
    float x1, y1;
    float x2, y2;
    float point_3d[3];
};

struct Frame {
    int id;
    int width, height; // Added dimensions
    unsigned char* image;
    float globalTransform[9]; // 3x3 rotation matrix
    std::vector<Feature> features;
    std::vector<Match> matches;
};

} // namespace lightcycle

#endif // DB_TYPES_H
