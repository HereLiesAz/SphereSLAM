#ifndef SPHERESLAM_BRIDGE_H
#define SPHERESLAM_BRIDGE_H

#import <Foundation/Foundation.h>

@interface SphereSLAMBridge : NSObject

- (instancetype)initWithVocFile:(NSString *)vocFile settingsFile:(NSString *)settingsFile;

// Updated signature to accept raw pointer and stride
- (void)processFrame:(const void *)baseAddress width:(int)width height:(int)height stride:(int)stride timestamp:(double)timestamp;

- (int)getTrackingState;
- (NSString *)getMapStats;
- (void)reset;
- (void)savePhotosphere:(NSString *)filename;
- (void)saveMap:(NSString *)filename;
- (BOOL)loadMap:(NSString *)filename;

@end

#endif // SPHERESLAM_BRIDGE_H
