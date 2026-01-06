#import "SphereSLAMBridge.h"
#include "SLAM/System.h"
#include "PlatformIOS.h"

@interface SphereSLAMBridge () {
    System* _system;
    PlatformIOS* _platform;
}
@end

@implementation SphereSLAMBridge

- (instancetype)initWithVocFile:(NSString *)vocFile settingsFile:(NSString *)settingsFile {
    self = [super init];
    if (self) {
        auto platform = std::make_unique<PlatformIOS>();
        std::string strVoc = [vocFile UTF8String];
        std::string strSettings = [settingsFile UTF8String];

        // Assuming MONOCULAR for basic bridge
        auto system = std::make_unique<System>(strVoc, strSettings, System::MONOCULAR, platform.get(), false);

        // Transfer ownership to the instance variables
        _platform = platform.release();
        _system = system.release();
    }
    return self;
}

- (void)dealloc {
    if (_system) delete _system;
    if (_platform) delete _platform;
}

- (void)processFrame:(NSData *)imageData width:(int)width height:(int)height timestamp:(double)timestamp {
    if (!_system) return;

    // Convert NSData to cv::Mat
    // This requires OpenCV headers which are available in core/
    // cv::Mat img(height, width, CV_8UC1, (void*)[imageData bytes]);

    // _system->TrackMonocular(img, timestamp);
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

@end
