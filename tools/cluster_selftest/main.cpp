// SPDX-License-Identifier: Apache-2.0
// Standalone harness for the S17 cluster acceleration structure self-test. Build:
//   g++ -O2 -std=c++17 -I<dxvk-remix>/include/vulkan/include -I<dxvk-remix>/src/dxvk/rtx_render/cluster_selftest main.cpp \
//       <dxvk-remix>/src/dxvk/rtx_render/cluster_selftest/rtx_cluster_selftest.cpp -lvulkan -o cluster_selftest
#include "rtx_cluster_selftest.h"
#include <cstdio>
#include <cstring>
#include <vector>

int main() {
  VkApplicationInfo ai = { VK_STRUCTURE_TYPE_APPLICATION_INFO }; ai.apiVersion = VK_API_VERSION_1_3;
  VkInstanceCreateInfo ici = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO }; ici.pApplicationInfo = &ai;
  VkInstance inst; if (vkCreateInstance(&ici, nullptr, &inst)) return 1;
  uint32_t n = 0; vkEnumeratePhysicalDevices(inst, &n, nullptr); std::vector<VkPhysicalDevice> pds(n); vkEnumeratePhysicalDevices(inst, &n, pds.data());
  VkPhysicalDevice pd = VK_NULL_HANDLE; VkPhysicalDeviceProperties props;
  for (auto p : pds) { vkGetPhysicalDeviceProperties(p, &props); if (props.vendorID == 0x10de) { pd = p; break; } }
  if (!pd) return 2;
  uint32_t qn = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, nullptr); std::vector<VkQueueFamilyProperties> qf(qn); vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, qf.data());
  uint32_t fam = 0; for (uint32_t i = 0; i < qn; i++) if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { fam = i; break; }
  float prio = 1.0f; VkDeviceQueueCreateInfo qci = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, fam, 1, &prio };
  uint32_t extensionCount = 0;
  vkEnumerateDeviceExtensionProperties(pd, nullptr, &extensionCount, nullptr);
  std::vector<VkExtensionProperties> supported(extensionCount);
  vkEnumerateDeviceExtensionProperties(pd, nullptr, &extensionCount, supported.data());
  bool hasCluster = false, hasPartitioned = false;
  for (const auto& extension : supported) {
    if (!std::strcmp(extension.extensionName, VK_NV_CLUSTER_ACCELERATION_STRUCTURE_EXTENSION_NAME)) {
      hasCluster = true;
      printf("cluster extension revision %u\n", extension.specVersion);
    }
    hasPartitioned |= !std::strcmp(extension.extensionName, VK_NV_PARTITIONED_ACCELERATION_STRUCTURE_EXTENSION_NAME);
  }
  if (!hasCluster) { puts("SKIP: cluster acceleration structures unsupported"); vkDestroyInstance(inst, nullptr); return 77; }
  VkPhysicalDeviceClusterAccelerationStructurePropertiesNV cp {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CLUSTER_ACCELERATION_STRUCTURE_PROPERTIES_NV};
  VkPhysicalDeviceProperties2 queried {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &cp};
  vkGetPhysicalDeviceProperties2(pd, &queried);
  printf("GPU %s driver %u maxTriangles %u maxVertices %u maxGeometryIndex %u align CLAS/BLAS/scratch %u/%u/%u\n", props.deviceName, props.driverVersion,
    cp.maxTrianglesPerCluster, cp.maxVerticesPerCluster, cp.maxClusterGeometryIndex, cp.clusterByteAlignment, cp.clusterBottomLevelByteAlignment, cp.clusterScratchByteAlignment);
  VkPhysicalDevicePartitionedAccelerationStructureFeaturesNV pf = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PARTITIONED_ACCELERATION_STRUCTURE_FEATURES_NV, nullptr, VK_TRUE };
  VkPhysicalDeviceClusterAccelerationStructureFeaturesNV cf = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CLUSTER_ACCELERATION_STRUCTURE_FEATURES_NV, &pf, VK_TRUE };
  cf.pNext = hasPartitioned ? &pf : nullptr;
  VkPhysicalDeviceRayQueryFeaturesKHR rq = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR, &cf, VK_TRUE };
  VkPhysicalDeviceAccelerationStructureFeaturesKHR as = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR, &rq, VK_TRUE };
  VkPhysicalDeviceVulkan12Features f12 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, &as }; f12.bufferDeviceAddress = VK_TRUE;
  VkPhysicalDeviceFeatures2 f2 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f12 };
  const char* exts[] = { "VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations", "VK_NV_cluster_acceleration_structure", "VK_NV_partitioned_acceleration_structure" };
  vkGetPhysicalDeviceFeatures2(pd, &f2);
  if (!cf.clusterAccelerationStructure || !as.accelerationStructure || !rq.rayQuery || !f12.bufferDeviceAddress) {
    puts("SKIP: required feature is unavailable"); vkDestroyInstance(inst, nullptr); return 77;
  }
  hasPartitioned &= pf.partitionedAccelerationStructure;
  cf.pNext = hasPartitioned ? &pf : nullptr;
  VkDeviceCreateInfo dci = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &f2, 0, 1, &qci, 0, nullptr, hasPartitioned ? 5u : 4u, exts };
  VkDevice dev; VkResult r = vkCreateDevice(pd, &dci, nullptr, &dev); if (r) { printf("vkCreateDevice failed %d\n", r); return 3; }
  dxvk::clusterselftest::Env env;
  env.physicalDevice = pd; env.device = dev; env.queueFamily = fam; vkGetDeviceQueue(dev, fam, 0, &env.queue);
  env.getProc = [dev](const char* n) { return vkGetDeviceProcAddr(dev, n); }; vkGetPhysicalDeviceMemoryProperties(pd, &env.memProps); env.timestampPeriodNs = props.limits.timestampPeriod; env.hasPartitioned = hasPartitioned;
  env.log = [](const std::string& s) { printf("%s\n", s.c_str()); fflush(stdout); };
  bool ok = dxvk::clusterselftest::run(env);
  vkDestroyDevice(dev, nullptr);
  vkDestroyInstance(inst, nullptr);
  return ok ? 0 : 4;
}
