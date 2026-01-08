#include "Initializer.h"
#include <iostream>
#include <map>
#include <opencv2/calib3d.hpp>

Initializer::Initializer(const Frame &ReferenceFrame, float sigma, int iterations)
    : mInitialFrame(ReferenceFrame), mSigma(sigma), mMaxIterations(iterations) {
}

bool Initializer::Initialize(const Frame &CurrentFrame, const std::vector<int> &vMatches12,
                             cv::Mat &R21, cv::Mat &t21, std::vector<cv::Point3f> &vP3D,
                             std::vector<bool> &vbTriangulated) {
    mvMatches12 = vMatches12;
    // Removed unused mvKeys1/2

    // 1. Collect matched points for RANSAC
    std::vector<cv::Point2f> points1, points2;
    // Map from RANSAC index to Original KeyPoint Index
    std::vector<int> ransacToOrigIdx; 

    // Access keys from Frame (Assuming single vector for simplicity or face 0)
    const std::vector<cv::KeyPoint>& keys1 = mInitialFrame.mvKeys[0]; 
    const std::vector<cv::KeyPoint>& keys2 = CurrentFrame.mvKeys[0];

    for(size_t i=0; i<vMatches12.size(); ++i) {
        if(vMatches12[i] >= 0) {
            points1.push_back(keys1[i].pt);
            points2.push_back(keys2[vMatches12[i]].pt);
            ransacToOrigIdx.push_back(i);
        }
    }

    if(points1.size() < 20) return false;

    // 2. Find Homography and Fundamental Matrix using OpenCV
    cv::Mat maskH, maskF;
    cv::Mat H = cv::findHomography(points1, points2, cv::RANSAC, 3.0, maskH);
    cv::Mat F = cv::findFundamentalMat(points1, points2, cv::FM_RANSAC, 3.0, 0.99, maskF);

    if (H.empty() || F.empty()) return false;

    // 3. Score Models (Simplification: Count inliers)
    float scoreH = cv::countNonZero(maskH);
    float scoreF = cv::countNonZero(maskF);
    
    float ratio = scoreH / (scoreF + 1e-5f);

    // 4. Select Model and Reconstruct
    cv::Mat K = cv::Mat::eye(3, 3, CV_32F); 
    if (mInitialFrame.mpCamera) {
         K = mInitialFrame.mpCamera->GetK();
    }

    bool success = false;
    
    // Resize output containers to match total number of keys in Frame 1
    vbTriangulated.assign(keys1.size(), false);
    vP3D.assign(keys1.size(), cv::Point3f(0,0,0));
    
    // P1 = K * [I | 0]
    cv::Mat P1(3, 4, CV_32F);
    K.copyTo(P1.rowRange(0,3).colRange(0,3));
    P1.col(3) = cv::Mat::zeros(3,1, CV_32F);

    if (ratio > 0.45) {
        // Prefer Homography
        std::vector<cv::Mat> Rs, ts, normals;
        int solutions = cv::decomposeHomographyMat(H, K, Rs, ts, normals);
        
        // Check 4 solutions
        int bestGood = 0;
        int bestSol = -1;
        cv::Mat bestMaskPose;
        cv::Mat bestPoints4D;

        for(int i=0; i<solutions; ++i) {
            cv::Mat R = Rs[i];
            cv::Mat t = ts[i];
            
            // P2 = K * [R | t]
            cv::Mat P2(3, 4, CV_32F);
            R.copyTo(P2.rowRange(0,3).colRange(0,3));
            t.copyTo(P2.rowRange(0,3).col(3));
            P2 = K * P2;
            
            cv::Mat points4D;
            cv::triangulatePoints(P1, P2, points1, points2, points4D);
            
            // Check parallax and cheirality
            int nGood = 0;
            cv::Mat mask = cv::Mat::zeros(points1.size(), 1, CV_8UC1);
            
            for(size_t j=0; j<points1.size(); ++j) {
                if(!maskH.at<uchar>(j)) continue; // Only check H inliers
                
                float w = points4D.at<float>(3, j);
                if(std::abs(w) < 1e-4) continue;
                
                float z1 = points4D.at<float>(2, j) / w;
                
                // Project to cam 2
                cv::Mat x3D = points4D.col(j);
                cv::Mat x2 = P2 * x3D;
                float w2 = x2.at<float>(2);
                
                if(z1 > 0 && w2 > 0) {
                     mask.at<uchar>(j) = 1;
                     nGood++;
                }
            }
            
            if(nGood > bestGood) {
                bestGood = nGood;
                bestSol = i;
                bestMaskPose = mask; // Use our computed mask
                bestPoints4D = points4D;
                R21 = R;
                t21 = t;
            }
        }
        
        if(bestSol >= 0 && bestGood > 10) {
             // Fill outputs
             for(size_t i=0; i<ransacToOrigIdx.size(); ++i) {
                if(bestMaskPose.at<uchar>(i)) {
                     float w = bestPoints4D.at<float>(3, i);
                     float x = bestPoints4D.at<float>(0, i) / w;
                     float y = bestPoints4D.at<float>(1, i) / w;
                     float z = bestPoints4D.at<float>(2, i) / w;
                     
                     vP3D[ransacToOrigIdx[i]] = cv::Point3f(x, y, z);
                     vbTriangulated[ransacToOrigIdx[i]] = true;
                }
             }
             success = true;
        }

    } else {
        // Fundamental Matrix Reconstruction
        cv::Mat E = K.t() * F * K; 
        cv::Mat R, t, maskPose;
        
        int goodPoints = cv::recoverPose(E, points1, points2, K, R, t, maskPose);
        
        if (goodPoints > 10) {
            R21 = R;
            t21 = t;
            
            cv::Mat P2(3, 4, CV_32F);
            R.copyTo(P2.rowRange(0,3).colRange(0,3));
            t.copyTo(P2.rowRange(0,3).col(3));
            P2 = K * P2;

            cv::Mat points4D;
            cv::triangulatePoints(P1, P2, points1, points2, points4D);

            for(size_t i=0; i<ransacToOrigIdx.size(); ++i) {
                int origIdx = ransacToOrigIdx[i];
                if(maskPose.at<uchar>(i)) {
                    float w = points4D.at<float>(3, i);
                    if(std::abs(w) > 1e-4) {
                        float x = points4D.at<float>(0, i) / w;
                        float y = points4D.at<float>(1, i) / w;
                        float z = points4D.at<float>(2, i) / w;
                        if (z > 0) {
                            vP3D[origIdx] = cv::Point3f(x, y, z);
                            vbTriangulated[origIdx] = true;
                        }
                    }
                }
            }
            success = true;
        }
    }

    return success;
}
