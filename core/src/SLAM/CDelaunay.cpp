#include "CDelaunay.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>

namespace lightcycle {

CDelaunay::CDelaunay() {}
CDelaunay::~CDelaunay() {}

/**
 * Reconstructed buildTriangulation
 * Uses OpenCV's Subdiv2D for Delaunay triangulation.
 */
void CDelaunay::buildTriangulation(const std::vector<Point2D>& features) {
    mVertices = features;
    mTriangles.clear();

    if (features.empty()) return;

    // Determine bounds
    float minX = features[0].x, maxX = features[0].x;
    float minY = features[0].y, maxY = features[0].y;
    for (const auto& p : features) {
        minX = std::min(minX, p.x); maxX = std::max(maxX, p.x);
        minY = std::min(minY, p.y); maxY = std::max(maxY, p.y);
    }

    cv::Rect rect(minX - 1, minY - 1, maxX - minX + 2, maxY - minY + 2);
    cv::Subdiv2D subdiv(rect);

    for (const auto& p : features) {
        subdiv.insert(cv::Point2f(p.x, p.y));
    }

    std::vector<cv::Vec6f> triangleList;
    subdiv.getTriangleList(triangleList);

    for (const auto& t : triangleList) {
        cv::Point2f pt[3] = { cv::Point2f(t[0], t[1]), cv::Point2f(t[2], t[3]), cv::Point2f(t[4], t[5]) };

        int indices[3] = { -1, -1, -1 };
        bool allIn = true;
        for (int i = 0; i < 3; ++i) {
            for (size_t j = 0; j < features.size(); ++j) {
                if (std::abs(pt[i].x - features[j].x) < 1e-3 && std::abs(pt[i].y - features[j].y) < 1e-3) {
                    indices[i] = static_cast<int>(j);
                    break;
                }
            }
            if (indices[i] == -1) allIn = false;
        }

        if (allIn) {
            mTriangles.push_back({indices[0], indices[1], indices[2]});
        }
    }
}

/**
 * Reconstructed warpImage using mesh
 */
void CDelaunay::warpImage(const cv::Mat& src, cv::Mat& dst) {
    if (mTriangles.empty()) {
        src.copyTo(dst);
        return;
    }

    dst = cv::Mat::zeros(src.size(), src.type());

    for (const auto& tri : mTriangles) {
        warpTriangle_BL_LUT(src, dst, tri);
    }
}

void CDelaunay::warpTriangle_BL_LUT(const cv::Mat& src, cv::Mat& dst, Triangle t) {
    // Simplified affine warp for the triangle
    std::vector<cv::Point2f> srcTri = {
        cv::Point2f(mVertices[t.v1].x, mVertices[t.v1].y),
        cv::Point2f(mVertices[t.v2].x, mVertices[t.v2].y),
        cv::Point2f(mVertices[t.v3].x, mVertices[t.v3].y)
    };

    // In LightCycle, the destination would be the spherical projection.
    // Here we just placeholder with an identity warp for the reconstruction logic.
    std::vector<cv::Point2f> dstTri = srcTri;

    cv::Mat warpMat = cv::getAffineTransform(srcTri, dstTri);

    cv::Rect r = cv::boundingRect(dstTri);
    cv::Mat mask = cv::Mat::zeros(r.height, r.width, CV_8U);
    std::vector<cv::Point> roiTri;
    for(int i=0; i<3; ++i) roiTri.push_back(cv::Point(dstTri[i].x - r.x, dstTri[i].y - r.y));
    cv::fillConvexPoly(mask, roiTri, cv::Scalar(255));

    cv::Mat imgRoi;
    cv::warpAffine(src, imgRoi, warpMat, r.size(), cv::INTER_LINEAR, cv::BORDER_REFLECT_101);

    imgRoi.copyTo(dst(r), mask);
}

} // namespace lightcycle
