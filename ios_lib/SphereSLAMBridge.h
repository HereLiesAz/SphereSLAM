#ifndef SPHERESLAM_BRIDGE_H
#define SPHERESLAM_BRIDGE_H

#import <Foundation/Foundation.h>

@interface SphereSLAMBridge : NSObject

- (instancetype)initWithVocFile:(NSString *)vocFile settingsFile:(NSString *)settingsFile;
- (void)processFrame:(NSData *)imageData width:(int)width height:(int)height timestamp:(double)timestamp;
- (int)getTrackingState;
- (NSString *)getMapStats;
- (void)reset;

@end

#endif // SPHERESLAM_BRIDGE_H
