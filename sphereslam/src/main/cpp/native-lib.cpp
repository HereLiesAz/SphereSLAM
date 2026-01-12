#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>
#include <glm/gtc/matrix_transform.hpp>

#include "SLAM/System.h"
#include "SLAM/KeyFrame.h"
#include "VulkanCompute.h"
#include "DepthAnyCamera.h"
#include "MobileGS.h"
#include "Densifier.h"
#include "PlatformAndroid.h"

#define TAG "SphereSLAM-Native"

// Global Pointers
JavaVM* g_vm = nullptr;
System* slamSystem = nullptr;
VulkanCompute* vulkanCompute = nullptr;
DepthAnyCamera* depthEstimator = nullptr;
MobileGS* renderer = nullptr;
PlatformAndroid* platformAndroid = nullptr;

// Thread safety for Pose
std::mutex mMutexPose;
cv::Mat mCurrentPose = cv::Mat::eye(4, 4, CV_32F);
int screenWidth = 1080;
int screenHeight = 1920;

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_initNative(JNIEnv* env, jobject thiz, jobject assetManager, jstring cacheDir) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    const char *path = env->GetStringUTFChars(cacheDir, 0);
    std::string strCacheDir(path);
    env->ReleaseStringUTFChars(cacheDir, path);

    KeyFrame::msCacheDir = strCacheDir;
    platformAndroid = new PlatformAndroid(mgr, g_vm);
    vulkanCompute = new VulkanCompute(mgr);
    vulkanCompute->initialize();

    depthEstimator = new DepthAnyCamera(mgr);
    depthEstimator->initialize(strCacheDir);

    renderer = new MobileGS();
    renderer->initialize();

    slamSystem = new System("", "", System::IMU_MONOCULAR, platformAndroid, false);
    Densifier* densifier = new Densifier(depthEstimator);
    slamSystem->SetDensifier(densifier);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_destroyNative(JNIEnv* env, jobject thiz) {
    if (slamSystem) { delete slamSystem; slamSystem = nullptr; }
    if (vulkanCompute) { delete vulkanCompute; vulkanCompute = nullptr; }
    if (depthEstimator) { delete depthEstimator; depthEstimator = nullptr; }
    if (renderer) { delete renderer; renderer = nullptr; }
    if (platformAndroid) { delete platformAndroid; platformAndroid = nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getBufferAddress(JNIEnv* env, jobject thiz, jobject buffer) {
    void* address = env->GetDirectBufferAddress(buffer);
    return (jlong)address;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp, jint width, jint height, jint stride) {
    if (slamSystem && vulkanCompute) {
        uint8_t* ptr = reinterpret_cast<uint8_t*>(matAddr);
        if (!ptr) return;

        size_t step = (stride > 0) ? (size_t)stride : cv::Mat::AUTO_STEP;
        cv::Mat inputWrapper(height, width, CV_8UC1, ptr, step);
        cv::Mat inputImage = inputWrapper.clone();

        vulkanCompute->processImage(inputImage.data, width, height);
        std::vector<cv::Mat> faces = vulkanCompute->getAllOutputFaces();

        if (faces.size() != 6) {
             faces.clear();
             for(int i=0; i<6; ++i) faces.push_back(inputImage.clone());
        }

        cv::Mat Tcw = slamSystem->TrackCubeMap(faces, timestamp);

        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            if (!Tcw.empty()) {
                mCurrentPose = Tcw.clone();
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_processIMU(JNIEnv* env, jobject thiz, jint type, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    if (slamSystem) {
        double t = (double)timestamp / 1e9;
        cv::Point3f data(x, y, z);
        if (type == 1) slamSystem->ProcessIMU(data, t, 0);
        else if (type == 4) slamSystem->ProcessIMU(data, t, 1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_setNativeWindow(JNIEnv* env, jobject thiz, jobject surface) {
    if (renderer) {
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
        if (window) {
            screenWidth = ANativeWindow_getWidth(window);
            screenHeight = ANativeWindow_getHeight(window);
        }
        renderer->setWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_renderFrame(JNIEnv* env, jobject thiz) {
    if (renderer) {
        cv::Mat pose;
        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            pose = mCurrentPose.clone();
        }

        glm::mat4 viewMatrix(1.0f);
        if (!pose.empty() && pose.rows == 4 && pose.cols == 4) {
            for(int i=0; i<4; ++i) {
                for(int j=0; j<4; ++j) {
                    viewMatrix[j][i] = pose.at<float>(i, j);
                }
            }
        }

        // Apply a proper projection matrix
        float aspect = (float)screenWidth / (float)screenHeight;
        glm::mat4 projMatrix = glm::perspective(glm::radians(60.0f), aspect, 0.1f, 100.0f);

        renderer->updateCamera(viewMatrix, projMatrix);
        renderer->draw();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_manipulateView(JNIEnv* env, jobject thiz, jfloat dx, jfloat dy) {
    if (renderer) renderer->handleInput(dx, dy);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getTrackingState(JNIEnv* env, jobject thiz) {
    return slamSystem ? slamSystem->GetTrackingState() : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_resetSystem(JNIEnv* env, jobject thiz) {
    if (slamSystem) slamSystem->Reset();
    if (renderer) renderer->reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getMapStats(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(slamSystem ? slamSystem->GetMapStats().c_str() : "System Not Init");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_saveMap(JNIEnv* env, jobject thiz, jstring filePath) {
    if (!slamSystem) return;
    const char* p = env->GetStringUTFChars(filePath, nullptr);
    slamSystem->SaveMap(p);
    env->ReleaseStringUTFChars(filePath, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_savePhotosphere(JNIEnv* env, jobject thiz, jstring filePath) {
    if (!slamSystem) return;
    const char* p = env->GetStringUTFChars(filePath, nullptr);
    slamSystem->SavePhotosphere(p);
    env->ReleaseStringUTFChars(filePath, p);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_loadMap(JNIEnv* env, jobject thiz, jstring filePath) {
    const char* p = env->GetStringUTFChars(filePath, nullptr);
    std::string path(p);
    env->ReleaseStringUTFChars(filePath, p);
    if (!slamSystem) return false;
    bool success = slamSystem->LoadMap(path);
    if (success && renderer) {
        renderer->reset();
        auto vMPs = slamSystem->GetAllMapPoints();
        std::vector<Gaussian> gs;
        for(auto mp : vMPs) {
            cv::Point3f pos = mp->GetWorldPos();
            Gaussian g;
            g.position = glm::vec3(pos.x, pos.y, pos.z);
            g.rotation = {1, 0, 0, 0};
            g.scale = {0.05, 0.05, 0.05};
            g.opacity = 1.0;
            g.color_sh = {1, 1, 1};
            gs.push_back(g);
        }
        renderer->addGaussians(gs);
    }
    return success;
}
