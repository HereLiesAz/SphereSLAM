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

    // Resources
    VkImage inputImage;
    VkDeviceMemory inputImageMemory;
    VkImageView inputImageView;

    VkBuffer stagingBuffer;
    VkDeviceMemory stagingBufferMemory;

    // Output: 6 faces
    std::vector<VkImage> outputImages;
    std::vector<VkDeviceMemory> outputImageMemories;
    std::vector<VkImageView> outputImageViews;

    // Readback
    VkBuffer readbackBuffer;
    VkDeviceMemory readbackBufferMemory;

    void createComputePipeline();
    void createDescriptorSets();
    void createResources();

    // Helpers
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    void createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory);
    void createImage(uint32_t width, uint32_t height, VkFormat format, VkImageTiling tiling, VkImageUsageFlags usage, VkMemoryPropertyFlags properties, VkImage& image, VkDeviceMemory& imageMemory);
    VkImageView createImageView(VkImage image, VkFormat format, VkImageViewType viewType = VK_IMAGE_VIEW_TYPE_2D);
    void transitionImageLayout(VkImage image, VkFormat format, VkImageLayout oldLayout, VkImageLayout newLayout);
};

#endif // VULKAN_COMPUTE_H
