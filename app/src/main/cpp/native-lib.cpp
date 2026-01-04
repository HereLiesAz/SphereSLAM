#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <vector>

#include "SLAM/System.h"
#include "VulkanCompute.h"
#include "DepthAnyCamera.h"
#include "MobileGS.h"

#define TAG "SphereSLAM-Native"

// Global Pointers
System* slamSystem = nullptr;
VulkanCompute* vulkanCompute = nullptr;
DepthAnyCamera* depthEstimator = nullptr;
MobileGS* renderer = nullptr;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sphereslam_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from SphereSLAM C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_initNative(JNIEnv* env, jobject thiz, jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    // Initialize Subsystems
    vulkanCompute = new VulkanCompute(mgr);
    vulkanCompute->initialize();

    depthEstimator = new DepthAnyCamera(mgr);
    // depthEstimator->initialize(); // Uncomment when model is available

    renderer = new MobileGS();
    renderer->initialize();

    // Initialize SLAM System
    // VocFile and SettingsFile would be paths to assets extracted to cache
    slamSystem = new System("", "", System::MONOCULAR, false);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp) {
    if (slamSystem && vulkanCompute) {
        // Conceptual flow:
        // 1. In a real app, matAddr would be the Equirectangular image.
        // 2. We pass it to VulkanCompute -> processImage(matAddr...)
        // 3. VulkanCompute outputs 6 faces (stored internally in textures or CPU accessible buffers).
        // 4. We retrieve those faces.

        // For Blueprint: We simulate receiving the faces.
        // If matAddr is valid, we assume it's one face or the equirect.
        // Let's assume for now we just pass a dummy vector to verify the pipeline.

        std::vector<cv::Mat> faces;
        if (matAddr != 0) {
            cv::Mat* pMat = (cv::Mat*)matAddr;
            // faces.push_back(*pMat); // Add the input as if it were a face
            // In reality, we would split or use the Vulkan output.
        }

        // Call TrackCubeMap (even with empty faces for now to trigger logic)
        slamSystem->TrackCubeMap(faces, timestamp);
    }
}
