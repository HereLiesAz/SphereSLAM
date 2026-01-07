#ifndef ORBEXTRACTOR_H
#define ORBEXTRACTOR_H

#include <vector>
#include <list>
#include <opencv2/core.hpp>

class ORBextractor {
public:
    enum {HARRIS_SCORE=0, FAST_SCORE=1 };

    ORBextractor(int nfeatures, float scaleFactor, int nlevels, int iniThFAST, int minThFAST);
    ~ORBextractor() {}

    // Compute the ORB features and descriptors on an image
    // mask is an optional mask to select the area to extract features
    void operator()(cv::Mat image, cv::Mat mask,
                    std::vector<cv::KeyPoint>& keypoints,
                    cv::Mat& descriptors);

    int inline GetLevels() {
        return nlevels;
    }

    float inline GetScaleFactor() {
        return scaleFactor;
    }

private:
    int nfeatures;
    double scaleFactor;
    int nlevels;
    int iniThFAST;
    int minThFAST;

    std::vector<int> mnFeaturesPerLevel;
    std::vector<float> mvScaleFactor;
    std::vector<float> mvInvScaleFactor;
};

#endif // ORBEXTRACTOR_H
