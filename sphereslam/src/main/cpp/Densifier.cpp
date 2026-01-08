#include "Densifier.h"
#include "DepthAnyCamera.h"
#include "SLAM/KeyFrame.h"
#include "MobileGS.h" // For Gaussian struct
#include <android/log.h>
#include <cmath>
#include <opencv2/imgproc.hpp>

#define TAG "Densifier"

// Constants for Equirectangular Projection
const int EQUI_WIDTH = 512;
const int EQUI_HEIGHT = 256;
const float PI = 3.14159265358979323846f;

Densifier::Densifier(DepthAnyCamera* pDepthEstimator) : mpDepthEstimator(pDepthEstimator) {
}

std::vector<Gaussian> Densifier::DensifyKeyFrame(KeyFrame* pKF, const std::vector<float>& depthMap, const cv::Mat& colorImage) {
    std::vector<Gaussian> newGaussians;

    if (!pKF || depthMap.empty() || colorImage.empty()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Invalid input for DensifyKeyFrame");
        return newGaussians;
    }

    // Get Camera Pose (World to Camera)
    cv::Mat Tcw = pKF->GetPose();
    if (Tcw.empty()) return newGaussians;

    // We need Camera to World (Inverse)
    cv::Mat Twc;
    cv::invert(Tcw, Twc);

    // Resize color image if it doesn't match depth map resolution (512x256)
    cv::Mat colorResized;
    if (colorImage.cols != EQUI_WIDTH || colorImage.rows != EQUI_HEIGHT) {
        cv::resize(colorImage, colorResized, cv::Size(EQUI_WIDTH, EQUI_HEIGHT));
    } else {
        colorResized = colorImage;
    }

    // Extract Rotation (R) and Translation (t)
    float r00 = Twc.at<float>(0,0); float r01 = Twc.at<float>(0,1); float r02 = Twc.at<float>(0,2); float tx = Twc.at<float>(0,3);
    float r10 = Twc.at<float>(1,0); float r11 = Twc.at<float>(1,1); float r12 = Twc.at<float>(1,2); float ty = Twc.at<float>(1,3);
    float r20 = Twc.at<float>(2,0); float r21 = Twc.at<float>(2,1); float r22 = Twc.at<float>(2,2); float tz = Twc.at<float>(2,3);

    // Reserve memory
    newGaussians.reserve(EQUI_WIDTH * EQUI_HEIGHT);

    // Iterate over pixels
    for (int v = 0; v < EQUI_HEIGHT; ++v) {
        for (int u = 0; u < EQUI_WIDTH; ++u) {
            int idx = v * EQUI_WIDTH + u;
            float depth = depthMap[idx];

            // Filter invalid depth (0 or too far)
            if (depth <= 0.1f || depth > 100.0f) continue;

            // 1. Unproject Equirectangular (u,v) to Unit Vector (x,y,z)
            // Normalized coordinates: u -> [0, 1], v -> [0, 1]
            float uf = (float)u / EQUI_WIDTH;
            float vf = (float)v / EQUI_HEIGHT;

            // Longitude (phi): -PI to PI
            // Latitude (theta): -PI/2 to PI/2
            float phi = (uf - 0.5f) * 2.0f * PI;
            float theta = -(vf - 0.5f) * PI;

            // Spherical to Cartesian (Camera Frame)
            // SphereSLAM / OpenCV Convention:
            // +Z Forward, +X Right, +Y Down.

            // Standard Spherical (Y-Up):
            // x = cos(theta)sin(phi)
            // y = sin(theta)  <-- +1 at Top
            // z = cos(theta)cos(phi)

            // We want Y-Down (+1 at Bottom).
            // v=0 (Top) -> theta = PI/2. We want Y = -1.
            // So y_c = -sin(theta).

            float cosTheta = cos(theta);
            float sinTheta = sin(theta);
            float cosPhi = cos(phi);
            float sinPhi = sin(phi);

            // Vector on unit sphere
            float x_c = cosTheta * sinPhi;
            float y_c = -sinTheta; // Inverted for Y-Down convention
            float z_c = cosTheta * cosPhi;

            // Scale by Depth
            float X_cam = x_c * depth;
            float Y_cam = y_c * depth;
            float Z_cam = z_c * depth;

            // 2. Transform to World Space
            // P_world = R * P_cam + t
            float X_world = r00 * X_cam + r01 * Y_cam + r02 * Z_cam + tx;
            float Y_world = r10 * X_cam + r11 * Y_cam + r12 * Z_cam + ty;
            float Z_world = r20 * X_cam + r21 * Y_cam + r22 * Z_cam + tz;

            // 3. Create Gaussian
            Gaussian g;
            g.position = glm::vec3(X_world, Y_world, Z_world);

            // Rotation: Default identity for spherical gaussians (Aggregate Init)
            g.rotation = {1.0f, 0.0f, 0.0f, 0.0f};

            // Scale: Heuristic based on depth and pixel angular size
            float scaleFactor = depth * 0.05f;
            g.scale = glm::vec3(scaleFactor, scaleFactor, scaleFactor);

            // Opacity
            g.opacity = 1.0f;

            // Color: Sample from image
            cv::Vec3b bgr = colorResized.at<cv::Vec3b>(v, u);
            // Convert to 0-1 range and RGB
            g.color_sh = glm::vec3(bgr[2] / 255.0f, bgr[1] / 255.0f, bgr[0] / 255.0f);

            newGaussians.push_back(g);
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Densified KeyFrame %lu: Generated %zu Gaussians", pKF->mnId, newGaussians.size());
    return newGaussians;
}
