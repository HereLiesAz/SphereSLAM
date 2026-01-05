#include "MobileGS.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define TAG "MobileGS"

// Conceptual Vertex Shader
const char* gsVertexShader = R"(
#version 300 es
layout(location = 0) in vec3 aPos;     // Gaussian Center
layout(location = 1) in vec4 aRot;     // Quaternion
layout(location = 2) in vec3 aScale;   // Scale
layout(location = 3) in float aOpacity;
layout(location = 4) in vec3 aColor;   // SH DC

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

out vec4 vColor;
out vec2 vCenter; // Screen space center

void main() {
    // Conceptual Billboarding logic
    // 1. Transform center to camera space
    vec4 viewPos = viewMatrix * vec4(aPos, 1.0);

    // 2. Project to screen
    gl_Position = projMatrix * viewPos;

    // 3. Pass color and opacity
    vColor = vec4(aColor, aOpacity);
}
)";

// Conceptual Fragment Shader
const char* gsFragmentShader = R"(
#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 FragColor;
void main() {
    // Simple Gaussian falloff approximation
    // float dist = length(gl_PointCoord - vec2(0.5));
    // float alpha = exp(-4.0 * dist * dist);
    // if (alpha < 0.1) discard;

    FragColor = vColor; // * alpha;
}
)";

MobileGS::MobileGS() : mWindow(nullptr), mUserOffset(glm::vec3(0.0f, 0.0f, 0.0f)), mUserRotation(glm::vec3(0.0f, 0.0f, 0.0f)), mBufferDirty(false) {
    mViewMatrix = glm::mat4(1.0f);
    mProjMatrix = glm::mat4(1.0f);
}

MobileGS::~MobileGS() {
}

void MobileGS::initialize() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing MobileGS Renderer");

    // 1. Compile Shaders
    // ...
}

void MobileGS::setWindow(ANativeWindow* window) {
    mWindow = window;
    if (mWindow) {
        // Initialize EGL
        __android_log_print(ANDROID_LOG_INFO, TAG, "Window set for MobileGS");
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Window released");
    }
}

void MobileGS::updateCamera(const glm::mat4& viewMatrix, const glm::mat4& projectionMatrix) {
    mViewMatrix = viewMatrix;
    mProjMatrix = projectionMatrix;
}

void MobileGS::handleInput(float dx, float dy) {
    // Basic orbit/pan logic
    mUserRotation.y += dx * 0.01f;
    mUserRotation.x += dy * 0.01f;
}

void MobileGS::addGaussians(const std::vector<Gaussian>& newGaussians) {
    std::unique_lock<std::mutex> lock(mMutexBuffer);
    mBackBuffer.insert(mBackBuffer.end(), newGaussians.begin(), newGaussians.end());
    mBufferDirty = true;
}

void MobileGS::addKeyFrameFrustum(const glm::mat4& pose) {
    keyFramePoses.push_back(pose);
}

struct DepthSorter {
    glm::vec3 camPos;
    bool operator()(const Gaussian& a, const Gaussian& b) {
        float distASq = glm::dot(a.position - camPos, a.position - camPos);
        float distBSq = glm::dot(b.position - camPos, b.position - camPos);
        return distASq > distBSq; // Back-to-front
    }
};

void MobileGS::draw() {
    if (!mWindow) return;

    // Swap Buffers Logic
    {
        std::unique_lock<std::mutex> lock(mMutexBuffer);
        if (mBufferDirty) {
            // Append BackBuffer to FrontBuffer or Swap
            // Here we assume additive update, so we insert
            if (!mBackBuffer.empty()) {
                mFrontBuffer.insert(mFrontBuffer.end(), mBackBuffer.begin(), mBackBuffer.end());
                mBackBuffer.clear();
                // Update GPU VBO here with new size
            }
            mBufferDirty = false;
        }
    }

    // eglMakeCurrent(...)
    // glClear(...)

    drawFrustums();

    if (mFrontBuffer.empty()) {
        // eglSwapBuffers(...)
        return;
    }

    // 1. Sort Gaussians (CPU Fallback)
    // Extract camera position from inverse View Matrix
    // ViewMatrix maps World -> Camera. Inverse maps Camera -> World.
    // The translation part of Inverse View Matrix is the Camera Position in World Space.
    // Assuming mViewMatrix is 4x4 float
    // Inverse calculation is expensive, ideally pass Camera Position directly.
    // For now, assume simplified view matrix structure (Rotation + Translation)
    // C = -R^T * t

    // Conceptual inverse for sorting
    // glm::mat4 invView = glm::inverse(mViewMatrix);
    // glm::vec3 camPos = glm::vec3(invView[3]);

    // Optimization: If mViewMatrix is rigid body:
    // camPos = -transpose(R) * t
    glm::mat3 R = glm::mat3(mViewMatrix);
    glm::vec3 t = glm::vec3(mViewMatrix[3].x, mViewMatrix[3].y, mViewMatrix[3].z);
    glm::vec3 camPos = glm::transpose(R) * (-t);

    std::sort(mFrontBuffer.begin(), mFrontBuffer.end(), DepthSorter{camPos});

    // 2. Upload Sorted Data to GPU
    // glBufferData(GL_ARRAY_BUFFER, ... mFrontBuffer.data() ...);

    // 3. Draw
    // glDrawArrays(GL_POINTS, 0, mFrontBuffer.size());

    // eglSwapBuffers(...)
}

void MobileGS::drawFrustums() {
    // Conceptually draw lines
}

void MobileGS::sortGaussians() {
    // Placeholder
}

void MobileGS::cullTiles() {
    // Placeholder
}
