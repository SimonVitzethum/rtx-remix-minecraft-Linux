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

#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

#include "../../../src/dxvk/rtx_render/rtx_ngx_vulkan.h"

namespace {
  std::vector<VkImageMemoryBarrier> barriers;
  std::vector<VkBufferMemoryBarrier> bufferBarriers;
  bool cleared = false;
  uint32_t filled = 0;
  VkCommandBuffer expectedCommand = reinterpret_cast<VkCommandBuffer>(1);
  VkImage expectedImage = reinterpret_cast<VkImage>(2);
  VkBuffer expectedBuffer = reinterpret_cast<VkBuffer>(5);
  const VkImageLayout expectedLayout = VK_IMAGE_LAYOUT_GENERAL;
  const VkClearColorValue expectedColor = {{0.1f, 0.2f, 0.3f, 0.4f}};

  void check(bool condition) {
    if (!condition) {
      throw std::runtime_error("NGX Vulkan dispatch contract violated");
    }
  }

  VKAPI_ATTR void VKAPI_CALL passthrough() { }
}

extern "C" {
  VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice, const char* pName) {
    return std::strcmp(pName, "unsupported") == 0 ? nullptr : passthrough;
  }

  VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char* pName) {
    return std::strcmp(pName, "unsupported") == 0 ? nullptr : passthrough;
  }

  VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier(VkCommandBuffer commandBuffer,
      VkPipelineStageFlags srcStages, VkPipelineStageFlags dstStages, VkDependencyFlags,
      uint32_t memoryCount, const VkMemoryBarrier*, uint32_t bufferCount, const VkBufferMemoryBarrier* pBuffers,
      uint32_t imageCount, const VkImageMemoryBarrier* pImages) {
    check(commandBuffer == expectedCommand);
    check(srcStages == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && dstStages == VK_PIPELINE_STAGE_TRANSFER_BIT);
    check(memoryCount == 0);
    if (bufferCount) {
      check(imageCount == 0 && bufferCount == 1);
      check(pBuffers[0].buffer == expectedBuffer);
      check(pBuffers[0].srcAccessMask == (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT));
      check(pBuffers[0].dstAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
      check(pBuffers[0].srcQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
      check(pBuffers[0].dstQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
      check(bufferBarriers.size() == filled);
      bufferBarriers.push_back(pBuffers[0]);
      return;
    }
    check(!cleared && imageCount == 1);
    check(pImages[0].image == expectedImage);
    check(pImages[0].oldLayout == expectedLayout && pImages[0].newLayout == expectedLayout);
    check(pImages[0].srcAccessMask & VK_ACCESS_MEMORY_WRITE_BIT);
    check(pImages[0].dstAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
    check(pImages[0].srcQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    check(pImages[0].dstQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    barriers.push_back(pImages[0]);
  }

  VKAPI_ATTR void VKAPI_CALL vkCmdClearColorImage(VkCommandBuffer commandBuffer, VkImage image,
      VkImageLayout layout, const VkClearColorValue* pColor, uint32_t rangeCount,
      const VkImageSubresourceRange* pRanges) {
    check(commandBuffer == expectedCommand && image == expectedImage && layout == expectedLayout);
    check(std::memcmp(pColor, &expectedColor, sizeof(expectedColor)) == 0);
    check(barriers.size() == rangeCount);
    for (uint32_t i = 0; i < rangeCount; ++i) {
      check(std::memcmp(&barriers[i].subresourceRange, &pRanges[i], sizeof(pRanges[i])) == 0);
    }
    cleared = true;
  }

  VKAPI_ATTR void VKAPI_CALL vkCmdFillBuffer(VkCommandBuffer commandBuffer, VkBuffer buffer,
      VkDeviceSize offset, VkDeviceSize size, uint32_t data) {
    check(commandBuffer == expectedCommand && buffer == expectedBuffer);
    check(bufferBarriers.size() == filled + 1 && filled < 2);
    check(offset == (filled ? 64 : 0));
    check(size == (filled ? VK_WHOLE_SIZE : 128));
    check(data == 0x12345678);
    check(bufferBarriers.back().offset == offset && bufferBarriers.back().size == size);
    ++filled;
  }
}

int main() {
  try {
    using namespace dxvk::ngx;
    const auto instance = reinterpret_cast<VkInstance>(3);
    const auto device = reinterpret_cast<VkDevice>(4);
    check(getInstanceProcAddr(instance, "unsupported") == nullptr);
    check(getInstanceProcAddr(instance, "vkGetInstanceProcAddr") == reinterpret_cast<PFN_vkVoidFunction>(getInstanceProcAddr));
    check(getDeviceProcAddr(device, "unsupported") == nullptr);
    check(getInstanceProcAddr(instance, "vkCreateDevice") == passthrough);
    check(getDeviceProcAddr(device, "vkCmdDraw") == passthrough);
    auto resolver = reinterpret_cast<PFN_vkGetDeviceProcAddr>(getInstanceProcAddr(instance, "vkGetDeviceProcAddr"));
    check(resolver == getDeviceProcAddr);
    auto clear = reinterpret_cast<PFN_vkCmdClearColorImage>(resolver(device, "vkCmdClearColorImage"));
    check(clear == clearColorImage);
    check(getInstanceProcAddr(instance, "vkCmdClearColorImage") == reinterpret_cast<PFN_vkVoidFunction>(clear));
    const VkImageSubresourceRange ranges[] = {
      { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
      { VK_IMAGE_ASPECT_COLOR_BIT, 2, 3, 4, 5 }
    };
    clear(expectedCommand, expectedImage, expectedLayout, &expectedColor, 2, ranges);
    check(cleared);
    auto fill = reinterpret_cast<PFN_vkCmdFillBuffer>(resolver(device, "vkCmdFillBuffer"));
    check(fill == fillBuffer);
    check(getInstanceProcAddr(instance, "vkCmdFillBuffer") == reinterpret_cast<PFN_vkVoidFunction>(fill));
    fill(expectedCommand, expectedBuffer, 0, 128, 0x12345678);
    fill(expectedCommand, expectedBuffer, 64, VK_WHOLE_SIZE, 0x12345678);
    check(filled == 2);
    std::cout << "PASS NGX resolver forwarding and exact transfer synchronization for image clears and buffer fills\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
