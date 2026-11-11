/*
* SPDX-License-Identifier: Apache-2.0
*
* S17 prototype: VK_NV_cluster_acceleration_structure self-test.
* Self-contained raw Vulkan (own queue submissions, own memory), usable from the
* standalone harness tools/cluster_selftest/main.cpp. The production renderer does not run this test at device creation.
*/
#pragma once

#include <vulkan/vulkan.h>
#include <functional>
#include <string>

namespace dxvk::clusterselftest {

  struct Env {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkQueue queue = VK_NULL_HANDLE;
    std::function<PFN_vkVoidFunction(const char*)> getProc;
    VkPhysicalDeviceMemoryProperties memProps = {};
    float timestampPeriodNs = 1.0f;
    bool hasPartitioned = false;   // VK_NV_partitioned_acceleration_structure enabled on the device
    std::function<void(const std::string&)> log;
  };

  // Builds a procedural terrain (64x64 clusters of 8x8 quads, 524288 triangles) into CLAS + cluster BLAS, culls clusters on the GPU,
  // traces rays against it and compares against an analytic reference and against a conventional KHR BLAS. Returns true if everything matched.
  bool run(const Env& env);

}
