#ifndef OPENCV_CORE_HPP
#define OPENCV_CORE_HPP

#include <vector>

namespace cv {

class Mat {
public:
    int rows, cols;

    Mat() : rows(0), cols(0) {}
    Mat(int r, int c, int type) : rows(r), cols(c) {}

    static Mat eye(int rows, int cols, int type) { return Mat(rows, cols, type); }
    static Mat zeros(int rows, int cols, int type) { return Mat(rows, cols, type); }

    Mat clone() const { return *this; }
    bool empty() const { return rows == 0 || cols == 0; }
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

class KeyPoint {
public:
    float pt_x, pt_y;
    float size;
    float angle;
    float response;
    int octave;
    int class_id;

    KeyPoint() : pt_x(0), pt_y(0), size(0), angle(-1), response(0), octave(0), class_id(-1) {}
    KeyPoint(float x, float y, float _size, float _angle=-1, float _response=0, int _octave=0, int _class_id=-1)
        : pt_x(x), pt_y(y), size(_size), angle(_angle), response(_response), octave(_octave), class_id(_class_id) {}
};

#define CV_32F 5
#define CV_8U 0

}

#endif
