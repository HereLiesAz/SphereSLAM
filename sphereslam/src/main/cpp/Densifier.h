#ifndef DENSIFIER_H
#define DENSIFIER_H

#include <vector>
#include <mutex>
#include <opencv2/core.hpp>

// Forward declarations
class KeyFrame;
class DepthAnyCamera;
struct Gaussian;

class Densifier {
public:
    Densifier(DepthAnyCamera* pDepthEstimator);

    // Main function to convert a KeyFrame to Gaussians
    // Accepts the KeyFrame (for Pose) and the Equirectangular Depth and Color maps
    std::vector<Gaussian> DensifyKeyFrame(KeyFrame* pKF, const std::vector<float>& depthMap, const cv::Mat& colorImage);

private:
    DepthAnyCamera* mpDepthEstimator;
};

#endif // DENSIFIER_H
