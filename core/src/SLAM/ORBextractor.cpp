#include "ORBextractor.h"
#include <iostream>
#include <opencv2/features2d.hpp>

ORBextractor::ORBextractor(int nfeatures, float scaleFactor, int nlevels, int iniThFAST, int minThFAST)
    : nfeatures(nfeatures), scaleFactor(scaleFactor), nlevels(nlevels), iniThFAST(iniThFAST), minThFAST(minThFAST) {
}

void ORBextractor::operator()(cv::Mat image, cv::Mat mask,
                              std::vector<cv::KeyPoint>& keypoints,
                              cv::Mat& descriptors) {
    // Real implementation using OpenCV's ORB
    if (image.empty()) return;

    // Create OpenCV ORB detector with parameters
    // Note: nlevels and scaleFactor map directly. iniThFAST maps to fastThreshold.
    // scoreType, WTA_K etc are left as defaults.
    cv::Ptr<cv::ORB> orb = cv::ORB::create(
        nfeatures,
        scaleFactor,
        nlevels,
        31, // edgeThreshold
        0,  // firstLevel
        2,  // WTA_K
        cv::ORB::HARRIS_SCORE,
        31, // patchSize
        iniThFAST // fastThreshold
    );

    orb->detectAndCompute(image, mask, keypoints, descriptors);
}
