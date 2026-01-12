#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <cmath>

#include "glm/glm.hpp"
#include "SLAM/System.h"
#include "SLAM/KeyFrame.h"
#include "Mosaic.h"
#include "MobileGS.h"
#include "Densifier.h"
#include "PlatformAndroid.h"

#define TAG "SphereSLAM-Native"

// Global Pointers
JavaVM* g_vm = nullptr;
System* slamSystem = nullptr;
lightcycle::Mosaic* mosaicer = nullptr;
MobileGS* renderer = nullptr;
PlatformAndroid* platformAndroid = nullptr;

// Thread safety for Pose
std::mutex mMutexPose;
cv::Mat mCurrentPose = cv::Mat::eye(4, 4, CV_32F);
glm::mat4 mManualPose = glm::mat4(1.0f);
bool mUseManualPose = false;

int screenWidth = 1080;
int screenHeight = 1920;

namespace glm_ext {
    inline glm::mat4 perspective(float fovy, float aspect, float zNear, float zFar) {
        float const tanHalfFovy = tan(fovy / 2.0f);
        glm::mat4 Result(0.0f);
        Result[0][0] = 1.0f / (aspect * tanHalfFovy);
        Result[1][1] = 1.0f / (tanHalfFovy);
        Result[2][2] = -(zFar + zNear) / (zFar - zNear);
        Result[2][3] = -1.0f;
        Result[3][2] = -(2.0f * zFar * zNear) / (zFar - zNear);
        return Result;
    }
    inline float radians(float degrees) { return degrees * 0.0174532925f; }
}

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
    renderer = new MobileGS();
    renderer->initialize();

    slamSystem = new System("", "", System::IMU_MONOCULAR, platformAndroid, false);
    mosaicer = new lightcycle::Mosaic();

    __android_log_print(ANDROID_LOG_INFO, TAG, "LightCycle Native Initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_destroyNative(JNIEnv* env, jobject thiz) {
    if (slamSystem) { delete slamSystem; slamSystem = nullptr; }
    if (mosaicer) { delete mosaicer; mosaicer = nullptr; }
    if (renderer) { delete renderer; renderer = nullptr; }
    if (platformAndroid) { delete platformAndroid; platformAndroid = nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getBufferAddress(JNIEnv* env, jobject thiz, jobject buffer) {
    return (jlong)env->GetDirectBufferAddress(buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp, jint width, jint height, jint stride) {
    uint8_t* ptr = reinterpret_cast<uint8_t*>(matAddr);
    if (!ptr) return;

    size_t step = (stride > 0) ? (size_t)stride : cv::Mat::AUTO_STEP;
    cv::Mat inputWrapper(height, width, CV_8UC1, ptr, step);
    cv::Mat inputImage = inputWrapper.clone();

    cv::rotate(inputImage, inputImage, cv::ROTATE_90_CLOCKWISE);

    if (renderer) renderer->updateBackground(inputImage);

    if (slamSystem) {
        std::vector<cv::Mat> faces;
        for(int i=0; i<6; ++i) faces.push_back(inputImage.clone());
        cv::Mat Tcw = slamSystem->TrackCubeMap(faces, timestamp);
        std::unique_lock<std::mutex> lock(mMutexPose);
        if (!Tcw.empty()) {
            mCurrentPose = Tcw.clone();
            mUseManualPose = false;
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
Java_com_hereliesaz_sphereslam_SphereSLAM_setCameraPose(JNIEnv* env, jobject thiz, jfloatArray matrix) {
    jfloat* m = env->GetFloatArrayElements(matrix, nullptr);
    {
        std::unique_lock<std::mutex> lock(mMutexPose);
        for(int i=0; i<4; ++i) for(int j=0; j<4; ++j) mManualPose[i][j] = m[i*4 + j];
        mUseManualPose = true;
    }
    env->ReleaseFloatArrayElements(matrix, m, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_renderFrame(JNIEnv* env, jobject thiz) {
    if (renderer) {
        glm::mat4 viewMatrix(1.0f);
        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            if (mUseManualPose) viewMatrix = mManualPose;
            else {
                for(int i=0; i<4; ++i) for(int j=0; j<4; ++j) viewMatrix[j][i] = mCurrentPose.at<float>(i, j);
            }
        }
        float aspect = (float)screenWidth / (float)screenHeight;
        glm::mat4 projMatrix = glm_ext::perspective(glm_ext::radians(60.0f), aspect, 0.1f, 100.0f);
        renderer->updateCamera(viewMatrix, projMatrix);
        renderer->draw();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_allocateMosaicMemory(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (mosaicer) mosaicer->allocateMosaicMemory(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_freeMosaicMemory(JNIEnv* env, jobject thiz) {
    if (mosaicer) mosaicer->freeMosaicMemory();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_addFrameToMosaic(JNIEnv* env, jobject thiz, jbyteArray imageYVU, jfloatArray rotationMatrix) {
    if (!mosaicer) return -1;
    jbyte* imgData = env->GetByteArrayElements(imageYVU, nullptr);
    jfloat* rotData = env->GetFloatArrayElements(rotationMatrix, nullptr);

    // LightCycle engine expects a specific YVU format.
    // For this blueprint, we wrap it in cv::Mat.
    // In actual implementation, we'd use the width/height from allocateMosaicMemory.
    // Assuming 1280x720 for now or passed from somewhere.

    // Mosaicer::addFrame Reconstruction
    int result = mosaicer->addFrame(reinterpret_cast<unsigned char*>(imgData), rotData);

    env->ReleaseByteArrayElements(imageYVU, imgData, JNI_ABORT);
    env->ReleaseFloatArrayElements(rotationMatrix, rotData, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_setCaptureTargets(JNIEnv* env, jobject thiz, jfloatArray positions, jbooleanArray captured) {
    if (renderer) {
        jsize len = env->GetArrayLength(positions);
        float* p = env->GetFloatArrayElements(positions, nullptr);
        jboolean* c = env->GetBooleanArrayElements(captured, nullptr);
        std::vector<glm::vec3> targets;
        std::vector<bool> capturedFlags;
        for (int i = 0; i < len / 3; ++i) {
            targets.push_back(glm::vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]));
            capturedFlags.push_back(c[i] == JNI_TRUE);
        }
        renderer->setCaptureTargets(targets, capturedFlags);
        env->ReleaseFloatArrayElements(positions, p, JNI_ABORT);
        env->ReleaseBooleanArrayElements(captured, c, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_setTargetSize(JNIEnv* env, jobject thiz, jfloat pixels) {
    if (renderer) renderer->setTargetSize(pixels);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getTrackingState(JNIEnv* env, jobject thiz) {
    return slamSystem ? slamSystem->GetTrackingState() : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_resetSystem(JNIEnv* env, jobject thiz) {
    if (slamSystem) slamSystem->Reset();
    if (renderer) renderer->reset();
    if (mosaicer) mosaicer->freeMosaicMemory();
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_savePhotosphere(JNIEnv* env, jobject thiz, jstring filePath) {
    if (!mosaicer) return;
    const char* p = env->GetStringUTFChars(filePath, nullptr);
    mosaicer->createMosaic(true, p);
    env->ReleaseStringUTFChars(filePath, p);
}
