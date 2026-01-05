#ifndef PLATFORM_WEB_H
#define PLATFORM_WEB_H

<<<<<<< HEAD
#include "../../core/include/Platform.h"
=======
#include "Platform.h"
>>>>>>> latest-debug-v0.1
#include <iostream>
#include <fstream>

class PlatformWeb : public Platform {
public:
    PlatformWeb() {}

    void Log(LogLevel level, const std::string& tag, const std::string& msg) override {
        // Emscripten routes cout/cerr to console.log/error
        if (level == LogLevel::ERROR) {
            std::cerr << "[" << tag << "] " << msg << std::endl;
        } else {
            std::cout << "[" << tag << "] " << msg << std::endl;
        }
    }

    bool LoadFile(const std::string& filename, std::vector<char>& buffer) override {
        // In Emscripten, we assume files are preloaded into the virtual filesystem (MEMFS)
        std::ifstream file(filename, std::ios::binary | std::ios::ate);
        if (!file.is_open()) return false;

        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);

        buffer.resize(size);
        if (file.read(buffer.data(), size)) {
            return true;
        }
        return false;
    }
};

#endif // PLATFORM_WEB_H
