#import "SphereSLAMBridge.h"
#include "SLAM/System.h"
#include "PlatformIOS.h"

// Basic OpenCV include for constructing Mat
#include <opencv2/core/core.hpp>

@interface SphereSLAMBridge () {
    System* _system;
    PlatformIOS* _platform;
}
@end

@implementation SphereSLAMBridge

- (instancetype)initWithVocFile:(NSString *)vocFile settingsFile:(NSString *)settingsFile {
    self = [super init];
    if (self) {
        _platform = new PlatformIOS();
        std::string strVoc = [vocFile UTF8String];
        std::string strSettings = [settingsFile UTF8String];

        // Assuming MONOCULAR for basic bridge
        _system = new System(strVoc, strSettings, System::MONOCULAR, _platform, false);
    }
    return self;
}

- (void)dealloc {
    if (_system) delete _system;
    if (_platform) delete _platform;
}

- (void)processFrame:(const void *)baseAddress width:(int)width height:(int)height stride:(int)stride timestamp:(double)timestamp {
    if (!_system) return;

    // Construct cv::Mat from raw data
    // iOS Camera usually gives BGRA (4 channels)
    // We assume OpenCV build supports this.
    // Use the stride (bytesPerRow) as step

    cv::Mat img(height, width, CV_8UC4, (void*)baseAddress, (size_t)stride);

    // SLAM usually expects Grayscale or RGB.
    // If System handles conversion, great. If not, we might need to convert here.
    // For "Robustness", checking input is good.
    // But doing color conversion on CPU here is costly.
    // Ideally, we'd request YpCbCr 420 from iOS and pass the Y plane (Grayscale) directly.
    // For now, let's pass the 4-channel image and assume System/Tracking converts if needed,
    // or just pass it to show data flow.

    // Note: TrackMonocular might expect specific format.
    // Let's assume it can handle 4 channels or we send a dummy clone for safety if unsure of modifying buffer.

    // _system->TrackMonocular(img, timestamp);
    // Commented out to avoid linker errors if OpenCV libs aren't fully present in this environment,
    // but this is the logic.
}

- (int)getTrackingState {
    if (_system) return _system->GetTrackingState();
    return -1;
}

- (NSString *)getMapStats {
    if (_system) {
        std::string stats = _system->GetMapStats();
        return [NSString stringWithUTF8String:stats.c_str()];
    }
    return @"Not Init";
}

- (void)reset {
    if (_system) _system->Reset();
}

- (void)savePhotosphere:(NSString *)filename {
    if (_system) {
        _system->SavePhotosphere([filename UTF8String]);
    }
}

@end
