#include "Densifier.h"
#include "DepthAnyCamera.h"
#include "SLAM/KeyFrame.h"
#include "MobileGS.h" // For Gaussian struct
#include <android/log.h>

#define TAG "Densifier"

Densifier::Densifier(DepthAnyCamera* pDepthEstimator) : mpDepthEstimator(pDepthEstimator) {
}

std::vector<Gaussian> Densifier::DensifyKeyFrame(KeyFrame* pKF, const std::vector<float>& depthMap) {
    std::vector<Gaussian> newGaussians;

    // Stub implementation logic:
    // 1. Iterate over pixels of the KeyFrame (or subset/features)
    // 2. Unproject using depthMap
    // 3. Create Gaussian struct

    // For blueprint, we generate a dummy Gaussian at the KeyFrame position
    if (pKF) {
        cv::Mat Tcw = pKF->GetPose();
        // Check empty
        if (Tcw.empty()) return newGaussians;

        // Inverse pose (Camera to World)
        // Assume Tcw is [R|t] 4x4
        // ... (Math stub)

        Gaussian g;
        g.position = glm::vec3(0, 0, 0); // Stub: Origin
        g.scale = glm::vec3(0.1f, 0.1f, 0.1f);
        g.opacity = 0.8f;
        g.color_sh = glm::vec3(1.0f, 0.0f, 0.0f); // Red

        newGaussians.push_back(g);

        __android_log_print(ANDROID_LOG_INFO, TAG, "Generated %zu Gaussians for KF %lu", newGaussians.size(), pKF->mnId);
    }

    return newGaussians;
}
