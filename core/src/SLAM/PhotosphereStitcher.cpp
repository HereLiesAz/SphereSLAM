#include "PhotosphereStitcher.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/warpers.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <iostream>

bool PhotosphereStitcher::StitchCubeMap(const std::vector<cv::Mat>& faces, cv::Mat& outputEqui) {
    if (faces.size() != 6 || faces[0].empty()) {
        std::cerr << "PhotosphereStitcher: Expected 6 non-empty faces, but got " << faces.size() << std::endl;
        return false;
    }

    // Define standard CubeMap rotations (assuming OpenCV convention: Y-Down, Front=+Z)
    // However, SLAM systems often vary.
    // Standard CubeMap Order usually: Right(+X), Left(-X), Top(+Y), Bottom(-Y), Front(+Z), Back(-Z)
    // Rotations to bring each face to the global frame.
    // For stitching, we treat each face as a camera with 90 deg FOV.

    // K matrix for 90 deg FOV: fx = w/2, cx = w/2.
    int w = faces[0].cols;
    int h = faces[0].rows;

    // Check if faces are same size
    for(const auto& f : faces) {
        if(f.cols != w || f.rows != h) return false;
    }

    // Since we know the exact geometry, we can use OpenCV's detail::SphericalWarper
    // BUT detail::SphericalWarper is for stitching *overlapping* images into a sphere.
    // Here we have *exact* adjacent faces.
    // The previous implementation used a manual pixel loop which is actually "correct" for perfect alignment.
    // To make it "Real" (Hugin-like) and better quality:
    // 1. Use cv::remap with Bicubic interpolation (better than nearest neighbor).
    // 2. We can still use the "Inverse Projection" method but optimized with OpenCV's remap.

    // Map generation for Remap
    // Output size: 4*w x 2*w (standard equirectangular 2:1)
    int outW = 4 * w;
    int outH = 2 * w;

    // Prepare separate maps for each face to minimize branching in loop?
    // No, standard remap requires one map per source image.
    // Since we have 6 sources, we can't use a single `remap` call.

    // Approach:
    // Create the full Equirectangular image.
    // For each pixel in Equi, calculate 3D ray.
    // Determine which Face it hits.
    // Map to UV on that face.
    // BUT do this efficiently.

    // Actually, "Real" stitching usually implies Seam Blending.
    // Even with perfect CubeMaps, seams can be visible due to exposure differences.
    // So we should:
    // 1. Warp each face onto the spherical canvas.
    // 2. Use MultiBandBlender to blend them.

    // Setup Blender
    cv::detail::MultiBandBlender blender(false);
    blender.prepare(cv::Rect(0, 0, outW, outH));

    // Camera Intrinsics
    cv::Mat K = cv::Mat::eye(3, 3, CV_32F);
    K.at<float>(0,0) = (float)w / 2.0f; // fx
    K.at<float>(1,1) = (float)h / 2.0f; // fy
    K.at<float>(0,2) = (float)w / 2.0f; // cx
    K.at<float>(1,2) = (float)h / 2.0f; // cy

    // Rotations (R) for each face to align with Global Frame
    // Standard GL/CV CubeMap:
    // 0: +X (Right)  -> R turns +Z to +X
    // 1: -X (Left)   -> R turns +Z to -X
    // 2: +Y (Top)    -> R turns +Z to +Y
    // 3: -Y (Bottom) -> R turns +Z to -Y
    // 4: +Z (Front)  -> Identity (if Front is +Z)
    // 5: -Z (Back)   -> R turns +Z to -Z

    // We need rotation matrices that transform a point from Camera Local to Global.
    // P_global = R * P_local
    std::vector<cv::Mat> Rs(6);

    // Helper to create Rotation Matrix from Euler angles or axes
    // Face 0: Right (+X). Local +Z points to Global +X.
    // Local X (+Right) points to Global -Z.
    // Local Y (+Down) points to Global -Y (Standard) or +Y?
    // Let's assume standard Cubemap layout:
    // Face 0 (Right): Look (+1, 0, 0), Up (0, -1, 0)
    // Face 1 (Left):  Look (-1, 0, 0), Up (0, -1, 0)
    // Face 2 (Top):   Look (0, +1, 0), Up (0, 0, +1)
    // Face 3 (Bottom):Look (0, -1, 0), Up (0, 0, -1)
    // Face 4 (Front): Look (0, 0, +1), Up (0, -1, 0)
    // Face 5 (Back):  Look (0, 0, -1), Up (0, -1, 0)

    // Constructing R matrices (cols are Local X, Y, Z axes expressed in Global coords)

    // Face 0 (Right)
    // Z_local -> +X_global (1, 0, 0)
    // Y_local -> +Y_global (0, 1, 0) (Assuming Y-Down generally) -> Wait, Cubemap spec says Top is +Y.
    // Let's stick to the convention used in System.cpp:
    // "Spherical to Cartesian... x = cosTheta * sinPhi..."
    // We will reuse the Warper logic from OpenCV which handles this if we give R.

    // But aligning 6 arbitrary R matrices to match OpenCV's spherical warper is tricky.
    // EASIER: Just implement the Inverse Projection with Bicubic Interpolation manually using remap logic blocks.

    // Create 6 masks/weight maps?
    // Let's stick to the previous logic but optimize with Remap for quality.

    outputEqui = cv::Mat::zeros(outH, outW, CV_8UC3);

    // Precompute map for the WHOLE image, but store Face Index and UV.
    // Since `remap` takes simple x,y, we can't switch source images mid-scanline easily with one call.
    // We have to warp each face to the Equirectangular projection separately and accumulate.

    // Instantiate Warper
    float scale = (float)outW / (2.0f * CV_PI); // Arc length = radius * angle. width = 2*pi*r -> r = w / 2pi. Scale for warper is radius.
    cv::Ptr<cv::detail::RotationWarper> warper = cv::makePtr<cv::detail::SphericalWarper>(scale);

    // Convert K to CV_32F
    K.convertTo(K, CV_32F);

    // Define Rotation Matrices for 6 faces relative to the center
    // We assume the camera is at (0,0,0).
    // Note: detailed::SphericalWarper assumes y-axis is rotation axis.

    // Construct Rs manually.
    // 0: Right (+X). Rotate -90 around Y.
    // 1: Left (-X). Rotate +90 around Y.
    // 2: Top (+Y). Rotate +90 around X.
    // 3: Bottom (-Y). Rotate -90 around X.
    // 4: Front (+Z). Identity.
    // 5: Back (-Z). Rotate 180 around Y.

    // Note: OpenCV coords: X-Right, Y-Down, Z-Forward.

    // Face 4 (Front): Identity.
    Rs[4] = cv::Mat::eye(3, 3, CV_32F);

    // Face 5 (Back): Rotate 180 Y
    // [-1  0  0]
    // [ 0  1  0]
    // [ 0  0 -1]
    Rs[5] = (cv::Mat_<float>(3,3) << -1,0,0, 0,1,0, 0,0,-1);

    // Face 0 (Right): Rotate Y by -90 (Points Z to X)
    // [ 0  0  1]
    // [ 0  1  0]
    // [-1  0  0]
    Rs[0] = (cv::Mat_<float>(3,3) << 0,0,1, 0,1,0, -1,0,0);

    // Face 1 (Left): Rotate Y by +90 (Points Z to -X)
    // [ 0  0 -1]
    // [ 0  1  0]
    // [ 1  0  0]
    Rs[1] = (cv::Mat_<float>(3,3) << 0,0,-1, 0,1,0, 1,0,0);

    // Face 2 (Top): Points Z to -Y (up). Rotate X by -90.
    // [ 1  0  0]
    // [ 0  0 -1]
    // [ 0  1  0]
    Rs[2] = (cv::Mat_<float>(3,3) << 1,0,0, 0,0,-1, 0,1,0);

    // Face 3 (Bottom): Points Z to +Y (down). Rotate X by +90.
    // [ 1  0  0]
    // [ 0  0  1]
    // [ 0 -1  0]
    Rs[3] = (cv::Mat_<float>(3,3) << 1,0,0, 0,0,1, 0,-1,0);

    // Blend Loop
    for (int i = 0; i < 6; ++i) {
        if(faces[i].empty()) continue;

        // Warp
        cv::Mat warped_img;
        cv::Mat mask = cv::Mat::ones(faces[i].size(), CV_8U) * 255;
        cv::Mat warped_mask;
        cv::Point tl = warper->warp(faces[i], K, Rs[i], cv::INTER_CUBIC, 0, warped_img);
        warper->warp(mask, K, Rs[i], cv::INTER_NEAREST, 0, warped_mask);

        // Feed to Blender
        blender.feed(warped_img, warped_mask, tl);
    }

    cv::Mat result, result_mask;
    blender.blend(result, result_mask);

    result.convertTo(outputEqui, CV_8UC3);
    return true;
}
