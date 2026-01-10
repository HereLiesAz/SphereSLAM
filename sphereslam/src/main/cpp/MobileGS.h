#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <glm/glm.hpp>
#include <android/native_window.h>
#include <mutex>
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

    // Handle user input to offset the camera
    void handleInput(float dx, float dy);

    // Add new gaussians to the scene
    void addGaussians(const std::vector<Gaussian>& newGaussians);

    // Visualize KeyFrames
    void addKeyFrameFrustum(const glm::mat4& pose);

    // Render the scene
    void draw();

private:
    // Double Buffer
    std::vector<Gaussian> mFrontBuffer;
    std::vector<Gaussian> mBackBuffer;
    bool mBufferDirty;
    std::mutex mMutexBuffer;

    std::vector<glm::mat4> keyFramePoses; // New: Store poses for visualization
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

    // Camera State
    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::vec3 mUserOffset;
    glm::vec3 mUserRotation;

    void initEGL();
    void terminateEGL();
    void compileShaders();
    GLuint loadShader(GLenum type, const char* shaderSrc);
    void drawFrustums();
};

#endif // MOBILE_GS_H
