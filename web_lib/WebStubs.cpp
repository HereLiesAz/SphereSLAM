#include <opencv2/core.hpp>
#include <iostream>

// WARNING: This file provides minimal STUBS for OpenCV functions to allow linking
// for WebAssembly build verification only.
// The resulting binary will NOT be functional for SLAM operations as it lacks
// actual OpenCV logic.
// For a functional build, please compile OpenCV for WebAssembly and link against it,
// removing this file from the build.

namespace cv {

    Mat::Mat() noexcept : flags(0), dims(0), rows(0), cols(0), data(nullptr), datastart(nullptr), dataend(nullptr), datalimit(nullptr), allocator(nullptr), u(nullptr), size(&rows) {}

    Mat::Mat(int rows, int cols, int type) : flags(0), dims(0), rows(rows), cols(cols), data(nullptr), datastart(nullptr), dataend(nullptr), datalimit(nullptr), allocator(nullptr), u(nullptr), size(&this->rows) {
        std::cerr << "CRITICAL WARNING: Stub cv::Mat(int, int, int) called. No memory allocated." << std::endl;
    }

    Mat::Mat(Size size, int type) : flags(0), dims(0), rows(size.height), cols(size.width), data(nullptr), datastart(nullptr), dataend(nullptr), datalimit(nullptr), allocator(nullptr), u(nullptr), size(&this->rows) {
        std::cerr << "CRITICAL WARNING: Stub cv::Mat(Size, int) called. No memory allocated." << std::endl;
    }

    Mat::Mat(int rows, int cols, int type, void* data, size_t step) : flags(0), dims(0), rows(rows), cols(cols), data((uchar*)data), datastart(nullptr), dataend(nullptr), datalimit(nullptr), allocator(nullptr), u(nullptr), size(&this->rows) {
        // This constructor is somewhat safe as it wraps existing data
    }

    Mat::Mat(const Mat& m) : flags(m.flags), dims(m.dims), rows(m.rows), cols(m.cols), data(m.data), datastart(m.datastart), dataend(m.dataend), datalimit(m.datalimit), allocator(m.allocator), u(m.u), size(&this->rows) {}

    Mat::Mat(Mat&& m) noexcept : flags(m.flags), dims(m.dims), rows(m.rows), cols(m.cols), data(m.data), datastart(m.datastart), dataend(m.dataend), datalimit(m.datalimit), allocator(m.allocator), u(m.u), size(&this->rows) {
        m.data = nullptr;
        m.u = nullptr;
    }

    Mat::~Mat() {}

    Mat& Mat::operator=(const Mat& m) {
        return *this;
    }

    Mat& Mat::operator=(Mat&& m) {
        return *this;
    }

    bool Mat::empty() const {
        return data == 0 || rows == 0 || cols == 0;
    }

    Mat Mat::clone() const {
        std::cerr << "CRITICAL WARNING: Stub cv::Mat::clone() called." << std::endl;
        return Mat();
    }

    void hconcat(const _InputArray& src, const _OutputArray& dst) {
        std::cerr << "CRITICAL WARNING: Stub cv::hconcat called." << std::endl;
    }

    bool imwrite(const String& filename, InputArray img, const std::vector<int>& params) {
        std::cerr << "CRITICAL WARNING: Stub cv::imwrite called for: " << filename << std::endl;
        return true;
    }

    void resize(InputArray src, OutputArray dst, Size dsize, double fx, double fy, int interpolation) {
        std::cerr << "CRITICAL WARNING: Stub cv::resize called." << std::endl;
    }

    void cvtColor(InputArray src, OutputArray dst, int code, int dstCn) {
        std::cerr << "CRITICAL WARNING: Stub cv::cvtColor called." << std::endl;
    }

}
