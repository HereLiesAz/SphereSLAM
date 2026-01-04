#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vector>
#include <mutex>

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

// Thread safety for Pose
std::mutex mMutexPose;
cv::Mat mCurrentPose = cv::Mat::eye(4, 4, CV_32F);

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
        std::vector<cv::Mat> faces;
        if (matAddr != 0) {
            cv::Mat* pMat = (cv::Mat*)matAddr;
            // faces.push_back(*pMat);
        }

        cv::Mat Tcw = slamSystem->TrackCubeMap(faces, timestamp);

        // Update Pose for Renderer
        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            if (!Tcw.empty()) {
                mCurrentPose = Tcw.clone();
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_setNativeWindow(JNIEnv* env, jobject thiz, jobject surface) {
    if (renderer) {
        ANativeWindow* window = nullptr;
        if (surface != nullptr) {
            window = ANativeWindow_fromSurface(env, surface);
        }
        renderer->setWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_renderFrame(JNIEnv* env, jobject thiz) {
    if (renderer) {
        // Get latest pose
        cv::Mat pose;
        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            pose = mCurrentPose.clone();
        }

        // Convert cv::Mat to glm::mat4
        // Logic to convert Tcw to View Matrix
        glm::mat4 viewMatrix(1.0f); // Identity placeholder
        glm::mat4 projMatrix(1.0f); // Identity placeholder

        renderer->updateCamera(viewMatrix, projMatrix);
        renderer->draw();
    }
}
