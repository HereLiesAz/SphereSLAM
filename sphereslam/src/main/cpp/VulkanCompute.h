#ifndef VULKAN_COMPUTE_H
#define VULKAN_COMPUTE_H

#include <vector>
#include <string>
#include <android/asset_manager.h>
#include <vulkan/vulkan.h>

struct VulkanContext {
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue queue;
    uint32_t queueFamilyIndex;
    VkCommandPool commandPool;
    VkCommandBuffer commandBuffer;

    VkDescriptorSetLayout descriptorSetLayout;
    VkPipelineLayout pipelineLayout;
    VkPipeline pipeline;
    VkDescriptorPool descriptorPool;
    VkDescriptorSet descriptorSet;
};

class VulkanCompute {
public:
    VulkanCompute(AAssetManager* assetManager);
    ~VulkanCompute();

    void initialize();
    void processImage(void* inputBuffer, int width, int height);

private:
    AAssetManager* assetManager;
    VulkanContext vkCtx;

    void loadShader(const std::string& filename);
    void createComputePipeline();
    void createDescriptorSets();
};

#endif // VULKAN_COMPUTE_H
