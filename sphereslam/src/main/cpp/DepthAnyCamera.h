#ifndef DEPTH_ANY_CAMERA_H
#define DEPTH_ANY_CAMERA_H

#include <string>
#include <vector>
#include <android/asset_manager.h>

// Mock types if TFLite is not available
#ifdef USE_TFLITE
#include "tensorflow/lite/c/c_api.h"
#else
// Define dummy types to compile the header
typedef struct TfLiteModel TfLiteModel;
typedef struct TfLiteInterpreterOptions TfLiteInterpreterOptions;
typedef struct TfLiteInterpreter TfLiteInterpreter;
#endif

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
