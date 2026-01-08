#include "DepthAnyCamera.h"
#include <android/log.h>
#include <vector>
#include <cstdio>
#include <fstream>
#include <opencv2/opencv.hpp>

#define TAG "DepthAnyCamera"
// Define to enable LiteRT when SDK is available
// #define USE_LITERT

DepthAnyCamera::DepthAnyCamera(AAssetManager* assetManager) : assetManager(assetManager) {
}

DepthAnyCamera::~DepthAnyCamera() {
#ifdef USE_LITERT
    if (dacCtx.interpreter) LiteRtInterpreterDelete(dacCtx.interpreter);
    if (dacCtx.options) LiteRtInterpreterOptionsDelete(dacCtx.options);
    if (dacCtx.model) LiteRtModelDelete(dacCtx.model);
#endif
}

bool DepthAnyCamera::initialize(const std::string& cachePath) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing DepthAnyCamera model (LiteRT)...");

#ifdef USE_LITERT
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
        __android_log_print(ANDROID_LOG_WARN, TAG, "Model asset not found");
    }

    // 1. Load Model using LiteRT C API
    dacCtx.model = LiteRtModelCreateFromFile(modelPath.c_str());
    if (!dacCtx.model) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model from %s", modelPath.c_str());
        return false;
    }

    // 2. Create Options
    dacCtx.options = LiteRtInterpreterOptionsCreate();
    LiteRtInterpreterOptionsSetNumThreads(dacCtx.options, 4); // Use 4 threads (Big cores)

    // Check if GPU delegate is available (Optional / Future work)
    // LiteRtDelegate* gpuDelegate = LiteRtGpuDelegateV2Create(nullptr);
    // if (gpuDelegate) LiteRtInterpreterOptionsAddDelegate(dacCtx.options, gpuDelegate);

    // 3. Create Interpreter
    dacCtx.interpreter = LiteRtInterpreterCreate(dacCtx.model, dacCtx.options);
    if (!dacCtx.interpreter) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create interpreter");
        return false;
    }

    // 4. Allocate Tensors
    if (LiteRtInterpreterAllocateTensors(dacCtx.interpreter) != kLiteRtOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to allocate tensors");
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "DAC Model Initialized");
    return true;
#else
    __android_log_print(ANDROID_LOG_WARN, TAG, "LiteRT SDK not linked. Depth estimation disabled.");
    return false;
#endif
}

std::vector<float> DepthAnyCamera::estimateDepth(void* inputBuffer, int width, int height) {
#ifdef USE_LITERT
    if (!dacCtx.interpreter) return {};

    // 1. Prepare Input
    // Model expects 512x256 RGB float32
    const int modelWidth = 512;
    const int modelHeight = 256;
    const int channels = 3;

    // Use stride if necessary, but here we assume inputBuffer is packed or handle it via Mat
    cv::Mat inputWrapper(height, width, CV_8UC3, inputBuffer);

    cv::Mat resized;
    cv::resize(inputWrapper, resized, cv::Size(modelWidth, modelHeight));

    cv::Mat floatMat;
    resized.convertTo(floatMat, CV_32FC3, 1.0f / 255.0f); // Normalize 0.0 - 1.0

    LiteRtTensor* inputTensor = LiteRtInterpreterGetInputTensor(dacCtx.interpreter, 0);

    // Check input tensor size to be sure
    // int inputDims[4] = {1, 256, 512, 3}; // Check against actual model

    // Copy data
    if (floatMat.isContinuous()) {
        LiteRtTensorCopyFromBuffer(inputTensor, floatMat.data, floatMat.total() * floatMat.elemSize());
    } else {
         __android_log_print(ANDROID_LOG_ERROR, TAG, "Input Mat is not continuous");
         return {};
    }

    // 2. Inference
    if (LiteRtInterpreterInvoke(dacCtx.interpreter) != kLiteRtOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Inference failed");
        return {};
    }

    // 3. Extract Output
    const LiteRtTensor* outputTensor = LiteRtInterpreterGetOutputTensor(dacCtx.interpreter, 0);
    // Assuming output is float32
    size_t outputSize = LiteRtTensorByteSize(outputTensor);
    int numPixels = outputSize / sizeof(float);

    std::vector<float> depthMap(numPixels);
    // Check if copy is successful
    if (LiteRtTensorCopyToBuffer(outputTensor, depthMap.data(), outputSize) != kLiteRtOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to copy output tensor");
        return {};
    }

    return depthMap;
#else
    return {};
#endif
}
