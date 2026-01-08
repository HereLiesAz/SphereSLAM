#include "PlatformAndroid.h"

PlatformAndroid::PlatformAndroid(AAssetManager* assetManager, JavaVM* jvm)
    : mAssetManager(assetManager), mJvm(jvm), mSphereSLAMClass(nullptr), mLogMethodID(nullptr) {
    if (mJvm) {
        JNIEnv* env;
        if (mJvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            jclass localClass = env->FindClass("com/hereliesaz/sphereslam/SphereSLAM");
            if (localClass) {
                mSphereSLAMClass = (jclass)env->NewGlobalRef(localClass);
                mLogMethodID = env->GetStaticMethodID(mSphereSLAMClass, "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
            }
        }
    }
}

PlatformAndroid::~PlatformAndroid() {
    if (mJvm && mSphereSLAMClass) {
        JNIEnv* env;
        if (mJvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(mSphereSLAMClass);
        }
    }
}

void PlatformAndroid::Log(LogLevel level, const std::string& tag, const std::string& msg) {
    int androidLevel;
    switch (level) {
        case LogLevel::INFO: androidLevel = ANDROID_LOG_INFO; break;
        case LogLevel::WARN: androidLevel = ANDROID_LOG_WARN; break;
        case LogLevel::ERROR: androidLevel = ANDROID_LOG_ERROR; break;
        default: androidLevel = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(androidLevel, tag.c_str(), "%s", msg.c_str());

    if (mJvm && mSphereSLAMClass && mLogMethodID) {
        JNIEnv* env;
        int getEnvStat = mJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        bool attached = false;
        if (getEnvStat == JNI_EDETACHED) {
             if (mJvm->AttachCurrentThread(&env, nullptr) != 0) {
                 return;
             }
             attached = true;
        } else if (getEnvStat != JNI_OK) {
             return;
        }

        jstring jTag = env->NewStringUTF(tag.c_str());
        jstring jMsg = env->NewStringUTF(msg.c_str());
        env->CallStaticVoidMethod(mSphereSLAMClass, mLogMethodID, androidLevel, jTag, jMsg);
        if (jTag) env->DeleteLocalRef(jTag);
        if (jMsg) env->DeleteLocalRef(jMsg);

        if (attached) {
            mJvm->DetachCurrentThread();
        }
    }
}

bool PlatformAndroid::LoadFile(const std::string& filename, std::vector<char>& buffer) {
    if (!mAssetManager) return false;

    AAsset* asset = AAssetManager_open(mAssetManager, filename.c_str(), AASSET_MODE_BUFFER);
    if (!asset) return false;

    off_t length = AAsset_getLength(asset);
    buffer.resize(length);
    int read = AAsset_read(asset, buffer.data(), length);
    AAsset_close(asset);

    return read == length;
}
