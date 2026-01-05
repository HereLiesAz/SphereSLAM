#ifndef GLM_HPP
#define GLM_HPP

#include <cmath>

namespace glm {

struct vec3 {
    float x, y, z;
    vec3() : x(0), y(0), z(0) {}
    vec3(float _x, float _y, float _z) : x(_x), y(_y), z(_z) {}

    vec3 operator-(const vec3& other) const {
        return vec3(x - other.x, y - other.y, z - other.z);
    }

    vec3 operator-() const {
        return vec3(-x, -y, -z);
    }
};

struct vec4 {
    float x, y, z, w;
    vec4() : x(0), y(0), z(0), w(0) {}
    vec4(float _x, float _y, float _z, float _w) : x(_x), y(_y), z(_z), w(_w) {}
    vec4(vec3 v, float _w) : x(v.x), y(v.y), z(v.z), w(_w) {}

    float& operator[](int i) {
        if(i==0) return x;
        if(i==1) return y;
        if(i==2) return z;
        return w;
    }
    const float& operator[](int i) const {
        if(i==0) return x;
        if(i==1) return y;
        if(i==2) return z;
        return w;
    }
};

struct quat {
    float w, x, y, z;
};

struct mat4;

struct mat3 {
    float data[9];
    mat3() {}
    mat3(const struct mat4& m);
};

struct mat4 {
    vec4 cols[4]; // Column-major storage

    mat4() {}
    mat4(float v) {
        // Identity or scaling
        cols[0] = vec4(v, 0, 0, 0);
        cols[1] = vec4(0, v, 0, 0);
        cols[2] = vec4(0, 0, v, 0);
        cols[3] = vec4(0, 0, 0, v);
    }

    vec4& operator[](int i) { return cols[i]; }
    const vec4& operator[](int i) const { return cols[i]; }
};

inline mat3::mat3(const mat4& m) {
    // Extract rotation (top-left 3x3)
    data[0] = m[0][0]; data[1] = m[0][1]; data[2] = m[0][2];
    data[3] = m[1][0]; data[4] = m[1][1]; data[5] = m[1][2];
    data[6] = m[2][0]; data[7] = m[2][1]; data[8] = m[2][2];
}

inline float distance(const vec3& p0, const vec3& p1) {
    float dx = p0.x - p1.x;
    float dy = p0.y - p1.y;
    float dz = p0.z - p1.z;
    return std::sqrt(dx*dx + dy*dy + dz*dz);
}

inline float dot(const vec3& a, const vec3& b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

inline vec3 operator*(const mat3& m, const vec3& v) {
    return vec3(
        m.data[0]*v.x + m.data[3]*v.y + m.data[6]*v.z,
        m.data[1]*v.x + m.data[4]*v.y + m.data[7]*v.z,
        m.data[2]*v.x + m.data[5]*v.y + m.data[8]*v.z
    );
}

inline mat3 transpose(const mat3& m) {
    mat3 t;
    t.data[0] = m.data[0]; t.data[1] = m.data[3]; t.data[2] = m.data[6];
    t.data[3] = m.data[1]; t.data[4] = m.data[4]; t.data[5] = m.data[7];
    t.data[6] = m.data[2]; t.data[7] = m.data[5]; t.data[8] = m.data[8];
    return t;
}

}

#endif
