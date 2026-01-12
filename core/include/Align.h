#ifndef ALIGN_H
#define ALIGN_H

#include <vector>
#include <opencv2/core.hpp>
#include "db_Types.h"

namespace lightcycle {

class Align {
public:
    Align();
    ~Align();

    int addFrame(const cv::Mat& image, float* rotationMatrix, std::vector<Frame>& frames);

private:
    void detectFeatures(const cv::Mat& image, std::vector<Feature>& features);
    void matchFeatures(const std::vector<Feature>& f1, const std::vector<Feature>& f2, std::vector<Match>& matches);
    int runRANSAC(const std::vector<Match>& matches, cv::Mat& refinedRotation);
    int findOverlappingFrameS2(float* rotationMatrix, const std::vector<Frame>& frames);
};

} // namespace lightcycle

#endif // ALIGN_H
