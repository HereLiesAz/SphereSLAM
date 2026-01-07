#ifndef SETTINGS_H
#define SETTINGS_H

#include <string>
#include <opencv2/core.hpp>

class Settings {
public:
    Settings(const std::string &filename);

    // Camera Parameters
    int width;
    int height;
    float fx;
    float fy;
    float cx;
    float cy;

    // ORB Parameters
    int nFeatures;
    float scaleFactor;
    int nLevels;
    int iniThFAST;
    int minThFAST;

    bool isValid();

private:
    bool bValid;
};

#endif // SETTINGS_H
