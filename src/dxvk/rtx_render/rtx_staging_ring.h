/*
* Copyright (c) 2025, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/

#pragma once

#include <algorithm>
#include <memory>

#include "dxvk_buffer.h"

namespace dxvk {

  // Allocates on demand within a fixed budget. Queued slices and GPU commands
  // retain the buffer until their temporary references and fences retire.
  class RtxStagingRing {
  public:
    struct Allocation {
      struct Lease {
        Rc<DxvkBuffer> buffer;
        explicit Lease(const Rc<DxvkBuffer>& buffer) : buffer(buffer) { buffer->acquire(DxvkAccess::Write); }
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        ~Lease() { buffer->release(DxvkAccess::Write); }
      };
      DxvkBufferSlice slice;
      std::shared_ptr<Lease> lease;
    };

    RtxStagingRing(const RtxStagingRing&) = delete;
    RtxStagingRing(RtxStagingRing&&) noexcept = delete;
    RtxStagingRing& operator=(const RtxStagingRing&) = delete;
    RtxStagingRing& operator=(RtxStagingRing&&) noexcept = delete;

    RtxStagingRing(const Rc<DxvkDevice>& device, VkDeviceSize budget)
      : m_device{ device }
      , m_budget{ budget }
      , m_offset{ 0 } { }


    ~RtxStagingRing() { }


    const VkDeviceSize budget() const { return m_budget; }


    // Producer only. The lease keeps queued slices alive until command tracking or cancellation.
    Allocation alloc(VkDeviceSize align, VkDeviceSize size) {
      if (!size || size > m_budget || !align || (align & (align - 1)) || align > kBufferAlignment) {
        return {};
      }
      const VkDeviceSize alignedSize = dxvk::align(size, align);
      if (alignedSize > m_budget) {
        return {};
      }
      if (!m_buffer.ptr() || !m_buffer->isInUse()) {
        m_offset = 0;
        const VkDeviceSize capacity = dxvk::align(std::max(std::min(m_budget, VkDeviceSize(1024 * 1024)), size), kBufferAlignment);
        if (!m_buffer.ptr() || m_buffer->info().size != capacity) {
          m_buffer = createBuffer(*m_device, capacity);
          if (capacity >= 4 * 1024 * 1024) {
            Logger::info(str::format("[TextureStaging] Pool allocated ", capacity, " bytes, ceiling ", m_budget));
          }
        }
      }
      const VkDeviceSize alignedOffset = dxvk::align(m_offset, align);
      if (alignedOffset > m_buffer->info().size || alignedSize > m_buffer->info().size - alignedOffset) {
        return {};
      }

      auto lease = std::make_shared<Allocation::Lease>(m_buffer);
      m_offset = alignedOffset + alignedSize;
      return { DxvkBufferSlice{ m_buffer, alignedOffset, size }, std::move(lease) };
    }


    // Producer only. Queued slices hold a temporary write reference until submission or cancellation.
    void trim() {
      if (m_buffer.ptr() && !m_buffer->isInUse()) {
        if (m_buffer->info().size >= 4 * 1024 * 1024) {
          Logger::info(str::format("[TextureStaging] Pool released ", m_buffer->info().size, " bytes"));
        }
        m_buffer = nullptr;
        m_offset = 0;
      }
    }


  private:
    static Rc<DxvkBuffer> createBuffer(DxvkDevice& device, VkDeviceSize size) {
      assert(size % kBufferAlignment == 0);
      DxvkBufferCreateInfo info{};
      {
        info.size = dxvk::align(size, kBufferAlignment);
        info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        info.stages =
          VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        info.access = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
      }
      return device.createBuffer(
        info,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
        DxvkMemoryStats::Category::AppBuffer, "RtxStagingRing");
    }


  private:
    const Rc<DxvkDevice> m_device;
    Rc<DxvkBuffer> m_buffer;
    const VkDeviceSize m_budget;
    VkDeviceSize m_offset;
  };

} // namespace dxvk
