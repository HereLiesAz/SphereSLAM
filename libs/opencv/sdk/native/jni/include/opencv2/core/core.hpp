#ifndef OPENCV_CORE_HPP
#define OPENCV_CORE_HPP

#include <vector>
#include <iostream>

namespace cv {

class Mat {
public:
    int rows, cols;

    // Stub data storage to allow .at() to work without crashing immediately in tests
    // In reality this would be managed memory
    float stub_data[16];

    Mat() : rows(0), cols(0) {}
    Mat(int r, int c, int type) : rows(r), cols(c) {
        for(int i=0; i<16; ++i) stub_data[i] = 0;
    }

    static Mat eye(int rows, int cols, int type) {
        Mat m(rows, cols, type);
        for(int i=0; i<std::min(rows,cols) && i<4; ++i) m.at<float>(i,i) = 1.0f;
        return m;
    }

    static Mat zeros(int rows, int cols, int type) { return Mat(rows, cols, type); }

    Mat clone() const { return *this; }
    bool empty() const { return rows == 0 || cols == 0; }

    template<typename T>
    T& at(int r, int c) {
        // Safe-ish stub access for 4x4 matrices
        int idx = r * cols + c;
        if (idx >= 0 && idx < 16) return (T&)stub_data[idx];
        static T dummy; return dummy;
    }

    template<typename T>
    const T& at(int r, int c) const {
        int idx = r * cols + c;
        if (idx >= 0 && idx < 16) return (const T&)stub_data[idx];
        static T dummy; return dummy;
    }

    Mat rowRange(int start, int end) const { return *this; } // Stub returns self
    Mat colRange(int start, int end) const { return *this; } // Stub returns self
    Mat col(int c) const { return *this; } // Stub returns self
    Mat t() const { return *this; } // Stub returns self (Identity transpose is Identity)

    void copyTo(Mat m) const {}

    // Stub multiplication
    Mat operator*(const Mat& other) const { return *this; }
    Mat operator-() const { return *this; }
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
