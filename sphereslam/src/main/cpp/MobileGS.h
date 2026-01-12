#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <glm/glm.hpp>
#include <android/native_window.h>
#include <mutex>
#include <opencv2/core.hpp>
#include "Gaussian.h"

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();

    // Set the output window
    void setWindow(ANativeWindow* window);

    // Reset the scene
    void reset();

    // Update the virtual camera pose
    void updateCamera(const glm::mat4& viewMatrix, const glm::mat4& projectionMatrix);

    // Update background camera frame
    void updateBackground(const cv::Mat& frame);

    // Update capture targets (dots)
    void setCaptureTargets(const std::vector<glm::vec3>& targets, const std::vector<bool>& captured);

    // Set size of targets in pixels
    void setTargetSize(float pixels);

    // Handle user input to offset the camera
    void handleInput(float dx, float dy);

    // Add new gaussians to the scene
    void addGaussians(const std::vector<Gaussian>& newGaussians);

    // Visualize KeyFrames
    void addKeyFrameFrustum(const glm::mat4& pose);

    // Render the scene
    void draw();

private:
    // Double Buffer for Gaussians
    std::vector<Gaussian> mFrontBuffer;
    std::vector<Gaussian> mBackBuffer;
    bool mBufferDirty;
    std::mutex mMutexBuffer;

    // Capture Targets
    std::vector<glm::vec3> mTargets;
    std::vector<bool> mCapturedFlags;
    std::mutex mMutexTargets;
    float mTargetSize;

    // Background Texture
    GLuint mBgTexture;
    cv::Mat mPendingBgFrame;
    std::mutex mMutexBg;
    bool mBgDirty;

    std::vector<glm::mat4> keyFramePoses;
    ANativeWindow* mWindow;

    // EGL State
    EGLDisplay mDisplay;
    EGLSurface mSurface;
    EGLContext mContext;
    bool mEglInitialized;

    // GL State
    GLuint mProgram;
    GLuint mVAO;
    GLuint mVBO;

    // Background GL State
    GLuint mBgProgram;
    GLuint mBgVAO;
    GLuint mBgVBO;

    // Target GL State (Dots)
    GLuint mTargetProgram;
    GLuint mTargetVAO;
    GLuint mTargetVBO;

    // Camera State
    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::vec3 mUserOffset;
    glm::vec3 mUserRotation;

    void initEGL();
    void terminateEGL();
    void compileShaders();
    GLuint loadShader(GLenum type, const char* shaderSrc);
    void drawBackground();
    void drawTargets();
    void drawFrustums();
};

#endif // MOBILE_GS_H
