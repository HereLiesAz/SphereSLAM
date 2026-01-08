#include "DepthAnyCamera.h"
#include <android/log.h>
#include <vector>
#include <cstdio>
#include <fstream>
#include <opencv2/opencv.hpp>

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
        // In case the model is already there or we want to fail gracefully later
    }

    // 1. Load Model
    dacCtx.model = TfLiteModelCreateFromFile(modelPath.c_str());
    if (!dacCtx.model) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model from %s", modelPath.c_str());
        return false;
    }

    // 2. Create Options
    dacCtx.options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(dacCtx.options, 4); // Use 4 threads (Big cores)

    // Check if GPU delegate is available (Optional / Future work)
    // TfLiteDelegate* gpuDelegate = TfLiteGpuDelegateV2Create(nullptr);
    // if (gpuDelegate) TfLiteInterpreterOptionsAddDelegate(dacCtx.options, gpuDelegate);

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
    // Model expects 512x256 RGB float32
    const int modelWidth = 512;
    const int modelHeight = 256;
    const int channels = 3;

    // Use stride if necessary, but here we assume inputBuffer is packed or handle it via Mat
    cv::Mat inputWrapper(height, width, CV_8UC3, inputBuffer);

    // Convert to RGB if needed (OpenCV is BGR usually, but input might be RGB depending on source)
    // Assuming input is RGB or BGR. Let's assume RGB from standard Android bitmaps/buffers if not specified.
    // If it comes from OpenCV (BGR), we need to convert.
    // Usually TFLite models expect RGB.

    cv::Mat resized;
    cv::resize(inputWrapper, resized, cv::Size(modelWidth, modelHeight));

    cv::Mat floatMat;
    resized.convertTo(floatMat, CV_32FC3, 1.0f / 255.0f); // Normalize 0.0 - 1.0

    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(dacCtx.interpreter, 0);

    // Check input tensor size to be sure
    // int inputDims[4] = {1, 256, 512, 3}; // Check against actual model

    // Copy data
    // floatMat is continuous?
    if (floatMat.isContinuous()) {
        TfLiteTensorCopyFromBuffer(inputTensor, floatMat.data, floatMat.total() * floatMat.elemSize());
    } else {
        // Fallback row-by-row
         __android_log_print(ANDROID_LOG_ERROR, TAG, "Input Mat is not continuous");
         return {};
    }

    // 2. Inference
    if (TfLiteInterpreterInvoke(dacCtx.interpreter) != kTfLiteOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Inference failed");
        return {};
    }

    // 3. Extract Output
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(dacCtx.interpreter, 0);
    // Assuming output is float32
    size_t outputSize = TfLiteTensorByteSize(outputTensor);
    int numPixels = outputSize / sizeof(float);

    std::vector<float> depthMap(numPixels);
    // Check if copy is successful
    if (TfLiteTensorCopyToBuffer(outputTensor, depthMap.data(), outputSize) != kTfLiteOk) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to copy output tensor");
        return {};
    }

    return depthMap;
}
