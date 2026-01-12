#ifndef CDELAUNAY_H
#define CDELAUNAY_H

#include <vector>
#include <opencv2/core.hpp>

namespace lightcycle {

struct Point2D {
    float x, y;
};

struct Triangle {
    int v1, v2, v3;
};

class CDelaunay {
public:
    CDelaunay();
    ~CDelaunay();

    void buildTriangulation(const std::vector<Point2D>& features);
    void warpImage(const cv::Mat& src, cv::Mat& dst);

private:
    std::vector<Triangle> mTriangles;
    std::vector<Point2D> mVertices;

    std::vector<Triangle> bowyerWatson(const std::vector<Point2D>& points);
    void warpTriangle_BL_LUT(const cv::Mat& src, cv::Mat& dst, Triangle t);
};

} // namespace lightcycle

#endif // CDELAUNAY_H
