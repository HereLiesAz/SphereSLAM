/*
 * Copyright (C) 2012 The Android Open Source Project
 * File: db_Matcher.h
 * Reconstructed based on binary symbol analysis of libjni_mosaic.so
 */

#ifndef DB_MATCHER_H
#define DB_MATCHER_H

#include <vector>
#include "Mosaic.h"

namespace lightcycle {

class Matcher {
public:
    Matcher();
    ~Matcher();

    /**
     * Section 3.2: Reconstructed matchFeatures
     * Matches features between two frames using proximity and descriptor similarity.
     */
    void matchFeatures(const std::vector<Feature>& f1,
                       const std::vector<Feature>& f2,
                       std::vector<Match>& matches);
};

} // namespace lightcycle

#endif // DB_MATCHER_H
