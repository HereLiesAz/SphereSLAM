#ifndef PLATFORM_H
#define PLATFORM_H

#include <string>
#include <vector>

enum class LogLevel {
    INFO,
    WARN,
    ERROR
};

class Platform {
public:
    virtual ~Platform() {}

    // Logging
    virtual void Log(LogLevel level, const std::string& tag, const std::string& msg) = 0;

    // Asset Loading
    // Returns true if successful, false otherwise.
    // Loads content into the provided buffer.
    virtual bool LoadFile(const std::string& filename, std::vector<char>& buffer) = 0;

    // Some platforms (Android) might need a path to extract files to
    virtual std::string GetWritablePath() { return ""; }
};

#endif // PLATFORM_H
