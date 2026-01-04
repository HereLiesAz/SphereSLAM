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
    slamSystem = new System("", "", System::IMU_MONOCULAR, false); // Enable IMU mode

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
Java_com_example_sphereslam_MainActivity_processIMU(JNIEnv* env, jobject thiz, jint type, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    if (slamSystem) {
        // Type 1: Accel, Type 4: Gyro (Android constants)
        double t = (double)timestamp / 1e9;
        cv::Point3f data(x, y, z);

        if (type == 1) { // Accel
             slamSystem->ProcessIMU(data, t, 0); // 0 for Accel
        } else if (type == 4) { // Gyro
             slamSystem->ProcessIMU(data, t, 1); // 1 for Gyro
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

        glm::mat4 viewMatrix(1.0f);
        glm::mat4 projMatrix(1.0f);

        renderer->updateCamera(viewMatrix, projMatrix);
        renderer->draw();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_manipulateView(JNIEnv* env, jobject thiz, jfloat dx, jfloat dy) {
    if (renderer) {
        renderer->handleInput(dx, dy);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sphereslam_MainActivity_getTrackingState(JNIEnv* env, jobject thiz) {
    if (slamSystem) {
        return slamSystem->GetTrackingState();
    }
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sphereslam_MainActivity_resetSystem(JNIEnv* env, jobject thiz) {
    if (slamSystem) {
        slamSystem->Reset();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sphereslam_MainActivity_getMapStats(JNIEnv* env, jobject thiz) {
    std::string stats = "System not ready";
    if (slamSystem) {
        stats = slamSystem->GetMapStats();
    }
    return env->NewStringUTF(stats.c_str());
}
