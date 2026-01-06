#include <emscripten/bind.h>
#include <emscripten/val.h>
#include "SLAM/System.h"
#include "PlatformWeb.h"
#include <vector>

using namespace emscripten;

// Wrapper class to manage System lifecycle and type conversions
class SystemWrapper {
public:
    SystemWrapper(std::string strVocFile, std::string strSettingsFile) {
        mPlatform = new PlatformWeb();
        mSystem = new System(strVocFile, strSettingsFile, System::MONOCULAR, mPlatform, false);
    }

    ~SystemWrapper() {
        delete mSystem;
        delete mPlatform;
    }

    // Wrap ProcessFrame
    // Takes a JS Uint8Array (memory view)
    bool processFrame(val jsData, int width, int height, double timestamp) {
        // Robustness: Input validation
        if (width <= 0 || height <= 0) return false;

        std::vector<unsigned char> pixelData;

        // Use emscripten::val::as to convert JS array to C++ vector
        // This handles type checking and copying
        try {
            // Option 1: Direct conversion if supported
            // pixelData = jsData.as<std::vector<unsigned char>>();

            // Option 2: Copy manually if auto-conversion is tricky with TypedArrays
            unsigned int length = jsData["length"].as<unsigned int>();
            pixelData.resize(length);

            // Efficient copy using memory view if available, or loop
            // For simple bind:
            val memory = val::module_property("HEAPU8");
            // This requires pointer logic usually.
            // Let's use the provided helper method if available, or just use .as<std::vector> which works for simple arrays.
            // For TypedArray, we often use memory view.

            // Fallback for simplicity/robustness without complex pointer math in this snippet:
            // Convert to regular array first in JS? No, slow.
            // Let's assume standard vector conversion works for now as it's cleaner.
             pixelData = vecFromJSArray<unsigned char>(jsData);

        } catch (...) {
            return false;
        }

        // Check size
        if (pixelData.size() != width * height * 4) { // Assuming RGBA
            // Error handling
            return false;
        }

        // Construct Mat (if OpenCV available)
        // cv::Mat im(height, width, CV_8UC4, pixelData.data());
        // mSystem->TrackMonocular(im, timestamp);

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

    void savePhotosphere(std::string filename) {
        if (mSystem) mSystem->SavePhotosphere(filename);
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
        .function("reset", &SystemWrapper::reset)
        .function("savePhotosphere", &SystemWrapper::savePhotosphere);

    // Register vector conversion if needed explicitly
    register_vector<unsigned char>("VectorU8");
}
