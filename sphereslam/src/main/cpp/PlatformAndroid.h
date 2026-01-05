#ifndef PLATFORM_ANDROID_H
#define PLATFORM_ANDROID_H

#include "Platform.h"
#include <android/log.h>
#include <android/asset_manager.h>

class PlatformAndroid : public Platform {
public:
    PlatformAndroid(AAssetManager* assetManager) : mAssetManager(assetManager) {}

    void Log(LogLevel level, const std::string& tag, const std::string& msg) override {
        int androidLevel;
        switch (level) {
            case LogLevel::INFO: androidLevel = ANDROID_LOG_INFO; break;
            case LogLevel::WARN: androidLevel = ANDROID_LOG_WARN; break;
            case LogLevel::ERROR: androidLevel = ANDROID_LOG_ERROR; break;
            default: androidLevel = ANDROID_LOG_DEBUG; break;
        }
        __android_log_print(androidLevel, tag.c_str(), "%s", msg.c_str());
    }

    bool LoadFile(const std::string& filename, std::vector<char>& buffer) override {
        // Filename is expected to be a path in assets
        // However, System currently takes paths to extracted files on disk for Vocabulary.
        // If we strictly used this interface for everything, we would read assets here.
        // For now, this is a placeholder or used if we switch to direct asset reading.

        // Example implementation for assets:
        if (!mAssetManager) return false;

        AAsset* asset = AAssetManager_open(mAssetManager, filename.c_str(), AASSET_MODE_BUFFER);
        if (!asset) return false;

        off_t length = AAsset_getLength(asset);
        buffer.resize(length);
        int read = AAsset_read(asset, buffer.data(), length);
        AAsset_close(asset);

        return read == length;
    }

private:
    AAssetManager* mAssetManager;
};

#endif // PLATFORM_ANDROID_H
