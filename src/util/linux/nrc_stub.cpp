// NRC has no Linux binary in this build. Every entry point reports an unsupported driver and the neural radiance cache
// stays disabled.
#include <windows.h>
#include <vulkan/vulkan.h>
#include <NrcVk.h>

namespace nrc {
  nrc_uint2 ComputeIdealTrainingDimensions(nrc_uint2 const& frameDimensions, uint32_t, float) { return frameDimensions; }

  namespace vulkan {
    uint32_t GetVulkanDeviceExtensions(char const* const*& out) { out = nullptr; return 0; }
    uint32_t GetVulkanInstanceExtensions(char const* const*& out) { out = nullptr; return 0; }
    uint32_t GetVulkanDeviceFeatures(char const* const*& out) { out = nullptr; return 0; }
    Status Initialize(const GlobalSettings&) { return Status::UnsupportedDriver; }
    void Shutdown() { }

    Status Context::Create(VkDevice, VkPhysicalDevice, VkInstance, Context*& out) { out = nullptr; return Status::UnsupportedDriver; }
    Status Context::Destroy(Context&) { return Status::UnsupportedDriver; }
    Status Context::GetBuffersAllocationInfo(const ContextSettings&, BuffersAllocationInfo&) { return Status::UnsupportedDriver; }
    Status Context::Configure(const ContextSettings&, const Buffers*, const char*) { return Status::UnsupportedDriver; }
    Status Context::PopulateShaderConstants(NrcConstants&) const { return Status::UnsupportedDriver; }
    Status Context::BeginFrame(VkCommandBuffer, const FrameSettings&) { return Status::UnsupportedDriver; }
    Status Context::QueryAndTrain(VkCommandBuffer, float*) { return Status::UnsupportedDriver; }
    Status Context::Resolve(VkCommandBuffer, VkImageView) { return Status::UnsupportedDriver; }
    Status Context::EndFrame(VkQueue) { return Status::UnsupportedDriver; }
    VkBufferUsageFlags Context::GetBufferUsageFlags(AllocationInfo const&) const { return 0; }
  }
}
