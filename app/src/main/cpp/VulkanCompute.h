#ifndef VULKAN_COMPUTE_H
#define VULKAN_COMPUTE_H

#include <vector>
#include <string>
#include <android/asset_manager.h>

class VulkanCompute {
public:
    VulkanCompute(AAssetManager* assetManager);
    ~VulkanCompute();

    void initialize();
    void processImage(void* inputBuffer, int width, int height);

private:
    AAssetManager* assetManager;
    // Vulkan instance, device, queue, etc. would go here.
    // For this blueprint, we keep it as a skeleton.

    void loadShader(const std::string& filename);
};

#endif // VULKAN_COMPUTE_H
