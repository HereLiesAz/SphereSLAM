#include "PhotosphereStitcher.h"
#include "SLAM/KeyFrame.h"
#include <opencv2/imgcodecs.hpp>
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

    int w = faces[0].cols;
    int h = faces[0].rows;

    for(const auto& f : faces) {
        if(f.cols != w || f.rows != h) return false;
    }

    // Output size: 4*w x 2*w (standard equirectangular 2:1)
    int outW = 4 * w;
    int outH = 2 * w;

    // Setup Blender
    cv::detail::MultiBandBlender blender(false); // true for GPU
    blender.prepare(cv::Rect(0, 0, outW, outH));

    // Camera Intrinsics
    cv::Mat K = cv::Mat::eye(3, 3, CV_32F);
    K.at<float>(0,0) = (float)w / 2.0f; // fx
    K.at<float>(1,1) = (float)h / 2.0f; // fy
    K.at<float>(0,2) = (float)w / 2.0f; // cx
    K.at<float>(1,2) = (float)h / 2.0f; // cy

    outputEqui = cv::Mat::zeros(outH, outW, CV_8UC3);

    float scale = (float)outW / (2.0f * CV_PI);
    cv::Ptr<cv::detail::RotationWarper> warper = cv::makePtr<cv::detail::SphericalWarper>(scale);

    K.convertTo(K, CV_32F);

    std::vector<cv::Mat> Rs(6);

    // Face 4 (Front): Identity.
    Rs[4] = cv::Mat::eye(3, 3, CV_32F);

    // Face 5 (Back): Rotate 180 Y
    Rs[5] = (cv::Mat_<float>(3,3) << -1,0,0, 0,1,0, 0,0,-1);

    // Face 0 (Right): Rotate Y by -90 (Points Z to X)
    Rs[0] = (cv::Mat_<float>(3,3) << 0,0,1, 0,1,0, -1,0,0);

    // Face 1 (Left): Rotate Y by +90 (Points Z to -X)
    Rs[1] = (cv::Mat_<float>(3,3) << 0,0,-1, 0,1,0, 1,0,0);

    // Face 2 (Top): Points Z to -Y (up). Rotate X by -90.
    Rs[2] = (cv::Mat_<float>(3,3) << 1,0,0, 0,0,-1, 0,1,0);

    // Face 3 (Bottom): Points Z to +Y (down). Rotate X by +90.
    Rs[3] = (cv::Mat_<float>(3,3) << 1,0,0, 0,0,1, 0,-1,0);

    // Blend Loop
    for (int i = 0; i < 6; ++i) {
        if(faces[i].empty()) continue;

        cv::Mat warped_img;
        cv::Mat mask = cv::Mat::ones(faces[i].size(), CV_8U) * 255;
        cv::Mat warped_mask;
        cv::Point tl = warper->warp(faces[i], K, Rs[i], cv::INTER_CUBIC, 0, warped_img);
        warper->warp(mask, K, Rs[i], cv::INTER_NEAREST, 0, warped_mask);

        blender.feed(warped_img, warped_mask, tl);
    }

    cv::Mat result, result_mask;
    blender.blend(result, result_mask);

    result.convertTo(outputEqui, CV_8UC3);
    return true;
}

bool PhotosphereStitcher::StitchKeyFrames(const std::vector<KeyFrame*>& vpKFs, cv::Mat& outputEqui) {
    if (vpKFs.empty()) {
        std::cerr << "PhotosphereStitcher: No KeyFrames to stitch." << std::endl;
        return false;
    }

    // Determine output resolution.
    // Assume high quality: 2048 width (Equirectangular) or 4096.
    // Let's use 2048x1024 for reasonable mobile performance.
    int outW = 2048;
    int outH = 1024;
    float scale = (float)outW / (2.0f * CV_PI);

    // Setup Blender
    cv::detail::MultiBandBlender blender(false);
    blender.prepare(cv::Rect(0, 0, outW, outH));

    cv::Ptr<cv::detail::RotationWarper> warper = cv::makePtr<cv::detail::SphericalWarper>(scale);

    int count = 0;
    for(size_t i=0; i<vpKFs.size(); i++) {
        KeyFrame* pKF = vpKFs[i];
        if(!pKF) continue;

        // Load image from disk
        cv::Mat img;
        if (!pKF->mImgFilenames.empty()) {
             std::string path = KeyFrame::msCacheDir + "/" + pKF->mImgFilenames[0];
             img = cv::imread(path);
        }

        if (img.empty()) continue;

        cv::Mat K = pKF->mK.clone();

        if(K.empty()) {
             // Fallback if K missing (should not happen if KF constructed correctly)
             continue;
        }
        K.convertTo(K, CV_32F);

        // Pose Tcw.
        // Warper needs Rotation Matrix R that transforms from Camera to World (or Center).
        // Standard Spherical Warper assumes camera is at center.
        // We ignore Translation (t) for Photosphere creation (assume pure rotation or distant scene).
        // Rwc = R of Tcw^-1.
        // Tcw = [Rcw^T | -Rcw^T * twc] ?? No.
        // Tcw: P_c = R_cw * P_w + t_cw.
        // We need P_w = R_wc * P_c (ignoring translation).
        // So R = R_cw^T (Inverse of Rotation part of Tcw).

        cv::Mat Tcw = pKF->GetPose();
        if(Tcw.empty()) continue;

        cv::Mat Rcw = Tcw.rowRange(0,3).colRange(0,3);
        cv::Mat Rwc = Rcw.t(); // Transpose is Inverse for Rotation

        // However, we need to handle coordinate systems.
        // SLAM usually: X-Right, Y-Down, Z-Forward.
        // Spherical Warper expects: Y-axis is rotation axis?
        // Let's assume standard CV convention.

        cv::Mat warped_img;
        cv::Mat mask = cv::Mat::ones(img.size(), CV_8U) * 255;
        cv::Mat warped_mask;

        // Note: warper->warp takes K and R.
        // It computes P_spherical = R * K_inv * P_pixel ??
        // It projects pixel to ray, rotates ray by R, then maps to sphere.
        // So R should be Camera -> World. Yes, Rwc.

        // Also check if img type is CV_8UC3
        if(img.type() == CV_8UC1) {
            cv::cvtColor(img, img, cv::COLOR_GRAY2BGR);
        }

        try {
            cv::Point tl = warper->warp(img, K, Rwc, cv::INTER_LINEAR, 0, warped_img);
            warper->warp(mask, K, Rwc, cv::INTER_NEAREST, 0, warped_mask);
            blender.feed(warped_img, warped_mask, tl);
            count++;
        } catch (std::exception& e) {
            std::cerr << "Stitch Error KF " << pKF->mnId << ": " << e.what() << std::endl;
        }
    }

    if (count == 0) return false;

    cv::Mat result, result_mask;
    blender.blend(result, result_mask);

    if (result.empty()) return false;

    result.convertTo(outputEqui, CV_8UC3);
    return true;
}
