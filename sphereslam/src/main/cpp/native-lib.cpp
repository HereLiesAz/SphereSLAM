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
#include "Densifier.h"

#define TAG "SphereSLAM-Native"

// Global Pointers
System* slamSystem = nullptr;
VulkanCompute* vulkanCompute = nullptr;
DepthAnyCamera* depthEstimator = nullptr;
MobileGS* renderer = nullptr;

// Thread safety for Pose
std::mutex mMutexPose;
cv::Mat mCurrentPose = cv::Mat::eye(4, 4, CV_32F);

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_initNative(JNIEnv* env, jobject thiz, jobject assetManager, jstring cacheDir) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    // Convert cacheDir to string
    const char *path = env->GetStringUTFChars(cacheDir, 0);
    std::string strCacheDir(path);
    env->ReleaseStringUTFChars(cacheDir, path);

    // Initialize Subsystems
    vulkanCompute = new VulkanCompute(mgr);
    vulkanCompute->initialize();

    depthEstimator = new DepthAnyCamera(mgr);
    // Pass cache dir for model extraction
    depthEstimator->initialize(strCacheDir);

    renderer = new MobileGS();
    renderer->initialize();

    // Initialize SLAM System
    // VocFile and SettingsFile would be paths to assets extracted to cache
    slamSystem = new System("", "", System::IMU_MONOCULAR, false); // Enable IMU mode

    // Wire up Densifier
    Densifier* densifier = new Densifier(depthEstimator);
    slamSystem->SetDensifier(densifier);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Initialized with Cache Dir: %s", strCacheDir.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_destroyNative(JNIEnv* env, jobject thiz) {
    if (slamSystem) {
        delete slamSystem;
        slamSystem = nullptr;
    }
    if (vulkanCompute) {
        delete vulkanCompute;
        vulkanCompute = nullptr;
    }
    if (depthEstimator) {
        delete depthEstimator;
        depthEstimator = nullptr;
    }
    if (renderer) {
        delete renderer;
        renderer = nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Destroyed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp) {
    if (slamSystem && vulkanCompute) {
        // Pipeline: Input -> Vulkan (Equirect to Cubemap) -> SLAM

        // 1. Process Input Image on GPU
        // In a real implementation, matAddr might be a AHardwareBuffer or TextureID
        vulkanCompute->processImage((void*)matAddr, 3840, 1920); // Assume 4K equirect input

        // 2. Retrieve Output Faces
        // Conceptual: Get 6 cv::Mat or buffers from VulkanCompute output
        std::vector<cv::Mat> faces;
        // Mocking the output:
        // for(int i=0; i<6; ++i) faces.push_back(vulkanCompute->getOutputFace(i));

        // For Blueprint: If matAddr is valid, we push it as a dummy face to keep pipeline moving
        if (matAddr != 0) {
            cv::Mat* pMat = (cv::Mat*)matAddr;
            faces.push_back(*pMat);
        }

        // 3. Track
        cv::Mat Tcw = slamSystem->TrackCubeMap(faces, timestamp);

        // 4. Update Pose for Renderer
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
        // Type 1: Accel, Type 4: Gyro (Android constants)
        // Timestamp is nanoseconds, convert to seconds if needed or keep consistent
        double t = (double)timestamp / 1e9;
        cv::Point3f data(x, y, z);

        // Map Android Sensor Types to internal enum or separate methods
        // 1 = ACCELEROMETER, 4 = GYROSCOPE
        if (type == 1) { // Accel
             slamSystem->ProcessIMU(data, t, 0); // 0 for Accel
        } else if (type == 4) { // Gyro
             slamSystem->ProcessIMU(data, t, 1); // 1 for Gyro
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_setNativeWindow(JNIEnv* env, jobject thiz, jobject surface) {
    if (renderer) {
        ANativeWindow* window = nullptr;
        if (surface != nullptr) {
            window = ANativeWindow_fromSurface(env, surface);
        }
        renderer->setWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_renderFrame(JNIEnv* env, jobject thiz) {
    if (renderer) {
        // Get latest pose
        cv::Mat pose;
        {
            std::unique_lock<std::mutex> lock(mMutexPose);
            pose = mCurrentPose.clone();
        }

        // Convert cv::Mat Tcw to glm::mat4 viewMatrix
        // OpenCV is row-major, GLM is column-major.
        // Tcw = [R | t]

        glm::mat4 viewMatrix(1.0f);

        if (!pose.empty() && pose.rows == 4 && pose.cols == 4) {
            // OpenCV (Row Major):
            // R00 R01 R02 Tx
            // R10 R11 R12 Ty
            // R20 R21 R22 Tz
            // 0   0   0   1

            // GLM (Column Major storage in mat4, but accessed as m[col][row]):
            // viewMatrix[0] is first column.

            // We want to copy R and t.
            // But wait, is MobileGS expecting View Matrix (World to Camera)?
            // Tcw is World to Camera. Yes.
            // But coordinate systems differ.
            // OpenCV Camera: X Right, Y Down, Z Forward
            // OpenGL Camera: X Right, Y Up, Z Backward (Look At -Z)
            // Need a conversion matrix.
            // For blueprint, direct copy is fine to show data flow.

            for(int i=0; i<4; ++i) {
                for(int j=0; j<4; ++j) {
                    viewMatrix[j][i] = pose.at<float>(i, j); // m[col][row]
                }
            }
        }

        glm::mat4 projMatrix(1.0f);

        renderer->updateCamera(viewMatrix, projMatrix);
        renderer->draw();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_manipulateView(JNIEnv* env, jobject thiz, jfloat dx, jfloat dy) {
    if (renderer) {
        renderer->handleInput(dx, dy);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getTrackingState(JNIEnv* env, jobject thiz) {
    if (slamSystem) {
        return slamSystem->GetTrackingState();
    }
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_resetSystem(JNIEnv* env, jobject thiz) {
    if (slamSystem) {
        slamSystem->Reset();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getMapStats(JNIEnv* env, jobject thiz) {
    std::string stats = "System not ready";
    if (slamSystem) {
        stats = slamSystem->GetMapStats();
    }
    return env->NewStringUTF(stats.c_str());
}
