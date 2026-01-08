#ifndef INITIALIZER_H
#define INITIALIZER_H

#include <vector>
#include <opencv2/core.hpp>
#include "Frame.h"

class Initializer {
public:
    Initializer(const Frame &ReferenceFrame, float sigma, int iterations);

    // Returns true if initialization is successful
    bool Initialize(const Frame &CurrentFrame, const std::vector<int> &vMatches12,
                    cv::Mat &R21, cv::Mat &t21, std::vector<cv::Point3f> &vP3D,
                    std::vector<bool> &vbTriangulated);

private:
    Frame mInitialFrame;
    float mSigma;
    int mMaxIterations;

    std::vector<cv::Point2f> mvKeys1;
    std::vector<cv::Point2f> mvKeys2;

    std::vector<std::vector<size_t>> mvSets;
    std::vector<int> mvMatches12;
};

#endif // INITIALIZER_H
