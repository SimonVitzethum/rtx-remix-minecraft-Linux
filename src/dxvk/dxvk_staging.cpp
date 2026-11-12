// NV-DXVK start: Passive staging capacity diagnostics
#include <algorithm>
#include <chrono>
#include <cstdlib>

#include "../util/log/log.h"
#include "../util/util_string.h"
// NV-DXVK end

#include "dxvk_device.h"
#include "dxvk_staging.h"

namespace dxvk {
  // NV-DXVK start: Passive staging capacity diagnostics
  namespace {
    struct StagingCapacityStats {
      uint64_t requests = 0, requestedBytes = 0, allocations = 0, allocatedBytes = 0;
      uint64_t wraps = 0, spills = 0, maxRequest = 0, maxOffset = 0, allocationNanos = 0;
    };
  }
  // NV-DXVK end
  
  DxvkStagingBuffer::DxvkStagingBuffer(
    const Rc<DxvkDevice>&     device,
          VkDeviceSize        size)
  : m_device(device), m_offset(0), m_size(size) {

  }


  DxvkStagingBuffer::~DxvkStagingBuffer() {

  }


  DxvkBufferSlice DxvkStagingBuffer::alloc(VkDeviceSize align, VkDeviceSize size) {
    DxvkBufferCreateInfo info;
    info.size   = size;
    info.usage  = VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT
                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT
                | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    info.access = VK_ACCESS_TRANSFER_READ_BIT
                | VK_ACCESS_SHADER_READ_BIT;

    VkDeviceSize alignedSize = dxvk::align(size, align);
    VkDeviceSize alignedOffset = dxvk::align(m_offset, align);

    // NV-DXVK start: Passive staging capacity diagnostics
    static const bool trace = std::getenv("REMIX_TRACE_SUBMIT") != nullptr;
    static thread_local StagingCapacityStats stats;
    if (trace) {
      ++stats.requests;
      stats.requestedBytes += size;
      stats.maxRequest = std::max<uint64_t>(stats.maxRequest, size);
      stats.maxOffset = std::max<uint64_t>(stats.maxOffset, m_offset);
    }
    auto allocate = [&](VkDeviceSize bytes, bool spill) {
      info.size = bytes;
      const auto begin = trace ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point {};
      auto buffer = m_device->createBuffer(info,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        DxvkMemoryStats::Category::AppBuffer, "DxvkStagingBuffer");
      if (trace) {
        ++stats.allocations;
        stats.allocatedBytes += bytes;
        stats.spills += spill;
        stats.wraps += !spill && m_buffer != nullptr;
        stats.allocationNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count();
        Logger::info(str::format("[StagingCapacity] frame=", m_device->getCurrentFrameId(),
          " requests=", stats.requests, " requested_bytes=", stats.requestedBytes,
          " allocations=", stats.allocations, " allocated_bytes_total=", stats.allocatedBytes,
          " wraps=", stats.wraps, " spills=", stats.spills, " max_request_bytes=", stats.maxRequest,
          " offset_high_water_bytes=", stats.maxOffset, " allocation_cpu_ns=", stats.allocationNanos,
          " chunk_capacity=", m_size, " alignment=", align, " pending=", m_device->pendingSubmissions(),
          " allocator_forced_flushes=0 allocator_forced_waits=0"));
      }
      return buffer;
    };

    if (2 * alignedSize > m_size) {
      return DxvkBufferSlice(allocate(size, true));
    }

    if (alignedOffset + alignedSize > m_size || m_buffer == nullptr) {
      m_buffer = allocate(m_size, false);
      alignedOffset = 0;
    }
    // NV-DXVK end

    DxvkBufferSlice slice(m_buffer, alignedOffset, size);
    m_offset = alignedOffset + alignedSize;
    return slice;
  }


  void DxvkStagingBuffer::reset() {
    m_buffer = nullptr;
    m_offset = 0;
  }
  
}
