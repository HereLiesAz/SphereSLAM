#ifndef OPENCV_CORE_HPP
#define OPENCV_CORE_HPP

namespace cv {

class Mat {
public:
    int rows, cols;

    Mat() {}
    static Mat eye(int rows, int cols, int type) { return Mat(); }
};

class Point2f {
public:
    float x, y;
    Point2f(float _x, float _y) : x(_x), y(_y) {}
};

class Point3f {
public:
    float x, y, z;
    Point3f() : x(0), y(0), z(0) {}
    Point3f(float _x, float _y, float _z) : x(_x), y(_y), z(_z) {}
};

#define CV_32F 5

}

#endif
