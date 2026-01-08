#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>

#include "SLAM/System.h"
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

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_initNative(JNIEnv* env, jobject thiz, jobject assetManager, jstring cacheDir) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    // Convert cacheDir to string
    const char *path = env->GetStringUTFChars(cacheDir, 0);
    std::string strCacheDir(path);
    env->ReleaseStringUTFChars(cacheDir, path);

    // Initialize Platform Layer
    platformAndroid = new PlatformAndroid(mgr, g_vm);

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
    slamSystem = new System("", "", System::IMU_MONOCULAR, platformAndroid, false); // Enable IMU mode

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
    if (platformAndroid) {
        delete platformAndroid;
        platformAndroid = nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Native Systems Destroyed");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_getBufferAddress(JNIEnv* env, jobject thiz, jobject buffer) {
    void* address = env->GetDirectBufferAddress(buffer);
    if (address == nullptr) {
        return 0L;
    }
    return (jlong)address;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_processFrame(JNIEnv* env, jobject thiz, jlong matAddr, jdouble timestamp, jint width, jint height, jint stride) {
    if (slamSystem && vulkanCompute) {
        // Pipeline: Input -> Vulkan (Equirect to Cubemap) -> SLAM

        // Cast address to raw pointer
        uint8_t* ptr = reinterpret_cast<uint8_t*>(matAddr);
        if (!ptr) return;

        // Construct cv::Mat wrapper around raw data
        // Use stride if it differs from width
        size_t step = (stride > 0) ? (size_t)stride : cv::Mat::AUTO_STEP;
        cv::Mat inputWrapper(height, width, CV_8UC1, ptr, step);

        // Clone to persist data for background processing
        cv::Mat inputImage = inputWrapper.clone();

        // 1. Process Input Image on GPU
        // In a real implementation, we would pass the texture or buffer to Vulkan
        // For now, we assume the input is suitable for processing.
        // We pass the raw data pointer from the clone, or the clone itself if VulkanCompute accepts it.
        // Since VulkanCompute->processImage takes void*, we pass the data pointer of the clone.
        vulkanCompute->processImage(inputImage.data, width, height);

        // 2. Retrieve Output Faces
        // Conceptual: Get 6 cv::Mat or buffers from VulkanCompute output
        std::vector<cv::Mat> faces;
        // Mocking the output:
        // for(int i=0; i<6; ++i) faces.push_back(vulkanCompute->getOutputFace(i));

        // For Blueprint: Push the input image as a dummy face to keep pipeline moving
        // (Assuming monocular or specific testing setup)
        faces.push_back(inputImage);

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

namespace {
void executeWithFilePath(JNIEnv* env, jstring jpath, void (System::*func)(const std::string&)) {
    if (!slamSystem) return;
    const char* path_str = env->GetStringUTFChars(jpath, nullptr);
    if (!path_str) {
        return; // An exception has been thrown by GetStringUTFChars
    }
    std::string path(path_str);
    env->ReleaseStringUTFChars(jpath, path_str);
    (slamSystem->*func)(path);
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_saveMap(JNIEnv* env, jobject thiz, jstring filePath) {
    executeWithFilePath(env, filePath, &System::SaveMap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_SphereSLAM_savePhotosphere(JNIEnv* env, jobject thiz, jstring filePath) {
    executeWithFilePath(env, filePath, &System::SavePhotosphere);
}
