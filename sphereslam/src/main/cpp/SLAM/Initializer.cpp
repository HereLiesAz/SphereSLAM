#include "Initializer.h"
#include <iostream>

Initializer::Initializer(const Frame &ReferenceFrame, float sigma, int iterations)
    : mInitialFrame(ReferenceFrame), mSigma(sigma), mMaxIterations(iterations) {
}

bool Initializer::Initialize(const Frame &CurrentFrame, const std::vector<int> &vMatches12,
                             cv::Mat &R21, cv::Mat &t21, std::vector<cv::Point3f> &vP3D,
                             std::vector<bool> &vbTriangulated) {
    // Stub implementation
    // In reality: Compute H and F, select best, reconstruct motion

    // Simulate success
    R21 = cv::Mat::eye(3, 3, CV_32F);
    t21 = cv::Mat::zeros(3, 1, CV_32F);

    // Generate dummy 3D points
    for(size_t i=0; i<vMatches12.size(); ++i) {
        if(vMatches12[i] >= 0) {
            vP3D.push_back(cv::Point3f(0,0,1));
            vbTriangulated.push_back(true);
        } else {
            vP3D.push_back(cv::Point3f(0,0,0));
            vbTriangulated.push_back(false);
        }
    }

    // std::cout << "Initializer: Initialization successful (Stub)" << std::endl;
    return true;
}
