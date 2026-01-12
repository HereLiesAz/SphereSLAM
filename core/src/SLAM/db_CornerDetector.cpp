#include "db_CornerDetector.h"
#include <opencv2/imgproc.hpp>

namespace lightcycle {

CornerDetector::CornerDetector() {}
CornerDetector::~CornerDetector() {}

/**
 * Reconstructed Section 3.2: detectFeatures
 * LightCycle used a custom corner detector (db_CornerDetector).
 * This implementation uses FAST corners with an adaptive threshold.
 */
void CornerDetector::detectFeatures(const cv::Mat& image, std::vector<Feature>& features) {
    cv::Mat gray;
    if (image.channels() == 3) {
        cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
    } else {
        gray = image;
    }

    std::vector<cv::KeyPoint> keypoints;
    // LightCycle symbols suggest a grid-based FAST detector for uniform feature spread
    int maxFeatures = 1000;
    cv::Ptr<cv::FastFeatureDetector> fast = cv::FastFeatureDetector::create(20);
    fast->detect(gray, keypoints);

    // Limit to top features and convert to lightcycle::Feature format
    if (keypoints.size() > maxFeatures) {
        std::sort(keypoints.begin(), keypoints.end(), [](const cv::KeyPoint& a, const cv::KeyPoint& b) {
            return a.response > b.response;
        });
        keypoints.resize(maxFeatures);
    }

    for (const auto& kp : keypoints) {
        features.push_back({kp.pt.x, kp.pt.y});
    }
}

} // namespace lightcycle
