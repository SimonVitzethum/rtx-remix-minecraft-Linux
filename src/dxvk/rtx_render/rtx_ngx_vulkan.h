/*
* Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
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

#include <cstring>
#include <vulkan/vulkan.h>

namespace dxvk::ngx {

  // NGX layout transitions may make images available only to shaders before clearing them.
  // Order the clear against all earlier accesses through the application's Vulkan dispatch chain.
  inline VKAPI_ATTR void VKAPI_CALL clearColorImage(VkCommandBuffer commandBuffer, VkImage image,
      VkImageLayout layout, const VkClearColorValue* pColor, uint32_t rangeCount,
      const VkImageSubresourceRange* pRanges) {
    for (uint32_t i = 0; i < rangeCount; ++i) {
      VkImageMemoryBarrier barrier = { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
      barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
      barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      barrier.oldLayout = layout;
      barrier.newLayout = layout;
      barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barrier.image = image;
      barrier.subresourceRange = pRanges[i];
      ::vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }
    ::vkCmdClearColorImage(commandBuffer, image, layout, pColor, rangeCount, pRanges);
  }

  inline VKAPI_ATTR void VKAPI_CALL fillBuffer(VkCommandBuffer commandBuffer, VkBuffer buffer,
      VkDeviceSize offset, VkDeviceSize size, uint32_t data) {
    VkBufferMemoryBarrier barrier = { VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER };
    barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer = buffer;
    barrier.offset = offset;
    barrier.size = size;
    ::vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &barrier, 0, nullptr);
    ::vkCmdFillBuffer(commandBuffer, buffer, offset, size, data);
  }

  inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL getDeviceProcAddr(VkDevice device, const char* pName) {
    auto function = ::vkGetDeviceProcAddr(device, pName);
    if (function == nullptr) {
      return nullptr;
    }
    if (std::strcmp(pName, "vkCmdClearColorImage") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(clearColorImage);
    }
    if (std::strcmp(pName, "vkCmdFillBuffer") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(fillBuffer);
    }
    if (std::strcmp(pName, "vkGetDeviceProcAddr") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(getDeviceProcAddr);
    }
    return function;
  }

  inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL getInstanceProcAddr(VkInstance instance, const char* pName) {
    auto function = ::vkGetInstanceProcAddr(instance, pName);
    if (function == nullptr) {
      return nullptr;
    }
    if (std::strcmp(pName, "vkGetInstanceProcAddr") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(getInstanceProcAddr);
    }
    if (std::strcmp(pName, "vkCmdClearColorImage") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(clearColorImage);
    }
    if (std::strcmp(pName, "vkCmdFillBuffer") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(fillBuffer);
    }
    if (std::strcmp(pName, "vkGetDeviceProcAddr") == 0) {
      return reinterpret_cast<PFN_vkVoidFunction>(getDeviceProcAddr);
    }
    return function;
  }
}
