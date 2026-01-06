#include <emscripten/bind.h>
#include <emscripten/val.h>
#include "SLAM/System.h"
#include "PlatformWeb.h"

using namespace emscripten;

// Wrapper class to manage System lifecycle and type conversions
class SystemWrapper {
public:
    SystemWrapper(std::string strVocFile, std::string strSettingsFile) {
        // PlatformWeb is stateless, so we can just pass a new instance
        // or manage it better. For now:
        mPlatform = new PlatformWeb();
        mSystem = new System(strVocFile, strSettingsFile, System::MONOCULAR, mPlatform, false);
    }

    ~SystemWrapper() {
        delete mSystem;
        delete mPlatform;
    }

    // Wrap ProcessFrame (Monocular for simplicity in this demo)
    // Takes a JS Uint8Array containing image data
    bool processFrame(val jsData, int width, int height, double timestamp) {
        // In a real Wasm implementation, managing memory transfer is key.
        // Copy JS data to C++ vector
        // This is slow, better to use memory view.

        // Simplified: Assuming we just want to trigger tracking
        // We would construct cv::Mat here.

        // cv::Mat im(height, width, CV_8UC4, ...); // Requires buffer access

        // For the purpose of this structure:
        return true;
    }

    int getTrackingState() {
        if (mSystem) return mSystem->GetTrackingState();
        return -1;
    }

    std::string getMapStats() {
        if (mSystem) return mSystem->GetMapStats();
        return "Not Init";
    }

    void reset() {
        if (mSystem) mSystem->Reset();
    }

private:
    System* mSystem;
    PlatformWeb* mPlatform;
};

// Bindings
EMSCRIPTEN_BINDINGS(sphereslam_module) {
    class_<SystemWrapper>("System")
        .constructor<std::string, std::string>()
        .function("processFrame", &SystemWrapper::processFrame)
        .function("getTrackingState", &SystemWrapper::getTrackingState)
        .function("getMapStats", &SystemWrapper::getMapStats)
        .function("reset", &SystemWrapper::reset);
}
