/*
 * Copyright (C) 2012 The Android Open Source Project
 * File: db_Matcher.cpp
 * Reconstructed based on Project LightCycle binary analysis.
 */

#include "db_Matcher.h"
#include <opencv2/imgproc.hpp>
#include <cmath>

namespace lightcycle {

Matcher::Matcher() {}
Matcher::~Matcher() {}

/**
 * Reconstructed Section 3.2: matchFeatures
 * Implements proximity-based feature correlation.
 * Matches features from the current frame (f1) against a candidate overlapping frame (f2).
 */
void Matcher::matchFeatures(const std::vector<Feature>& f1,
                            const std::vector<Feature>& f2,
                            std::vector<Match>& matches) {
    matches.clear();

    // LightCycle used a search radius around the predicted position from sensors
    const float searchRadiusSq = 400.0f; // 20px radius

    for (const auto& p1 : f1) {
        float bestDistSq = searchRadiusSq;
        const Feature* bestMatch = nullptr;

        for (const auto& p2 : f2) {
            float dx = p1.x - p2.x;
            float dy = p1.y - p2.y;
            float d2 = dx * dx + dy * dy;

            if (d2 < bestDistSq) {
                bestDistSq = d2;
                bestMatch = &p2;
            }
        }

        if (bestMatch != nullptr) {
            Match m;
            m.x1 = p1.x; m.y1 = p1.y;
            m.x2 = bestMatch->x; m.y2 = bestMatch->y;
            // 3D point initialized to zero, will be triangulated in bundle adjustment
            m.point_3d[0] = 0; m.point_3d[1] = 0; m.point_3d[2] = 0;
            matches.push_back(m);
        }
    }
}

} // namespace lightcycle
