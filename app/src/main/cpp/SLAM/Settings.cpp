#include "Settings.h"
#include <iostream>

Settings::Settings(const std::string &filename) : bValid(false) {
    // Stub implementation: Hardcode defaults since we can't parse YAML easily without dependency
    // In a real app, use OpenCV FileStorage or yaml-cpp

    // Defaults for CubeMap Face (e.g. 512x512, 90 deg FOV)
    width = 512;
    height = 512;
    fx = width / 2.0f;
    fy = height / 2.0f;
    cx = width / 2.0f;
    cy = height / 2.0f;

    nFeatures = 1000;
    scaleFactor = 1.2f;
    nLevels = 8;
    iniThFAST = 20;
    minThFAST = 7;

    bValid = true;

    std::cout << "Settings loaded (Defaults) from " << filename << std::endl;
}

bool Settings::isValid() {
    return bValid;
}
