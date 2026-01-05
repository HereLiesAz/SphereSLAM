#ifndef PLATFORM_IOS_H
#define PLATFORM_IOS_H

<<<<<<< HEAD
#include "../../core/include/Platform.h"
=======
#include "Platform.h"
>>>>>>> origin/platform-modules-12313376745933906235
#import <Foundation/Foundation.h>

class PlatformIOS : public Platform {
public:
    PlatformIOS() {}

    void Log(LogLevel level, const std::string& tag, const std::string& msg) override;

    bool LoadFile(const std::string& filename, std::vector<char>& buffer) override;
};

#endif // PLATFORM_IOS_H
