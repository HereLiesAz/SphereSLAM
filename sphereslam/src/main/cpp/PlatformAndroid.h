#ifndef PLATFORM_ANDROID_H
#define PLATFORM_ANDROID_H

#include "Platform.h"
#include <android/log.h>
#include <android/asset_manager.h>
#include <jni.h>

class PlatformAndroid : public Platform {
public:
    PlatformAndroid(AAssetManager* assetManager, JavaVM* jvm);
    ~PlatformAndroid() override;

    void Log(LogLevel level, const std::string& tag, const std::string& msg) override;

    bool LoadFile(const std::string& filename, std::vector<char>& buffer) override;

private:
    AAssetManager* mAssetManager;
    JavaVM* mJvm;
    jclass mSphereSLAMClass;
    jmethodID mLogMethodID;
};

#endif // PLATFORM_ANDROID_H
