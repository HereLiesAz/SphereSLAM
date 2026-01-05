#include "DepthAnyCamera.h"
#include <android/log.h>
#include <vector>
#include <cstdio>
#include <fstream>
#include "tensorflow/lite/c/c_api.h"

#define TAG "DepthAnyCamera"

DepthAnyCamera::DepthAnyCamera(AAssetManager* assetManager) : assetManager(assetManager) {
}

DepthAnyCamera::~DepthAnyCamera() {
    if (dacCtx.interpreter) TfLiteInterpreterDelete(dacCtx.interpreter);
    if (dacCtx.options) TfLiteInterpreterOptionsDelete(dacCtx.options);
    if (dacCtx.model) TfLiteModelDelete(dacCtx.model);
}

bool DepthAnyCamera::initialize(const std::string& cachePath) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing DepthAnyCamera model...");

    // Extract model from Assets to Cache Dir
    std::string modelPath = cachePath + "/" + MODEL_FILENAME;

    AAsset* asset = AAssetManager_open(assetManager, MODEL_FILENAME.c_str(), AASSET_MODE_BUFFER);
    if (asset) {
        std::ofstream outfile(modelPath, std::ios::binary);
        const void* buffer = AAsset_getBuffer(asset);
        off_t length = AAsset_getLength(asset);
        outfile.write((const char*)buffer, length);
        outfile.close();
        AAsset_close(asset);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Model extracted to %s", modelPath.c_str());
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Model asset not found, using stub path");
    }

    // 1. Load Model
    dacCtx.model = TfLiteModelCreateFromFile(modelPath.c_str());
    if (!dacCtx.model) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model");
        return false;
    }

    // 2. Create Options
    dacCtx.options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(dacCtx.options, std::thread::hardware_concurrency()); // Use available cores

    // 3. Create Interpreter
    dacCtx.interpreter = TfLiteInterpreterCreate(dacCtx.model, dacCtx.options);
    if (!dacCtx.interpreter) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create interpreter");
        return false;
    }

    // 4. Allocate Tensors
    if (TfLiteInterpreterAllocateTensors(dacCtx.interpreter) != kTfLiteOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to allocate tensors");
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "DAC Model Initialized");
    return true;
}

std::vector<float> DepthAnyCamera::estimateDepth(void* inputBuffer, int width, int height) {
    if (!dacCtx.interpreter) return {};

    // 1. Prepare Input
    // Assuming model expects 512x256 RGB float32
    int modelWidth = 512;
    int modelHeight = 256;
    int channels = 3;

    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(dacCtx.interpreter, 0);

    // Copy data (In reality: Resize and Normalize inputBuffer to model buffer)
    size_t inputSize = modelWidth * modelHeight * channels * sizeof(float);
    // TfLiteTensorCopyFromBuffer(inputTensor, inputBuffer, inputSize);

    // 2. Inference
    if (TfLiteInterpreterInvoke(dacCtx.interpreter) != kTfLiteOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Inference failed");
        return {};
    }

    // 3. Extract Output
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(dacCtx.interpreter, 0);
    size_t outputSize = TfLiteTensorByteSize(outputTensor);
    int numPixels = outputSize / sizeof(float);

    std::vector<float> depthMap(numPixels);
    TfLiteTensorCopyToBuffer(outputTensor, depthMap.data(), outputSize);

    return depthMap;
}
