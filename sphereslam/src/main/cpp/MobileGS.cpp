#include "MobileGS.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <opencv2/imgproc.hpp>

#define TAG "MobileGS"

// Vertex Shader for Background
const char* bgVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";

// Fragment Shader for Background
const char* bgFragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vTexCoord;
out vec4 FragColor;
void main() {
    FragColor = texture(uTexture, vTexCoord);
}
)";

// Vertex Shader for Dots (Targets)
const char* targetVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aCaptured;
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform float targetSize;
out float vCaptured;
void main() {
    gl_Position = projMatrix * viewMatrix * vec4(aPos, 1.0);
    gl_PointSize = targetSize;
    vCaptured = aCaptured;
}
)";

// Fragment Shader for Dots
const char* targetFragmentShader = R"(#version 300 es
precision mediump float;
in float vCaptured;
out vec4 FragColor;
void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    float distSq = dot(coord, coord);
    if (distSq > 0.25) discard;

    // Antialiased edge
    float alpha = smoothstep(0.25, 0.23, distSq) * 0.7;

    if (vCaptured > 0.5) {
        FragColor = vec4(0.0, 1.0, 0.0, alpha); // Green
    } else {
        FragColor = vec4(1.0, 0.0, 0.0, alpha); // Red
    }
}
)";

// Gaussian Shaders
const char* gsVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aRot;
layout(location = 2) in vec3 aScale;
layout(location = 3) in float aOpacity;
layout(location = 4) in vec3 aColor;
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
out vec4 vColor;
void main() {
    vec4 viewPos = viewMatrix * vec4(aPos, 1.0);
    gl_Position = projMatrix * viewPos;
    gl_PointSize = 10.0;
    vColor = vec4(aColor, aOpacity);
}
)";

const char* gsFragmentShader = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 FragColor;
void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    if (dot(coord, coord) > 0.25) discard;
    FragColor = vColor;
}
)";

MobileGS::MobileGS() : mWindow(nullptr), mUserOffset(0,0,0), mUserRotation(0,0,0), mBufferDirty(false),
    mDisplay(EGL_NO_DISPLAY), mSurface(EGL_NO_SURFACE), mContext(EGL_NO_CONTEXT), mEglInitialized(false),
    mProgram(0), mVAO(0), mVBO(0), mBgProgram(0), mBgVAO(0), mBgVBO(0), mBgTexture(0), mBgDirty(false),
    mTargetProgram(0), mTargetVAO(0), mTargetVBO(0), mTargetSize(100.0f) {
    mViewMatrix = glm::mat4(1.0f);
    mProjMatrix = glm::mat4(1.0f);
}

MobileGS::~MobileGS() { terminateEGL(); }

void MobileGS::initialize() { __android_log_print(ANDROID_LOG_INFO, TAG, "MobileGS Initialized"); }

void MobileGS::reset() {
    std::unique_lock<std::mutex> lock(mMutexBuffer);
    mFrontBuffer.clear(); mBackBuffer.clear(); mBufferDirty = true;
    keyFramePoses.clear();
}

void MobileGS::setWindow(ANativeWindow* window) {
    if (mWindow == window) return;
    if (mWindow) terminateEGL();
    mWindow = window;
    if (mWindow) {
        initEGL();
        compileShaders();
    }
}

void MobileGS::initEGL() {
    mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(mDisplay, 0, 0);
    const EGLint attribs[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                               EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_DEPTH_SIZE, 16, EGL_NONE };
    EGLConfig config; EGLint numConfigs;
    eglChooseConfig(mDisplay, attribs, &config, 1, &numConfigs);
    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    mContext = eglCreateContext(mDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    mSurface = eglCreateWindowSurface(mDisplay, config, mWindow, 0);
    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
    mEglInitialized = true;
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
}

void MobileGS::terminateEGL() {
    if (mDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (mContext != EGL_NO_CONTEXT) eglDestroyContext(mDisplay, mContext);
        if (mSurface != EGL_NO_SURFACE) eglDestroySurface(mDisplay, mSurface);
        eglTerminate(mDisplay);
    }
    mDisplay = EGL_NO_DISPLAY; mSurface = EGL_NO_SURFACE; mContext = EGL_NO_CONTEXT; mEglInitialized = false;
}

GLuint MobileGS::loadShader(GLenum type, const char* shaderSrc) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shaderSrc, nullptr);
    glCompileShader(shader);
    GLint compiled; glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0; glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen]; glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Shader Error: %s", infoLog);
            delete[] infoLog;
        }
        return 0;
    }
    return shader;
}

