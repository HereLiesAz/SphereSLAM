#include "MobileGS.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define TAG "MobileGS"

// Conceptual Vertex Shader
const char* gsVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;     // Gaussian Center
layout(location = 1) in vec4 aRot;     // Quaternion
layout(location = 2) in vec3 aScale;   // Scale
layout(location = 3) in float aOpacity;
layout(location = 4) in vec3 aColor;   // SH DC

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

out vec4 vColor;

void main() {
    // Conceptual Billboarding logic
    // 1. Transform center to camera space
    vec4 viewPos = viewMatrix * vec4(aPos, 1.0);

    // 2. Project to screen
    gl_Position = projMatrix * viewPos;
    gl_PointSize = 10.0; // Fixed size for point splatting fallback

    // 3. Pass color and opacity
    vColor = vec4(aColor, aOpacity);
}
)";

// Conceptual Fragment Shader
const char* gsFragmentShader = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 FragColor;
void main() {
    // Simple Gaussian falloff approximation
    vec2 coord = gl_PointCoord - vec2(0.5);
    float distSq = dot(coord, coord);
    if (distSq > 0.25) discard;

    // float alpha = exp(-4.0 * distSq);
    FragColor = vColor;
}
)";

MobileGS::MobileGS() : mWindow(nullptr), mUserOffset(glm::vec3(0.0f, 0.0f, 0.0f)), mUserRotation(glm::vec3(0.0f, 0.0f, 0.0f)), mBufferDirty(false),
    mDisplay(EGL_NO_DISPLAY), mSurface(EGL_NO_SURFACE), mContext(EGL_NO_CONTEXT), mEglInitialized(false),
    mProgram(0), mVAO(0), mVBO(0) {
    mViewMatrix = glm::mat4(1.0f);
    mProjMatrix = glm::mat4(1.0f);
}

MobileGS::~MobileGS() {
    terminateEGL();
}

void MobileGS::initialize() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing MobileGS Renderer");
}

void MobileGS::reset() {
    std::unique_lock<std::mutex> lock(mMutexBuffer);
    mFrontBuffer.clear();
    mBackBuffer.clear();
    mBufferDirty = true;
    keyFramePoses.clear();
}

void MobileGS::setWindow(ANativeWindow* window) {
    if (mWindow == window) return;

    if (mWindow) {
        terminateEGL();
    }

    mWindow = window;
    if (mWindow) {
        initEGL();
        compileShaders();
        __android_log_print(ANDROID_LOG_INFO, TAG, "Window set for MobileGS");
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Window released");
    }
}

void MobileGS::initEGL() {
    mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (mDisplay == EGL_NO_DISPLAY) return;

    eglInitialize(mDisplay, 0, 0);

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    eglChooseConfig(mDisplay, attribs, &config, 1, &numConfigs);

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    mContext = eglCreateContext(mDisplay, config, EGL_NO_CONTEXT, contextAttribs);

    mSurface = eglCreateWindowSurface(mDisplay, config, mWindow, 0);

    if (eglMakeCurrent(mDisplay, mSurface, mSurface, mContext) == EGL_FALSE) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "eglMakeCurrent failed");
        return;
    }

    mEglInitialized = true;

    // Init GL State
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
}

void MobileGS::terminateEGL() {
    if (mDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (mContext != EGL_NO_CONTEXT) eglDestroyContext(mDisplay, mContext);
        if (mSurface != EGL_NO_SURFACE) eglDestroySurface(mDisplay, mSurface);
        eglTerminate(mDisplay);
    }
    mDisplay = EGL_NO_DISPLAY;
    mSurface = EGL_NO_SURFACE;
    mContext = EGL_NO_CONTEXT;
    mEglInitialized = false;
}

GLuint MobileGS::loadShader(GLenum type, const char* shaderSrc) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shaderSrc, nullptr);
    glCompileShader(shader);

    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Error compiling shader: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void MobileGS::compileShaders() {
    if (!mEglInitialized) return;

    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, gsVertexShader);
    GLuint fragmentShader = loadShader(GL_FRAGMENT_SHADER, gsFragmentShader);

    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertexShader);
    glAttachShader(mProgram, fragmentShader);
    glLinkProgram(mProgram);

    GLint linked;
    glGetProgramiv(mProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Error linking program");
        return;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    glGenVertexArrays(1, &mVAO);
    glGenBuffers(1, &mVBO);
}

void MobileGS::updateCamera(const glm::mat4& viewMatrix, const glm::mat4& projectionMatrix) {
    mViewMatrix = viewMatrix;
    mProjMatrix = projectionMatrix;
}

void MobileGS::handleInput(float dx, float dy) {
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
    if (!mEglInitialized) return;

    {
        std::unique_lock<std::mutex> lock(mMutexBuffer);
        if (mBufferDirty) {
            if (!mBackBuffer.empty()) {
                mFrontBuffer.insert(mFrontBuffer.end(), mBackBuffer.begin(), mBackBuffer.end());
                mBackBuffer.clear();
            }
            mBufferDirty = false;
        }
    }

    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);

    // Clear background
    glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (mFrontBuffer.empty()) {
        eglSwapBuffers(mDisplay, mSurface);
        return;
    }

    // Sort using manual camera position extraction
    // View Matrix T_cw = [R | t]
    // Camera Position C_w = -R^T * t

    glm::mat3 R(mViewMatrix); // Extract rotation (top-left 3x3)
    glm::vec3 t(mViewMatrix[3].x, mViewMatrix[3].y, mViewMatrix[3].z); // Extract translation (col 3)

    // Transpose R (since it's orthogonal, inverse = transpose)
    glm::mat3 Rt = glm::transpose(R);
    glm::vec3 camPos = Rt * (-t);

    std::sort(mFrontBuffer.begin(), mFrontBuffer.end(), DepthSorter{camPos});

    // Upload
    glBindVertexArray(mVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, mFrontBuffer.size() * sizeof(Gaussian), mFrontBuffer.data(), GL_DYNAMIC_DRAW);

    // Attributes
    GLsizei stride = sizeof(Gaussian);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void*)0);

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void*)(sizeof(float)*3));

    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, stride, (void*)(sizeof(float)*7));

    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, stride, (void*)(sizeof(float)*10));

    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 3, GL_FLOAT, GL_FALSE, stride, (void*)(sizeof(float)*11));

    // Draw
    glUseProgram(mProgram);

    GLint locView = glGetUniformLocation(mProgram, "viewMatrix");
    GLint locProj = glGetUniformLocation(mProgram, "projMatrix");

    glUniformMatrix4fv(locView, 1, GL_FALSE, &mViewMatrix[0][0]);
    glUniformMatrix4fv(locProj, 1, GL_FALSE, &mProjMatrix[0][0]);

    glDrawArrays(GL_POINTS, 0, mFrontBuffer.size());

    drawFrustums();

    eglSwapBuffers(mDisplay, mSurface);
}

void MobileGS::drawFrustums() {
    // Placeholder
}
