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

class DepthAnyCamera {
public:
    DepthAnyCamera(AAssetManager* assetManager);
    ~DepthAnyCamera();

    bool initialize();

    // Run inference on an equirectangular image
    // Input: RGB byte buffer (or AHardwareBuffer)
    // Output: Float array of metric depth values
    std::vector<float> estimateDepth(void* inputBuffer, int width, int height);

private:
    AAssetManager* assetManager;
    // std::unique_ptr<tflite::FlatBufferModel> model;
    // std::unique_ptr<tflite::Interpreter> interpreter;

    const std::string MODEL_FILENAME = "dac_360_int8.tflite";
};

#endif // DEPTH_ANY_CAMERA_H