void MobileGS::compileShaders() {
    if (!mEglInitialized) return;
    // Gaussian Shaders
    GLuint vs = loadShader(GL_VERTEX_SHADER, gsVertexShader);
    GLuint fs = loadShader(GL_FRAGMENT_SHADER, gsFragmentShader);
    mProgram = glCreateProgram(); glAttachShader(mProgram, vs); glAttachShader(mProgram, fs); glLinkProgram(mProgram);
    glGenVertexArrays(1, &mVAO); glGenBuffers(1, &mVBO);

    // Background Shaders
    vs = loadShader(GL_VERTEX_SHADER, bgVertexShader);
    fs = loadShader(GL_FRAGMENT_SHADER, bgFragmentShader);
    mBgProgram = glCreateProgram(); glAttachShader(mBgProgram, vs); glAttachShader(mBgProgram, fs); glLinkProgram(mBgProgram);
    glGenVertexArrays(1, &mBgVAO); glGenBuffers(1, &mBgVBO);
    float bgVertices[] = { -1, 1, 0, 0, -1, -1, 0, 1, 1, 1, 1, 0, 1, -1, 1, 1 };
    glBindVertexArray(mBgVAO); glBindBuffer(GL_ARRAY_BUFFER, mBgVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(bgVertices), bgVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)(2*4));
    glGenTextures(1, &mBgTexture); glBindTexture(GL_TEXTURE_2D, mBgTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    // Target Shaders
    vs = loadShader(GL_VERTEX_SHADER, targetVertexShader);
    fs = loadShader(GL_FRAGMENT_SHADER, targetFragmentShader);
    mTargetProgram = glCreateProgram(); glAttachShader(mTargetProgram, vs); glAttachShader(mTargetProgram, fs); glLinkProgram(mTargetProgram);
    glGenVertexArrays(1, &mTargetVAO); glGenBuffers(1, &mTargetVBO);
}

void MobileGS::updateBackground(const cv::Mat& frame) {
    std::unique_lock<std::mutex> lock(mMutexBg);
    mPendingBgFrame = frame.clone();
    mBgDirty = true;
}

void MobileGS::setCaptureTargets(const std::vector<glm::vec3>& targets, const std::vector<bool>& captured) {
    std::unique_lock<std::mutex> lock(mMutexTargets);
    mTargets = targets;
    mCapturedFlags = captured;
}

void MobileGS::setTargetSize(float pixels) {
    mTargetSize = pixels;
}

void MobileGS::drawBackground() {
    {
        std::unique_lock<std::mutex> lock(mMutexBg);
        if (mBgDirty && !mPendingBgFrame.empty()) {
            glBindTexture(GL_TEXTURE_2D, mBgTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, mPendingBgFrame.cols, mPendingBgFrame.rows, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, mPendingBgFrame.data);
            mBgDirty = false;
        }
    }
    if (mBgTexture == 0) return;
    glDisable(GL_DEPTH_TEST); glUseProgram(mBgProgram);
    glBindVertexArray(mBgVAO); glBindTexture(GL_TEXTURE_2D, mBgTexture);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glEnable(GL_DEPTH_TEST);
}

void MobileGS::drawTargets() {
    std::unique_lock<std::mutex> lock(mMutexTargets);
    if (mTargets.empty()) return;
    struct TargetData { glm::vec3 pos; float captured; };
    std::vector<TargetData> data;
    for(size_t i=0; i<mTargets.size(); ++i) data.push_back({mTargets[i], mCapturedFlags[i] ? 1.0f : 0.0f});
    glBindVertexArray(mTargetVAO); glBindBuffer(GL_ARRAY_BUFFER, mTargetVBO);
    glBufferData(GL_ARRAY_BUFFER, data.size()*sizeof(TargetData), data.data(), GL_DYNAMIC_DRAW);
    glUseProgram(mTargetProgram);
    glUniformMatrix4fv(glGetUniformLocation(mTargetProgram, "viewMatrix"), 1, GL_FALSE, &mViewMatrix[0][0]);
    glUniformMatrix4fv(glGetUniformLocation(mTargetProgram, "projMatrix"), 1, GL_FALSE, &mProjMatrix[0][0]);
    glUniform1f(glGetUniformLocation(mTargetProgram, "targetSize"), mTargetSize);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(TargetData), (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, sizeof(TargetData), (void*)(3*4));
    glDrawArrays(GL_POINTS, 0, data.size());
}

void MobileGS::draw() {
    if (!mEglInitialized) return;
    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    drawBackground();
    drawTargets();
    eglSwapBuffers(mDisplay, mSurface);
}

void MobileGS::updateCamera(const glm::mat4& viewMatrix, const glm::mat4& projectionMatrix) { mViewMatrix = viewMatrix; mProjMatrix = projectionMatrix; }
void MobileGS::handleInput(float dx, float dy) {}
void MobileGS::addGaussians(const std::vector<Gaussian>& g) {}
void MobileGS::addKeyFrameFrustum(const glm::mat4& p) {}
void MobileGS::drawFrustums() {}
