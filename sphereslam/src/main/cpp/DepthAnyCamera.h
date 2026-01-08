#ifndef DEPTH_ANY_CAMERA_H
#define DEPTH_ANY_CAMERA_H

#include <string>
#include <vector>
#include <android/asset_manager.h>

// LiteRT Headers (Expected path when SDK is linked)
#ifdef USE_LITERT
#include "litert/c/c_api.h"
#else
// Define dummy types to compile the header without SDK
typedef struct LiteRtModel LiteRtModel;
typedef struct LiteRtInterpreterOptions LiteRtInterpreterOptions;
typedef struct LiteRtInterpreter LiteRtInterpreter;
typedef struct LiteRtTensor LiteRtTensor;
#endif

struct DacState {
    LiteRtModel* model = nullptr;
    LiteRtInterpreter* interpreter = nullptr;
    LiteRtInterpreterOptions* options = nullptr;
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
