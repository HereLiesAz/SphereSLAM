#ifndef PROFILER_H
#define PROFILER_H

#include <string>
#include <chrono>
#include <iostream>

class Profiler {
public:
    Profiler(const std::string& name) : mName(name) {
        mStart = std::chrono::high_resolution_clock::now();
    }

    ~Profiler() {
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - mStart).count();
        // std::cout << "[" << mName << "] took " << duration << " ms" << std::endl;
    }

private:
    std::string mName;
    std::chrono::time_point<std::chrono::high_resolution_clock> mStart;
};

#endif // PROFILER_H
