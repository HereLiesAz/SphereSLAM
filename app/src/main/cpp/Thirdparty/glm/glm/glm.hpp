#ifndef GLM_HPP
#define GLM_HPP

#include <cmath>

namespace glm {

struct vec3 {
    float x, y, z;
    vec3() : x(0), y(0), z(0) {}
    vec3(float _x, float _y, float _z) : x(_x), y(_y), z(_z) {}
};

struct vec4 {
    float x, y, z, w;
};

struct quat {
    float w, x, y, z;
};

struct mat4 {
    float data[16];
};

inline float distance(const vec3& p0, const vec3& p1) {
    float dx = p0.x - p1.x;
    float dy = p0.y - p1.y;
    float dz = p0.z - p1.z;
    return std::sqrt(dx*dx + dy*dy + dz*dz);
}

}

#endif
