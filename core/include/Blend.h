#ifndef BLEND_H
#define BLEND_H

#include <vector>
#include <opencv2/core.hpp>
#include "Mosaic.h"

namespace lightcycle {

class Blend {
public:
    Blend();
    ~Blend();

    // Section 3.5: Multi-band blending implementation
    void runBlend(std::vector<Frame>& warpedFrames, cv::Mat& finalMosaic);

private:
    std::vector<cv::Mat> createLaplacianPyramid(const cv::Mat& img);
    std::vector<cv::Mat> createGaussianPyramid(const cv::Mat& img);
    cv::Mat collapsePyramid(const std::vector<cv::Mat>& pyramid);
};

} // namespace lightcycle

#endif // BLEND_H
