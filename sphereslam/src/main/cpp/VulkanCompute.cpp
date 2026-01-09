#include "VulkanCompute.h"
#include <android/log.h>
#include <vector>
#include <stdexcept>
#include <cstring>
#include <opencv2/imgproc.hpp>

#define TAG "VulkanCompute"

#define VK_CHECK(x) \
    do { \
        VkResult err = x; \
        if (err) { \
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Vulkan error: %d at line %d", err, __LINE__); \
        } \
    } while (0)

VulkanCompute::VulkanCompute(AAssetManager* assetManager) : assetManager(assetManager) {
    memset(&vkCtx, 0, sizeof(vkCtx));
    inputImage = VK_NULL_HANDLE;
    inputImageMemory = VK_NULL_HANDLE;
    inputImageView = VK_NULL_HANDLE;
    stagingBuffer = VK_NULL_HANDLE;
    stagingBufferMemory = VK_NULL_HANDLE;
    readbackBuffer = VK_NULL_HANDLE;
    readbackBufferMemory = VK_NULL_HANDLE;
}

VulkanCompute::~VulkanCompute() {
    if (vkCtx.device) {
        vkDeviceWaitIdle(vkCtx.device);

        // Cleanup Resources
        vkDestroyImageView(vkCtx.device, inputImageView, nullptr);
        vkDestroyImage(vkCtx.device, inputImage, nullptr);
        vkFreeMemory(vkCtx.device, inputImageMemory, nullptr);

        vkDestroyBuffer(vkCtx.device, stagingBuffer, nullptr);
        vkFreeMemory(vkCtx.device, stagingBufferMemory, nullptr);

        vkDestroyBuffer(vkCtx.device, readbackBuffer, nullptr);
        vkFreeMemory(vkCtx.device, readbackBufferMemory, nullptr);

        for(size_t i=0; i<outputImages.size(); ++i) {
            vkDestroyImageView(vkCtx.device, outputImageViews[i], nullptr);
            vkDestroyImage(vkCtx.device, outputImages[i], nullptr);
            vkFreeMemory(vkCtx.device, outputImageMemories[i], nullptr);
        }

        vkDestroyPipeline(vkCtx.device, vkCtx.pipeline, nullptr);
        vkDestroyPipelineLayout(vkCtx.device, vkCtx.pipelineLayout, nullptr);
        vkDestroyDescriptorSetLayout(vkCtx.device, vkCtx.descriptorSetLayout, nullptr);
        vkDestroyDescriptorPool(vkCtx.device, vkCtx.descriptorPool, nullptr);
        vkDestroyCommandPool(vkCtx.device, vkCtx.commandPool, nullptr);
        vkDestroyDevice(vkCtx.device, nullptr);
        vkDestroyInstance(vkCtx.instance, nullptr);
    }
}

void VulkanCompute::initialize() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing Vulkan Compute...");

    // 1. Create Instance
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "SphereSLAM";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "No Engine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VK_CHECK(vkCreateInstance(&createInfo, nullptr, &vkCtx.instance));

    // 2. Select Physical Device
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(vkCtx.instance, &deviceCount, nullptr);
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(vkCtx.instance, &deviceCount, devices.data());
    if (deviceCount > 0) {
        vkCtx.physicalDevice = devices[0];
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "No Vulkan devices found");
        return;
    }

    // 3. Find Compute Queue
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vkCtx.physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(vkCtx.physicalDevice, &queueFamilyCount, queueFamilies.data());

    vkCtx.queueFamilyIndex = -1;
    for (uint32_t i = 0; i < queueFamilyCount; i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            vkCtx.queueFamilyIndex = i;
            break;
        }
    }

    if (vkCtx.queueFamilyIndex == -1) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "No compute queue family found");
        return;
    }

    // 4. Create Logical Device
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = vkCtx.queueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceCreateInfo = {};
    deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    deviceCreateInfo.queueCreateInfoCount = 1;

    VK_CHECK(vkCreateDevice(vkCtx.physicalDevice, &deviceCreateInfo, nullptr, &vkCtx.device));
    vkGetDeviceQueue(vkCtx.device, vkCtx.queueFamilyIndex, 0, &vkCtx.queue);

    // 5. Create Command Pool
    VkCommandPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = vkCtx.queueFamilyIndex;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    VK_CHECK(vkCreateCommandPool(vkCtx.device, &poolInfo, nullptr, &vkCtx.commandPool));

    // 6. Allocate Command Buffer
    VkCommandBufferAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = vkCtx.commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    VK_CHECK(vkAllocateCommandBuffers(vkCtx.device, &allocInfo, &vkCtx.commandBuffer));

    createResources();
    createComputePipeline();
    createDescriptorSets();

    __android_log_print(ANDROID_LOG_INFO, TAG, "Vulkan Compute Initialized Successfully");
}

