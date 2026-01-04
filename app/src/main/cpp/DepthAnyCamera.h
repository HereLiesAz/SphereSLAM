#ifndef DEPTH_ANY_CAMERA_H
#define DEPTH_ANY_CAMERA_H

#include <string>
#include <vector>
#include <android/asset_manager.h>

// Forward declaration for TFLite Interpreter
namespace tflite {
    class Interpreter;
    class FlatBufferModel;
}

// Mock TFLite C API Types
typedef struct TfLiteModel TfLiteModel;
typedef struct TfLiteInterpreterOptions TfLiteInterpreterOptions;
typedef struct TfLiteInterpreter TfLiteInterpreter;
typedef struct TfLiteTensor TfLiteTensor;

struct DacState {
    TfLiteModel* model = nullptr;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* options = nullptr;
};

class DepthAnyCamera {
public:
    DepthAnyCamera(AAssetManager* assetManager);
    ~DepthAnyCamera();

    bool initialize(const std::string& cacheDir);

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
