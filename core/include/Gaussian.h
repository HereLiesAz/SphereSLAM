#ifndef GAUSSIAN_H
#define GAUSSIAN_H

#include <glm/glm.hpp>
#include <glm/quat.hpp>

// Structure for a 3D Gaussian
struct Gaussian {
    glm::vec3 position;
    glm::quat rotation;
    glm::vec3 scale;
    float opacity;
    glm::vec3 color_sh; // Simplification: just DC component
};

#endif // GAUSSIAN_H