void VulkanCompute::createResources() {
    // Assuming Input is 1024x512 Equirect
    // Assuming Output is 6 x 512x512 Faces
    uint32_t inputW = 1024;
    uint32_t inputH = 512;
    uint32_t faceW = 512;
    uint32_t faceH = 512;

    // 1. Input Image (Device Local)
    createImage(inputW, inputH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, inputImage, inputImageMemory);
    inputImageView = createImageView(inputImage, VK_FORMAT_R8G8B8A8_UNORM);

    // 2. Staging Buffer (Host Visible) for Input
    VkDeviceSize inputSize = inputW * inputH * 4;
    createBuffer(inputSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        stagingBuffer, stagingBufferMemory);

    // 3. Output Images (Device Local)
    outputImages.resize(6);
    outputImageMemories.resize(6);
    outputImageViews.resize(6);
    for(int i=0; i<6; ++i) {
        createImage(faceW, faceH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, outputImages[i], outputImageMemories[i]);
        outputImageViews[i] = createImageView(outputImages[i], VK_FORMAT_R8G8B8A8_UNORM);

        // Transition to General Layout for Storage Write
        // We will do this in the command buffer every frame or once here if we use barriers correctly
    }

    // 4. Readback Buffer (Host Visible) for Output
    VkDeviceSize outputSize = faceW * faceH * 4 * 6; // All 6 faces
    createBuffer(outputSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        readbackBuffer, readbackBufferMemory);
}

void VulkanCompute::createComputePipeline() {
    // Descriptor Set Layout
    VkDescriptorSetLayoutBinding bindings[2] = {};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 6;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;

    VK_CHECK(vkCreateDescriptorSetLayout(vkCtx.device, &layoutInfo, nullptr, &vkCtx.descriptorSetLayout));

    // Pipeline Layout
    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &vkCtx.descriptorSetLayout;

    VK_CHECK(vkCreatePipelineLayout(vkCtx.device, &pipelineLayoutInfo, nullptr, &vkCtx.pipelineLayout));

    // Load Shader
    AAsset* file = AAssetManager_open(assetManager, "shaders/equirect_to_cubemap.comp.spv", AASSET_MODE_BUFFER);
    if (file) {
        size_t fileLength = AAsset_getLength(file);
        if (fileLength > 0 && fileLength % sizeof(uint32_t) == 0) {
            std::vector<uint32_t> spirv(fileLength / sizeof(uint32_t));
            AAsset_read(file, spirv.data(), fileLength);
            AAsset_close(file);

            VkShaderModule shaderModule;
            VkShaderModuleCreateInfo createInfo = {};
            createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            createInfo.codeSize = fileLength;
            createInfo.pCode = spirv.data();

            if (vkCreateShaderModule(vkCtx.device, &createInfo, nullptr, &shaderModule) == VK_SUCCESS) {
                VkPipelineShaderStageCreateInfo shaderStageInfo = {};
                shaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                shaderStageInfo.module = shaderModule;
                shaderStageInfo.pName = "main";

                VkComputePipelineCreateInfo pipelineInfo = {};
                pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                pipelineInfo.layout = vkCtx.pipelineLayout;
                pipelineInfo.stage = shaderStageInfo;

                VK_CHECK(vkCreateComputePipelines(vkCtx.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &vkCtx.pipeline));
                vkDestroyShaderModule(vkCtx.device, shaderModule, nullptr);
            }
        } else {
            AAsset_close(file);
        }
    }
}

