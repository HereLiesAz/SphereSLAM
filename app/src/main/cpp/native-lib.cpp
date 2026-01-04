#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>

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

    // slamSystem = new System(...); // Needs proper arguments

    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp) {
    if (slamSystem && matAddr != 0) {
        cv::Mat* pMat = (cv::Mat*)matAddr;
        slamSystem->TrackMonocular(*pMat, timestamp);
    } else {
         // __android_log_print(ANDROID_LOG_WARN, TAG, "Skipping frame: System not init or invalid matAddr");
    }
}
