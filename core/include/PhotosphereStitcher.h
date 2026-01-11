#ifndef PHOTOSPHERE_STITCHER_H
#define PHOTOSPHERE_STITCHER_H

#include <opencv2/core.hpp>
#include <vector>

class PhotosphereStitcher {
public:
    /**
     * @brief Stitches 6 CubeMap faces into an equirectangular panorama.
     * Uses OpenCV's warpers and blenders for high-quality output.
     *
     * @param faces Vector of 6 cv::Mat (Order: Right, Left, Top, Bottom, Front, Back - standard CubeMap)
     * @param outputEqui Reference to output equirectangular image.
     * @return true if successful.
     */
    static bool StitchCubeMap(const std::vector<cv::Mat>& faces, cv::Mat& outputEqui);
};

#endif // PHOTOSPHERE_STITCHER_H
