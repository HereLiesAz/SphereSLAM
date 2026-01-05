#include "PlatformIOS.h"

void PlatformIOS::Log(LogLevel level, const std::string& tag, const std::string& msg) {
    NSString* nsMsg = [NSString stringWithUTF8String:msg.c_str()];
    NSString* nsTag = [NSString stringWithUTF8String:tag.c_str()];

    // In a real iOS app, you might use unified logging (os_log)
    // For now, NSLog is sufficient for debugging.
    NSLog(@"[%@] %@", nsTag, nsMsg);
}

bool PlatformIOS::LoadFile(const std::string& filename, std::vector<char>& buffer) {
    // filename is expected to be a path relative to the Bundle Resource
    NSString* nsPath = [NSString stringWithUTF8String:filename.c_str()];
    NSString* resourcePath = [[NSBundle mainBundle] pathForResource:[nsPath stringByDeletingPathExtension]
                                                             ofType:[nsPath pathExtension]];

    if (!resourcePath) {
        // Try without extension if it was part of the name
         resourcePath = [[NSBundle mainBundle] pathForResource:nsPath ofType:nil];
    }

    if (resourcePath) {
        NSData* data = [NSData dataWithContentsOfFile:resourcePath];
        if (data) {
            buffer.resize([data length]);
            memcpy(buffer.data(), [data bytes], [data length]);
            return true;
        }
    }

    return false;
}
