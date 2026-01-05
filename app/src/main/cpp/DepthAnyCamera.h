#ifndef DEPTH_ANY_CAMERA_H
#define DEPTH_ANY_CAMERA_H

#include <string>
#include <vector>
#include <android/asset_manager.h>
#include "tensorflow/lite/c/c_api.h"

struct DacState {
    TfLiteModel* model = nullptr;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* options = nullptr;
};

class DepthAnyCamera {
public:
    DepthAnyCamera(AAssetManager* assetManager);
    ~DepthAnyCamera();

    bool initialize(const std::string& cachePath);

    // Run inference on an equirectangular image
    // Input: RGB byte buffer (or AHardwareBuffer)
    // Output: Float array of metric depth values
    std::vector<float> estimateDepth(void* inputBuffer, int width, int height);

private:
    AAssetManager* assetManager;
    DacState dacCtx;

    const std::string MODEL_FILENAME = "dac_360_int8.tflite";
};

#endif // DEPTH_ANY_CAMERA_H
