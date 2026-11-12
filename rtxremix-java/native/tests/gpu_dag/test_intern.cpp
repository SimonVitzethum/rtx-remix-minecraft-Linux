/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#include <vulkan/vulkan.h>
#include "voxel_dag.h"
#include <array>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <random>
#include <stdexcept>
#include <vector>

namespace {
void check(VkResult result, const char* what) {
  if (result != VK_SUCCESS) throw std::runtime_error(std::string(what) + ": " + std::to_string(result));
}
uint32_t validationErrors = 0;
VKAPI_ATTR VkBool32 VKAPI_CALL validation(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
  if (severity & (VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)) {
    ++validationErrors;
    std::cerr << data->pMessage << '\n';
  }
  return VK_FALSE;
}
struct Buffer { VkBuffer buffer = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; void* mapped = nullptr; VkDeviceSize bytes = 0; };
struct Context {
  VkInstance instance = VK_NULL_HANDLE;
  VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  VkCommandPool pool = VK_NULL_HANDLE;
  VkCommandBuffer command = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  VkShaderModule shader = VK_NULL_HANDLE;
  VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
  VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkPipeline buildPipeline = VK_NULL_HANDLE;
  VkShaderModule buildShader = VK_NULL_HANDLE;
  VkDescriptorPool descriptors = VK_NULL_HANDLE;
  VkDescriptorSet set = VK_NULL_HANDLE;
  VkQueryPool queries = VK_NULL_HANDLE;
  std::array<Buffer,13> buffers {};
  VkPhysicalDeviceMemoryProperties memory {};
  float timestampPeriod = 0;
  uint32_t family = 0;
  explicit Context(const char* filename, const char* buildFilename) {
    VkApplicationInfo app {VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "GPU DAG intern correctness gate"; app.apiVersion = VK_API_VERSION_1_2;
    const char* layer = "VK_LAYER_KHRONOS_validation"; const char* extensions[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME,VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME};
    VkValidationFeatureEnableEXT feature = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT;
    VkValidationFeaturesEXT validationInfo {VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT}; validationInfo.enabledValidationFeatureCount = 1; validationInfo.pEnabledValidationFeatures = &feature;
    VkInstanceCreateInfo create {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; create.pApplicationInfo = &app;
    create.enabledLayerCount = 1; create.ppEnabledLayerNames = &layer; create.enabledExtensionCount = 2; create.ppEnabledExtensionNames = extensions; create.pNext = &validationInfo;
    check(vkCreateInstance(&create,nullptr,&instance),"instance");
    VkDebugUtilsMessengerCreateInfoEXT debug {VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debug.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    debug.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT; debug.pfnUserCallback = validation;
    auto debugCreate = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance,"vkCreateDebugUtilsMessengerEXT"));
    check(debugCreate(instance,&debug,nullptr,&messenger),"debug messenger");
    uint32_t count = 0; check(vkEnumeratePhysicalDevices(instance,&count,nullptr),"physical count");
    std::vector<VkPhysicalDevice> devices(count); check(vkEnumeratePhysicalDevices(instance,&count,devices.data()),"physical devices");
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    for (auto candidate : devices) { VkPhysicalDeviceProperties p; vkGetPhysicalDeviceProperties(candidate,&p); if (p.vendorID == 0x10de) physical = candidate; }
    if (!physical) throw std::runtime_error("No NVIDIA GPU; do not silently test a CPU device");
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical,&props); timestampPeriod = props.limits.timestampPeriod;
    std::cout << "device=" << props.deviceName << '\n'; vkGetPhysicalDeviceMemoryProperties(physical,&memory);
    vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,nullptr); std::vector<VkQueueFamilyProperties> families(count); vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,families.data());
    bool found = false;
    for (uint32_t i = 0; i < count; ++i) if ((families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && families[i].timestampValidBits) { family = i; found = true; break; }
    if (!found) throw std::runtime_error("No timestamp-capable compute queue");
    float priority = 1; VkDeviceQueueCreateInfo queueInfo {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; queueInfo.queueFamilyIndex = family; queueInfo.queueCount = 1; queueInfo.pQueuePriorities = &priority;
    VkDeviceCreateInfo deviceInfo {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; deviceInfo.queueCreateInfoCount = 1; deviceInfo.pQueueCreateInfos = &queueInfo;
    check(vkCreateDevice(physical,&deviceInfo,nullptr,&device),"device"); vkGetDeviceQueue(device,family,0,&queue);
    VkCommandPoolCreateInfo poolInfo {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; poolInfo.queueFamilyIndex = family; poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    check(vkCreateCommandPool(device,&poolInfo,nullptr,&pool),"pool"); VkCommandBufferAllocateInfo allocate {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; allocate.commandPool = pool; allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; allocate.commandBufferCount = 1; check(vkAllocateCommandBuffers(device,&allocate,&command),"command");
    VkFenceCreateInfo fenceInfo {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device,&fenceInfo,nullptr,&fence),"fence");
    std::ifstream input(filename,std::ios::binary | std::ios::ate); if (!input) throw std::runtime_error("Missing shader"); auto size = input.tellg(); if (size <= 0 || uint64_t(size) % 4) throw std::runtime_error("Invalid shader length"); std::vector<uint32_t> words(size_t(size)/4); input.seekg(0); input.read(reinterpret_cast<char*>(words.data()),size);
    VkShaderModuleCreateInfo shaderInfo {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; shaderInfo.codeSize = words.size()*4; shaderInfo.pCode = words.data(); check(vkCreateShaderModule(device,&shaderInfo,nullptr,&shader),"shader");
    std::array<VkDescriptorSetLayoutBinding,13> bindings {};
    for (uint32_t i = 0; i < bindings.size(); ++i) { bindings[i].binding = i; bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; bindings[i].descriptorCount = 1; bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT; }
    VkDescriptorSetLayoutCreateInfo setInfo {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; setInfo.bindingCount = bindings.size(); setInfo.pBindings = bindings.data(); check(vkCreateDescriptorSetLayout(device,&setInfo,nullptr,&setLayout),"set layout");
    VkPushConstantRange range {VK_SHADER_STAGE_COMPUTE_BIT,0,64}; VkPipelineLayoutCreateInfo layoutInfo {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; layoutInfo.setLayoutCount = 1; layoutInfo.pSetLayouts = &setLayout; layoutInfo.pushConstantRangeCount = 1; layoutInfo.pPushConstantRanges = &range; check(vkCreatePipelineLayout(device,&layoutInfo,nullptr,&pipelineLayout),"pipeline layout");
    VkComputePipelineCreateInfo pipelineInfo {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pipelineInfo.layout = pipelineLayout; pipelineInfo.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}; pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; pipelineInfo.stage.module = shader; pipelineInfo.stage.pName = "main"; check(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pipelineInfo,nullptr,&pipeline),"pipeline");
    {
      std::ifstream stream(buildFilename,std::ios::binary | std::ios::ate);
      if (!stream) throw std::runtime_error("Missing build shader");
      auto length = stream.tellg(); std::vector<uint32_t> code(size_t(length)/4);
      stream.seekg(0); stream.read(reinterpret_cast<char*>(code.data()),length);
      VkShaderModuleCreateInfo info {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; info.codeSize = code.size()*4; info.pCode = code.data();
      check(vkCreateShaderModule(device,&info,nullptr,&buildShader),"build shader");
      pipelineInfo.stage.module = buildShader;
      check(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pipelineInfo,nullptr,&buildPipeline),"build pipeline");
    }
    VkDescriptorPoolSize poolSize {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,13}; VkDescriptorPoolCreateInfo descInfo {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; descInfo.maxSets = 1; descInfo.poolSizeCount = 1; descInfo.pPoolSizes = &poolSize; check(vkCreateDescriptorPool(device,&descInfo,nullptr,&descriptors),"descriptor pool");
    VkDescriptorSetAllocateInfo setAllocate {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; setAllocate.descriptorPool = descriptors; setAllocate.descriptorSetCount = 1; setAllocate.pSetLayouts = &setLayout; check(vkAllocateDescriptorSets(device,&setAllocate,&set),"descriptor set");
    VkQueryPoolCreateInfo queryInfo {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP; queryInfo.queryCount = 2; check(vkCreateQueryPool(device,&queryInfo,nullptr,&queries),"queries");
    const std::array<uint64_t,13> bytes {2097152*32,1048576*4,262145*32,2097152*4,2097152*4,32,2097152*16,256,65536*4,65792*4,12,8192,585*4};
    for (uint32_t i = 0; i < buffers.size(); ++i) { buffers[i] = makeBuffer(bytes[i]); VkDescriptorBufferInfo info {buffers[i].buffer,0,bytes[i]}; VkWriteDescriptorSet write {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; write.dstSet = set; write.dstBinding = i; write.descriptorCount = 1; write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; write.pBufferInfo = &info; vkUpdateDescriptorSets(device,1,&write,0,nullptr); }
  }
  Buffer makeBuffer(uint64_t bytes) {
    Buffer result; result.bytes = bytes; VkBufferCreateInfo info {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; info.size = bytes; info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT; info.sharingMode = VK_SHARING_MODE_EXCLUSIVE; check(vkCreateBuffer(device,&info,nullptr,&result.buffer),"buffer");
    VkMemoryRequirements need; vkGetBufferMemoryRequirements(device,result.buffer,&need); uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) if ((need.memoryTypeBits & (1u<<i)) && (memory.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type = i; break; }
    if (type == UINT32_MAX) throw std::runtime_error("No coherent test memory type");
    VkMemoryAllocateInfo allocate {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocate.allocationSize = need.size; allocate.memoryTypeIndex = type; check(vkAllocateMemory(device,&allocate,nullptr,&result.memory),"test memory"); check(vkBindBufferMemory(device,result.buffer,result.memory,0),"bind"); check(vkMapMemory(device,result.memory,0,bytes,0,&result.mapped),"map"); return result;
  }
  void reset() { for (auto& b : buffers) std::memset(b.mapped,0,size_t(b.bytes)); static_cast<uint32_t*>(buffers[5].mapped)[0] = 1; }
  double run(uint32_t count,uint32_t capacity = 32769,uint32_t hashMask = UINT32_MAX,uint32_t probes = 64) {
    auto status = static_cast<uint32_t*>(buffers[5].mapped); uint32_t committed = status[0];
    auto control = static_cast<uint32_t*>(buffers[7].mapped); control[0] = count; control[1] = committed;
    check(vkResetCommandBuffer(command,0),"reset command"); VkCommandBufferBeginInfo begin {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; check(vkBeginCommandBuffer(command,&begin),"begin");
    vkCmdResetQueryPool(command,queries,0,2); vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline); vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,1,&set,0,nullptr);
    VkMemoryBarrier barrier {VK_STRUCTURE_TYPE_MEMORY_BARRIER}; barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,0);
    for (uint32_t mode = 0; mode < 3; ++mode) {
      std::array<uint32_t,8> parameters {mode,65535,capacity,probes,hashMask,65535,0,0}; vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,64,parameters.data());
      vkCmdDispatch(command,(count+63)/64,1,1);
      barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }
    vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,1); barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT; vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr); check(vkEndCommandBuffer(command),"end");
    check(vkResetFences(device,1,&fence),"reset fence"); VkSubmitInfo submit {VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &command; check(vkQueueSubmit(queue,1,&submit,fence),"submit"); check(vkWaitForFences(device,1,&fence,VK_TRUE,10'000'000'000ull),"bounded test fence"); std::array<uint64_t,2> timestamps; check(vkGetQueryPoolResults(device,queries,0,2,sizeof(timestamps),timestamps.data(),8,VK_QUERY_RESULT_64_BIT),"timestamps"); return (timestamps[1]-timestamps[0])*double(timestampPeriod)/1e6;
  }
  void computeBarrier() {
    VkMemoryBarrier barrier {VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,0,1,&barrier,0,nullptr,0,nullptr);
  }
  void buildPass(uint32_t mode,uint32_t level,uint32_t lg,uint32_t pyramidLevel = 0,uint32_t count = 1,bool indirect = false) {
    std::array<uint32_t,12> parameters {mode,level,lg,1,0,2097152,1,3,2,65535,pyramidLevel,0};
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,buildPipeline);
    vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,48,parameters.data());
    if (indirect) vkCmdDispatchIndirect(command,buffers[10].buffer,0);
    else vkCmdDispatch(command,(count+63)/64,1,1);
    computeBarrier();
  }
  void internPass() {
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
    for (uint32_t mode = 0; mode < 3; ++mode) {
      std::array<uint32_t,8> parameters {mode,1048575,262145,64,UINT32_MAX,65535,0,0};
      vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,32,parameters.data());
      vkCmdDispatchIndirect(command,buffers[10].buffer,0);
      computeBarrier();
    }
  }
  double terrain(uint32_t lg,const std::vector<uint32_t>& samples,const std::vector<uint16_t>& exactVoxels) {
    reset();
    requireInput(samples.size() == size_t(256>>lg)*(256>>lg));
    std::memcpy(buffers[8].mapped,samples.data(),samples.size()*4);
    if (!exactVoxels.empty()) { requireInput(exactVoxels.size() == 4096); std::memcpy(buffers[11].mapped,exactVoxels.data(),8192); }
    check(vkResetCommandBuffer(command,0),"reset terrain command");
    VkCommandBufferBeginInfo begin {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; check(vkBeginCommandBuffer(command,&begin),"begin terrain");
    vkCmdResetQueryPool(command,queries,0,2);
    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,1,&set,0,nullptr);
    VkMemoryBarrier barrier {VK_STRUCTURE_TYPE_MEMORY_BARRIER}; barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,0);
    for (uint32_t level = 0; level <= 8-lg; ++level) { uint32_t n = (256>>lg)>>level; buildPass(0,0,lg,level,n*n); }
    if (!exactVoxels.empty()) {
      for (uint32_t level = 1; level <= 4; ++level) {
        buildPass(9,level,lg); buildPass(7,level,lg,0,0,true); internPass(); buildPass(8,level,lg,0,0,true);
      }
    }
    buildPass(1,8,lg);
    for (int level = 8; level >= 0; --level) { buildPass(2,uint32_t(level),lg); buildPass(3,uint32_t(level),lg,0,0,true); }
    for (uint32_t level = 0; level <= 8; ++level) {
      buildPass(4,level,lg); buildPass(5,level,lg,0,0,true); internPass(); buildPass(6,level,lg,0,0,true);
    }
    vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,1);
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    check(vkEndCommandBuffer(command),"end terrain"); check(vkResetFences(device,1,&fence),"reset terrain fence");
    VkSubmitInfo submit {VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
    check(vkQueueSubmit(queue,1,&submit,fence),"submit terrain"); check(vkWaitForFences(device,1,&fence,VK_TRUE,10'000'000'000ull),"bounded terrain fence");
    std::array<uint64_t,2> timestamps; check(vkGetQueryPoolResults(device,queries,0,2,sizeof(timestamps),timestamps.data(),8,VK_QUERY_RESULT_64_BIT),"terrain timestamps");
    return (timestamps[1]-timestamps[0])*double(timestampPeriod)/1e6;
  }
  static void requireInput(bool condition) { if (!condition) throw std::invalid_argument("Invalid GPU gate input"); }
  ~Context() {
    if (device) {
      for (auto& b : buffers) { if (b.mapped) vkUnmapMemory(device,b.memory); if (b.buffer) vkDestroyBuffer(device,b.buffer,nullptr); if (b.memory) vkFreeMemory(device,b.memory,nullptr); }
      if (buildPipeline) vkDestroyPipeline(device,buildPipeline,nullptr); if (buildShader) vkDestroyShaderModule(device,buildShader,nullptr);
      if (queries) vkDestroyQueryPool(device,queries,nullptr); if (descriptors) vkDestroyDescriptorPool(device,descriptors,nullptr); if (pipeline) vkDestroyPipeline(device,pipeline,nullptr); if (pipelineLayout) vkDestroyPipelineLayout(device,pipelineLayout,nullptr); if (setLayout) vkDestroyDescriptorSetLayout(device,setLayout,nullptr); if (shader) vkDestroyShaderModule(device,shader,nullptr); if (fence) vkDestroyFence(device,fence,nullptr); if (pool) vkDestroyCommandPool(device,pool,nullptr); vkDestroyDevice(device,nullptr);
    }
    if (messenger) reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance,"vkDestroyDebugUtilsMessengerEXT"))(instance,messenger,nullptr);
    if (instance) vkDestroyInstance(instance,nullptr);
  }
};
using Node = std::array<uint32_t,8>;
void require(bool value,const char* message) { if (!value) throw std::runtime_error(message); }
uint32_t voxel(uint32_t root,const uint32_t* words,uint32_t nodeCount,uint32_t x,uint32_t y,uint32_t z) {
  uint32_t ref = root;
  for (int level = 7; ref && !(ref & 0x80000000u); --level) {
    require(level >= 0 && ref < nodeCount,"Invalid GPU/reference DAG edge");
    uint32_t child = ((x>>level)&1) | (((y>>level)&1)<<1) | (((z>>level)&1)<<2);
    ref = words[size_t(ref)*8+child];
  }
  return ref;
}
void verifyTerrain(Context& c,uint32_t lg,const std::vector<uint32_t>& samples,const std::vector<uint16_t>& exactVoxels) {
  auto status = static_cast<uint32_t*>(c.buffers[5].mapped);
  auto control = static_cast<uint32_t*>(c.buffers[7].mapped);
  auto gpuWords = static_cast<uint32_t*>(c.buffers[2].mapped);
  if (status[1]) throw std::runtime_error("GPU terrain status error " + std::to_string(status[1]));
  // CPU construction is strictly an independent post-submission correctness oracle.
  rtx::voxel::Builder reference(lg,1);
  reference.surface(0,uint32_t(samples.size()),samples.data());
  if (!exactVoxels.empty()) reference.exact(1,3,2,exactVoxels.data());
  uint32_t count = reference.finish(); std::vector<uint32_t> cpuWords(size_t(count)*8);
  reference.copy(0,count*8,0,cpuWords.data());
  require(status[0] == count,"GPU and CPU canonical node counts differ");
  uint32_t gpuRoot = control[21],cpuRoot = reference.root(0);
  std::mt19937 random(0xDA665);
  for (uint32_t z = 0; z < 256; ++z) for (uint32_t x = 0; x < 256; ++x) {
    int32_t height = int16_t(samples[(z>>lg)*(256>>lg)+(x>>lg)]>>16);
    for (int32_t y : {0,height-1,height,height+1,height-int32_t(1u<<lg),height-2*int32_t(1u<<lg),255}) {
      if (y < 0 || y >= 256) continue;
      require(voxel(gpuRoot,gpuWords,status[0],x,uint32_t(y),z) == voxel(cpuRoot,cpuWords.data(),count,x,uint32_t(y),z),"GPU surface boundary mismatch");
    }
  }
  for (uint32_t i = 0; i < 20000; ++i) {
    uint32_t x = random()%256,y = random()%256,z = random()%256;
    require(voxel(gpuRoot,gpuWords,status[0],x,y,z) == voxel(cpuRoot,cpuWords.data(),count,x,y,z),"GPU random voxel mismatch");
  }
  if (!exactVoxels.empty()) for (uint32_t y = 0; y < 256; ++y) for (uint32_t z = 32; z < 48; ++z) for (uint32_t x = 16; x < 32; ++x)
    require(voxel(gpuRoot,gpuWords,status[0],x,y,z) == voxel(cpuRoot,cpuWords.data(),count,x,y,z),"GPU exact column/unspecified air mismatch");
  std::cout << "terrain_reference PASS lg=" << lg << " nodes=" << count << " spatial_records=" << control[2] << " cpu_build_during_gpu=0 independent_reference_after_gpu=1\n";
}
void verify(Context& c,uint32_t count) {
  auto keys = static_cast<Node*>(c.buffers[0].mapped); auto nodes = static_cast<Node*>(c.buffers[2].mapped); auto values = static_cast<uint32_t*>(c.buffers[4].mapped); auto status = static_cast<uint32_t*>(c.buffers[5].mapped);
  require(status[1] == 0,"Unexpected GPU status error"); std::map<Node,uint32_t> canonical;
  for (uint32_t i = 0; i < count; ++i) {
    bool uniform = (keys[i][0] == 0 || (keys[i][0]&0x80000000u)) && std::all_of(keys[i].begin()+1,keys[i].end(),[&](uint32_t v) { return v == keys[i][0]; });
    if (uniform) { require(values[i] == keys[i][0],"Uniform leaf/empty mismatch"); continue; }
    require(values[i] > 0 && values[i] < status[0],"Output edge bound"); require(nodes[values[i]] == keys[i],"Full eight-child key mismatch");
    auto [entry,inserted] = canonical.emplace(keys[i],values[i]); require(inserted || entry->second == values[i],"Duplicate key not canonicalized");
  }
}
}
int main(int argc,char** argv) {
  try {
    if (argc != 3) throw std::runtime_error("usage: test_intern intern.spv build.spv");
    Context c(argv[1],argv[2]); auto keys = static_cast<Node*>(c.buffers[0].mapped); auto status = static_cast<uint32_t*>(c.buffers[5].mapped);
    c.reset(); keys[0].fill(0); keys[1].fill(0x80001234u); double ms = c.run(2); verify(c,2); require(status[0] == 1,"Collapsed keys allocated nodes"); std::cout << "empty_uniform PASS gpu_ms=" << ms << '\n';
    c.reset(); std::mt19937 random(1234567);
    for (uint32_t i = 0; i < 32768; ++i) { if (i%3 == 0 && i) keys[i] = keys[i-1]; else for (auto& child : keys[i]) child = (random()%7 == 0) ? 0 : 0x80000000u | (random()&65535u); }
    ms = c.run(32768); verify(c,32768); uint32_t firstCount = status[0]; ms += c.run(32768); verify(c,32768); require(status[0] == firstCount,"Completed hash table did not reuse prior nodes"); std::cout << "random_repeated PASS nodes=" << status[0] << " duplicate_hits=" << status[5] << " gpu_ms=" << ms << '\n';
    c.reset(); for (uint32_t i = 0; i < 32; ++i) for (uint32_t k = 0; k < 8; ++k) keys[i][k] = 0x80000000u | ((i/2)*16+k); ms = c.run(32,32769,0,64); verify(c,32); require(status[0] == 17 && status[4] > 0,"Collision path not exercised"); std::cout << "full_key_collision PASS collisions=" << status[4] << " gpu_ms=" << ms << '\n';
    c.reset(); for (uint32_t i = 0; i < 32; ++i) for (uint32_t k = 0; k < 8; ++k) keys[i][k] = 0x80000000u | (i*16+k); ms = c.run(32,32769,0,2); require((status[1]&2) && status[3],"Probe bound did not report overflow"); std::cout << "bounded_probe_overflow PASS errors=" << status[1] << " gpu_ms=" << ms << '\n';
    c.reset(); for (uint32_t i = 0; i < 32; ++i) for (uint32_t k = 0; k < 8; ++k) keys[i][k] = 0x80000000u | (i*16+k); ms = c.run(32,2); require((status[1]&1) && status[2],"Node capacity overflow not reported"); std::cout << "node_capacity_overflow PASS errors=" << status[1] << " gpu_ms=" << ms << '\n';
    std::vector<uint32_t> samples(128*128,0x80000000u);
    ms = c.terrain(1,samples,{}); verifyTerrain(c,1,samples,{}); std::cout << "empty_terrain gpu_ms=" << ms << '\n';
    samples.assign(128*128,128u<<16 | 0x1234u);
    ms = c.terrain(1,samples,{}); verifyTerrain(c,1,samples,{}); std::cout << "uniform_terrain gpu_ms=" << ms << '\n';
    for (uint32_t lg = 1; lg <= 4; ++lg) {
      uint32_t n = 256>>lg; samples.resize(n*n);
      for (auto& packed : samples) packed = random()%13 == 0 ? 0x80000000u : ((1+random()%255)<<16) | (1+random()%65535);
      std::vector<uint16_t> exactVoxels(4096); for (auto& value : exactVoxels) value = random()%7 == 0 ? 0 : uint16_t(1+random()%65535);
      ms = c.terrain(lg,samples,exactVoxels); verifyTerrain(c,lg,samples,exactVoxels); std::cout << "random_exact_terrain gpu_ms=" << ms << '\n';
    }
    require(validationErrors == 0,"Khronos synchronization/validation warning or error"); std::cout << "GPU_INTERN_GATE_PASS validation_errors=0 cpu_builder_calls=0 (terrain GPU worklists/exact/LOD reference gate; game integration PENDING)\n";
  } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
