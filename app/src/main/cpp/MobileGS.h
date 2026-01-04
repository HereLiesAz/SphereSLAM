#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <GLES3/gl32.h>
#include <glm/glm.hpp>
#include <android/native_window.h>

// Structure for a 3D Gaussian
struct Gaussian {
    glm::vec3 position;
    glm::quat rotation;
    glm::vec3 scale;
    float opacity;
    glm::vec3 color_sh; // Simplification: just DC component
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();

    // Set the output window
    void setWindow(ANativeWindow* window);

    // Update the virtual camera pose
    void updateCamera(const glm::mat4& viewMatrix, const glm::mat4& projectionMatrix);

    // Handle user input to offset the camera
    void handleInput(float dx, float dy);

    // Add new gaussians to the scene
    void addGaussians(const std::vector<Gaussian>& newGaussians);

    // Render the scene
    void draw();

private:
    std::vector<Gaussian> sceneGaussians;
    ANativeWindow* mWindow;

    glm::vec3 mUserOffset;
    glm::vec3 mUserRotation;

    // OpenGL/Vulkan resources (buffers, shaders)
    // For blueprint, we assume these exist

    void sortGaussians(); // Radix sort on GPU
    void cullTiles();     // Tile-based culling
};

#endif // MOBILE_GS_H
