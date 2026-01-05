#include "VulkanCompute.h"
#include <android/log.h>
#include <vector>
#include <stdexcept>
#include <cstring>

#define TAG "VulkanCompute"

// Helper macros for error checking
#define VK_CHECK(x) \
    do { \
        VkResult err = x; \
        if (err) { \
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Vulkan error: %d at line %d", err, __LINE__); \
        } \
    } while (0)

VulkanCompute::VulkanCompute(AAssetManager* assetManager) : assetManager(assetManager) {
    memset(&vkCtx, 0, sizeof(vkCtx));
}

VulkanCompute::~VulkanCompute() {
    if (vkCtx.device) {
        vkDeviceWaitIdle(vkCtx.device);
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

    createComputePipeline();
    createDescriptorSets();

    __android_log_print(ANDROID_LOG_INFO, TAG, "Vulkan Compute Initialized Successfully");
}

void VulkanCompute::createComputePipeline() {
    // A. Create Descriptor Set Layout
    VkDescriptorSetLayoutBinding bindings[2] = {};
    // Binding 0: Input Image (Sampler)
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // Binding 1: Output Images (Storage Image Array [6])
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 6;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;

    VK_CHECK(vkCreateDescriptorSetLayout(vkCtx.device, &layoutInfo, nullptr, &vkCtx.descriptorSetLayout));

    // B. Create Pipeline Layout
    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &vkCtx.descriptorSetLayout;

    VK_CHECK(vkCreatePipelineLayout(vkCtx.device, &pipelineLayoutInfo, nullptr, &vkCtx.pipelineLayout));

    // C. Load Shader Module
    // Load precompiled SPIR-V for the compute shader. Ensure the asset is compiled with e.g.:
    //   glslc equirect_to_cubemap.comp -o equirect_to_cubemap.comp.spv
    AAsset* file = AAssetManager_open(assetManager, "shaders/equirect_to_cubemap.comp.spv", AASSET_MODE_BUFFER);
    if (file) {
        size_t fileLength = AAsset_getLength(file);

        // SPIR-V is defined in 32-bit words; reject invalid sizes.
        if (fileLength > 0 && fileLength % sizeof(uint32_t) == 0) {
            const size_t wordCount = fileLength / sizeof(uint32_t);
            std::vector<uint32_t> spirv(wordCount);

            // Read the raw bytes into the aligned uint32_t buffer.
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
            } else {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create shader module");
            }
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Invalid SPIR-V size or alignment");
            AAsset_close(file);
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Shader file not found: shaders/equirect_to_cubemap.comp.spv");
    }
}

void VulkanCompute::createDescriptorSets() {
    // Create Descriptor Pool
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

    // Allocate Descriptor Set
    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = vkCtx.descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &vkCtx.descriptorSetLayout;

    VK_CHECK(vkAllocateDescriptorSets(vkCtx.device, &allocInfo, &vkCtx.descriptorSet));
}

void VulkanCompute::processImage(void* inputBuffer, int width, int height) {
    if (!vkCtx.pipeline) return;

    // This is the hot loop.

    // 1. Update Descriptor Sets with the new inputBuffer (wrapped in VkImageView)
    // We would need to create a VkImageView from inputBuffer and update vkCtx.descriptorSet

    // Stub call to update sets (conceptual)
    VkDescriptorImageInfo imageInfo = {};
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    // imageInfo.imageView = (VkImageView)inputBuffer; // Requires inputBuffer to be a valid handle
    imageInfo.sampler = VK_NULL_HANDLE; // Needs sampler

    VkWriteDescriptorSet descriptorWrite = {};
    descriptorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrite.dstSet = vkCtx.descriptorSet;
    descriptorWrite.dstBinding = 0;
    descriptorWrite.descriptorCount = 1;
    descriptorWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptorWrite.pImageInfo = &imageInfo;

    vkUpdateDescriptorSets(vkCtx.device, 1, &descriptorWrite, 0, nullptr);

    // 2. Record Command Buffer
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(vkCtx.commandBuffer, &beginInfo);

    vkCmdBindPipeline(vkCtx.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vkCtx.pipeline);
    vkCmdBindDescriptorSets(vkCtx.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vkCtx.pipelineLayout, 0, 1, &vkCtx.descriptorSet, 0, nullptr);

    // Dispatch: 6 faces. Workgroup size 16x16.
    vkCmdDispatch(vkCtx.commandBuffer, 512 / 16, 512 / 16, 6);

    vkEndCommandBuffer(vkCtx.commandBuffer);

    // 3. Submit
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &vkCtx.commandBuffer;

    vkQueueSubmit(vkCtx.queue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(vkCtx.queue);
}

void VulkanCompute::loadShader(const std::string& filename) {
    // Helper used inside createComputePipeline
}
