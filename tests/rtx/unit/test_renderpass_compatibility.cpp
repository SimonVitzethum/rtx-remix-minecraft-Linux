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

#include <array>
#include <cstring>
#include <iostream>
#include <tuple>
#include <vector>

#include "../../../src/dxvk/dxvk_renderpass.h"

namespace {
  std::vector<std::vector<VkSubpassDependency>> dependencies;

  VKAPI_ATTR VkResult VKAPI_CALL createRenderPass(VkDevice, const VkRenderPassCreateInfo* pInfo,
      const VkAllocationCallbacks*, VkRenderPass* pPass) {
    dependencies.emplace_back(pInfo->pDependencies, pInfo->pDependencies + pInfo->dependencyCount);
    *pPass = reinterpret_cast<VkRenderPass>(dependencies.size());
    return VK_SUCCESS;
  }

  VKAPI_ATTR void VKAPI_CALL destroyRenderPass(VkDevice, VkRenderPass, const VkAllocationCallbacks*) { }

  auto fields(const VkSubpassDependency& dep) {
    return std::tie(dep.srcSubpass, dep.dstSubpass, dep.srcStageMask, dep.dstStageMask,
      dep.srcAccessMask, dep.dstAccessMask, dep.dependencyFlags);
  }

  void check(bool condition) {
    if (!condition) throw std::runtime_error("Incompatible render pass dependency variants");
  }
}

// Intercept the production render-pass creation boundary without creating a GPU device.
namespace dxvk::vk {
  DeviceLoader::DeviceLoader(bool, VkInstance, VkDevice device)
  : m_getDeviceProcAddr(nullptr), m_device(device), m_owned(false) { }

  PFN_vkVoidFunction DeviceLoader::sym(const char* pName) const {
    if (std::strcmp(pName, "vkCreateRenderPass") == 0) return reinterpret_cast<PFN_vkVoidFunction>(createRenderPass);
    if (std::strcmp(pName, "vkDestroyRenderPass") == 0) return reinterpret_cast<PFN_vkVoidFunction>(destroyRenderPass);
    return nullptr;
  }

  DeviceFn::DeviceFn(bool owned, VkInstance instance, VkDevice device)
  : DeviceLoader(owned, instance, device) { }
  DeviceFn::~DeviceFn() { }
}

namespace dxvk {
  Logger Logger::s_instance("test_renderpass_compatibility.log");
}

int main() {
  try {
    dxvk::Rc<dxvk::vk::DeviceFn> functions = new dxvk::vk::DeviceFn(false, VK_NULL_HANDLE, VK_NULL_HANDLE);
    uint32_t cases = 0;
    for (uint32_t formatCase = 0; formatCase < 4; ++formatCase) {
      dxvk::DxvkRenderPassFormat format;
      if (formatCase != 0) {
        format.color[0] = { VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
      }
      if (formatCase >= 2) {
        format.depth = { VK_FORMAT_D24_UNORM_S8_UINT, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
        format.sampleCount = VK_SAMPLE_COUNT_4_BIT;
      }
      if (formatCase == 3) {
        format.color[3] = { VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_LAYOUT_GENERAL };
        format.depth.layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
      }
      dxvk::DxvkRenderPass pass(functions, format);
      auto expected = dependencies.back();
      for (auto color : { VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE }) {
        for (auto depth : { VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE }) {
          for (auto stencil : { VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE }) {
            dxvk::DxvkRenderPassOps ops;
            for (auto& op : ops.colorOps) {
              op.loadOp = color;
              op.loadLayout = VK_IMAGE_LAYOUT_GENERAL;
              op.storeLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            ops.depthOps.loadOpD = depth;
            ops.depthOps.loadOpS = stencil;
            ops.depthOps.loadLayout = VK_IMAGE_LAYOUT_GENERAL;
            ops.depthOps.storeLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
            ops.barrier = { VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
              VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT };
            auto handle = pass.getHandle(ops);
            const auto& actual = dependencies.at(reinterpret_cast<uintptr_t>(handle) - 1);
            check(expected.size() == actual.size());
            for (size_t i = 0; i < expected.size(); ++i) check(fields(expected[i]) == fields(actual[i]));
            check(handle == pass.getHandle(ops));
            ++cases;
          }
        }
      }
    }
    std::cout << "PASS " << cases << " production render pass variants\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
