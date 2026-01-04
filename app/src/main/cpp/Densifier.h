#ifndef DENSIFIER_H
#define DENSIFIER_H

#include <vector>
#include <mutex>
#include <opencv2/core/core.hpp>

// Forward declarations
class KeyFrame;
class DepthAnyCamera;
struct Gaussian;

class Densifier {
public:
    Densifier(DepthAnyCamera* pDepthEstimator);

    // Main function to convert a KeyFrame to Gaussians
    std::vector<Gaussian> DensifyKeyFrame(KeyFrame* pKF, const std::vector<float>& depthMap);

    // In a real system, this might run in a loop or be called by LocalMapping

private:
    DepthAnyCamera* mpDepthEstimator;
};

#endif // DENSIFIER_H