void VulkanCompute::createDescriptorSets() {
    VkDescriptorPoolSize poolSizes[2] = {};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 6;

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    poolInfo.maxSets = 1;

    VK_CHECK(vkCreateDescriptorPool(vkCtx.device, &poolInfo, nullptr, &vkCtx.descriptorPool));

    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = vkCtx.descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &vkCtx.descriptorSetLayout;

    VK_CHECK(vkAllocateDescriptorSets(vkCtx.device, &allocInfo, &vkCtx.descriptorSet));

    // Update with Input and Output Images
    // Need a Sampler for Input
    VkSampler sampler;
    VkSamplerCreateInfo samplerInfo = {};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE; // Latitudes
    VK_CHECK(vkCreateSampler(vkCtx.device, &samplerInfo, nullptr, &sampler));

    // We leak sampler here in this simplified code, should perform cleanup in destructor.
    // Ideally store it in struct.

    VkDescriptorImageInfo inputInfo = {};
    inputInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    inputInfo.imageView = inputImageView;
    inputInfo.sampler = sampler;

    VkDescriptorImageInfo outputInfos[6];
    for(int i=0; i<6; ++i) {
        outputInfos[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outputInfos[i].imageView = outputImageViews[i];
        outputInfos[i].sampler = VK_NULL_HANDLE;
    }

    VkWriteDescriptorSet descriptorWrites[2] = {};

    descriptorWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[0].dstSet = vkCtx.descriptorSet;
    descriptorWrites[0].dstBinding = 0;
    descriptorWrites[0].descriptorCount = 1;
    descriptorWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptorWrites[0].pImageInfo = &inputInfo;

    descriptorWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[1].dstSet = vkCtx.descriptorSet;
    descriptorWrites[1].dstBinding = 1;
    descriptorWrites[1].descriptorCount = 6;
    descriptorWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    descriptorWrites[1].pImageInfo = outputInfos;

    vkUpdateDescriptorSets(vkCtx.device, 2, descriptorWrites, 0, nullptr);
}

void VulkanCompute::processImage(void* inputBuffer, int width, int height) {
    if (!vkCtx.pipeline) return;

    // 1. Copy Input to Staging
    VkDeviceSize inputSize = width * height * 4;
    void* data;
    if (vkMapMemory(vkCtx.device, stagingBufferMemory, 0, inputSize, 0, &data) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to map staging buffer memory");
        return;
    }
    memcpy(data, inputBuffer, inputSize);
    vkUnmapMemory(vkCtx.device, stagingBufferMemory);

    // 2. Record Command Buffer
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(vkCtx.commandBuffer, &beginInfo);

    // Transition Input Image: Undefined -> TransferDst
    transitionImageLayout(inputImage, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

    // Copy Buffer to Image
    VkBufferImageCopy region = {};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = { (uint32_t)width, (uint32_t)height, 1 };
    vkCmdCopyBufferToImage(vkCtx.commandBuffer, stagingBuffer, inputImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // Transition Input Image: TransferDst -> ShaderReadOnly
    transitionImageLayout(inputImage, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

    // Transition Output Images: Undefined -> General (for writing)
    for(const auto& img : outputImages) {
        transitionImageLayout(img, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    }

    // Bind and Dispatch
    vkCmdBindPipeline(vkCtx.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vkCtx.pipeline);
    vkCmdBindDescriptorSets(vkCtx.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vkCtx.pipelineLayout, 0, 1, &vkCtx.descriptorSet, 0, nullptr);
    vkCmdDispatch(vkCtx.commandBuffer, 512 / 16, 512 / 16, 6);

    // Transition Output Images: General -> TransferSrc (for readback)
    for(const auto& img : outputImages) {
        transitionImageLayout(img, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    }

    // Copy Images to Readback Buffer
    VkDeviceSize faceSize = 512 * 512 * 4;
    for(int i=0; i<6; ++i) {
        VkBufferImageCopy copyRegion = {};
        copyRegion.bufferOffset = i * faceSize;
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = { 512, 512, 1 };
        vkCmdCopyImageToBuffer(vkCtx.commandBuffer, outputImages[i], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, 1, &copyRegion);
    }

    vkEndCommandBuffer(vkCtx.commandBuffer);

    // 3. Submit
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &vkCtx.commandBuffer;

    vkQueueSubmit(vkCtx.queue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(vkCtx.queue);

    // 4. Readback happens when user accesses buffer mapped memory, or we copy it here.
    // For now, we leave it in readbackBuffer host memory.
}

cv::Mat VulkanCompute::getOutputFace(int index) {
    if (index < 0 || index >= 6 || !readbackBufferMemory) {
        return cv::Mat();
    }

    // Map Memory
    VkDeviceSize faceSize = 512 * 512 * 4; // RGBA
    VkDeviceSize offset = index * faceSize;
    void* data;
    if (vkMapMemory(vkCtx.device, readbackBufferMemory, offset, faceSize, 0, &data) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to map readback buffer memory for face %d", index);
        return cv::Mat();
    }

    // Construct cv::Mat
    // Data is RGBA, we likely need Gray or RGB for SLAM.
    // OpenCV ORB usually needs Grayscale.
    // Let's create RGBA first, then convert.
    cv::Mat faceRGBA(512, 512, CV_8UC4, data);

    // We must clone it because unmapping memory will invalidate 'data' pointer
    // if we were just wrapping it.
    // Wait, vkUnmap doesn't necessarily invalidate the bytes if we copy them out.
    // faceRGBA owns no data, it just points to 'data'.

    cv::Mat faceGray;
    cv::cvtColor(faceRGBA, faceGray, cv::COLOR_RGBA2GRAY);

    // Unmap
    vkUnmapMemory(vkCtx.device, readbackBufferMemory);

    return faceGray;
}

uint32_t VulkanCompute::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(vkCtx.physicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    throw std::runtime_error("failed to find suitable memory type!");
}

void VulkanCompute::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory) {
    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VK_CHECK(vkCreateBuffer(vkCtx.device, &bufferInfo, nullptr, &buffer));

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(vkCtx.device, buffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits, properties);

    VK_CHECK(vkAllocateMemory(vkCtx.device, &allocInfo, nullptr, &bufferMemory));
    vkBindBufferMemory(vkCtx.device, buffer, bufferMemory, 0);
}

void VulkanCompute::createImage(uint32_t width, uint32_t height, VkFormat format, VkImageTiling tiling, VkImageUsageFlags usage, VkMemoryPropertyFlags properties, VkImage& image, VkDeviceMemory& imageMemory) {
    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = width;
    imageInfo.extent.height = height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = format;
    imageInfo.tiling = tiling;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = usage;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VK_CHECK(vkCreateImage(vkCtx.device, &imageInfo, nullptr, &image));

    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(vkCtx.device, image, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits, properties);

    VK_CHECK(vkAllocateMemory(vkCtx.device, &allocInfo, nullptr, &imageMemory));
    vkBindImageMemory(vkCtx.device, image, imageMemory, 0);
}

VkImageView VulkanCompute::createImageView(VkImage image, VkFormat format, VkImageViewType viewType) {
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = viewType;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    VkImageView imageView;
    VK_CHECK(vkCreateImageView(vkCtx.device, &viewInfo, nullptr, &imageView));
    return imageView;
}

void VulkanCompute::transitionImageLayout(VkImage image, VkFormat format, VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;

    VkPipelineStageFlags sourceStage;
    VkPipelineStageFlags destinationStage;

    if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        destinationStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_GENERAL) {
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        destinationStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_GENERAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        sourceStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else {
        // Fallback
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = 0;
        sourceStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        destinationStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    }

    vkCmdPipelineBarrier(
        vkCtx.commandBuffer,
        sourceStage, destinationStage,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );
}
