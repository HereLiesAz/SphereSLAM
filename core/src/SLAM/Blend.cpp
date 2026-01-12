#include "Blend.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/blenders.hpp>

namespace lightcycle {

Blend::Blend() {}
Blend::~Blend() {}

/**
 * Reconstructed Section 3.5: Multi-band blending
 */
void Blend::runBlend(std::vector<Frame>& warpedFrames, cv::Mat& finalMosaic) {
    if (warpedFrames.empty()) return;

    // 1. Create pyramids for each frame
    // This is handled internally by OpenCV's MultiBandBlender
    // but the doc describes the manual process.

    int outW = 4096;
    int outH = 2048;

    cv::detail::MultiBandBlender blender(false, 5); // 5 levels as seen in symbols
    blender.prepare(cv::Rect(0, 0, outW, outH));

    for (auto& frame : warpedFrames) {
        // Assume frame.image is already warped to equirectangular space
        // In a full implementation, we'd use the globalTransform here.

        cv::Mat mask = cv::Mat::ones(frame.image.size(), CV_8U) * 255;
        // Feeds the Laplacian pyramid
        blender.feed(frame.image, mask, cv::Point(0,0));
    }

    // 2. Blend each level and collapse
    cv::Mat result, result_mask;
    blender.blend(result, result_mask);

    result.convertTo(finalMosaic, CV_8UC3);
}

std::vector<cv::Mat> Blend::createGaussianPyramid(const cv::Mat& img) {
    std::vector<cv::Mat> pyramid;
    cv::Mat current = img;
    pyramid.push_back(current);
    for (int i = 0; i < 4; i++) {
        cv::Mat down;
        cv::pyrDown(current, down);
        pyramid.push_back(down);
        current = down;
    }
    return pyramid;
}

std::vector<cv::Mat> Blend::createLaplacianPyramid(const cv::Mat& img) {
    std::vector<cv::Mat> gaussian = createGaussianPyramid(img);
    std::vector<cv::Mat> laplacian;
    for (size_t i = 0; i < gaussian.size() - 1; i++) {
        cv::Mat up;
        cv::pyrUp(gaussian[i+1], up, gaussian[i].size());
        laplacian.push_back(gaussian[i] - up);
    }
    laplacian.push_back(gaussian.back());
    return laplacian;
}

cv::Mat Blend::collapsePyramid(const std::vector<cv::Mat>& pyramid) {
    cv::Mat current = pyramid.back();
    for (int i = static_cast<int>(pyramid.size()) - 2; i >= 0; i--) {
        cv::Mat up;
        cv::pyrUp(current, up, pyramid[i].size());
        current = up + pyramid[i];
    }
    return current;
}

} // namespace lightcycle
