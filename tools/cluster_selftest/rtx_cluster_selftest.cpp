/*
* SPDX-License-Identifier: Apache-2.0
*/
#include "rtx_cluster_selftest.h"
#include "rtx_cluster_selftest_cull_spv.h"
#include "rtx_cluster_selftest_trace_spv.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <vector>

namespace dxvk::clusterselftest {

namespace {

uint32_t kGrid = 64;                        // clusters per side (env REMIX_CLUSTER_AS_GRID, default 64)
constexpr uint32_t kQuadsPerSide = 8;       // quads per cluster side -> 64 quads = 128 triangles = 256 vertices per cluster
uint32_t kClusters = kGrid * kGrid;
constexpr uint32_t kTrisPerCluster = 128;
constexpr uint32_t kVertsPerCluster = 256;
uint32_t kRays = 512;                       // kRays x kRays rays straight down, 1 unit apart (= terrain extent)
constexpr float kOriginY = 100.0f;

float terrainHeight(int ix, int iz) {
  return 6.0f * std::sin(ix * 0.07f) * std::cos(iz * 0.05f) + 3.0f * std::sin((ix + iz) * 0.21f);
}

struct Fns {
#define F(n) PFN_##n n = nullptr;
  F(vkCreateBuffer) F(vkDestroyBuffer) F(vkGetBufferMemoryRequirements) F(vkAllocateMemory) F(vkFreeMemory) F(vkBindBufferMemory)
  F(vkMapMemory) F(vkUnmapMemory) F(vkGetBufferDeviceAddress) F(vkCreateShaderModule) F(vkDestroyShaderModule)
  F(vkCreateDescriptorSetLayout) F(vkDestroyDescriptorSetLayout) F(vkCreatePipelineLayout) F(vkDestroyPipelineLayout)
  F(vkCreateComputePipelines) F(vkDestroyPipeline) F(vkCreateDescriptorPool) F(vkDestroyDescriptorPool) F(vkAllocateDescriptorSets)
  F(vkUpdateDescriptorSets) F(vkCreateCommandPool) F(vkDestroyCommandPool) F(vkAllocateCommandBuffers) F(vkBeginCommandBuffer)
  F(vkEndCommandBuffer) F(vkCmdBindPipeline) F(vkCmdBindDescriptorSets) F(vkCmdPushConstants) F(vkCmdDispatch) F(vkCmdPipelineBarrier)
  F(vkCmdFillBuffer) F(vkQueueSubmit) F(vkQueueWaitIdle) F(vkCreateQueryPool) F(vkDestroyQueryPool) F(vkCmdResetQueryPool)
  F(vkCmdWriteTimestamp) F(vkGetQueryPoolResults) F(vkResetCommandBuffer)
  F(vkGetAccelerationStructureBuildSizesKHR) F(vkCreateAccelerationStructureKHR) F(vkDestroyAccelerationStructureKHR)
  F(vkCmdBuildAccelerationStructuresKHR) F(vkGetAccelerationStructureDeviceAddressKHR)
  F(vkGetClusterAccelerationStructureBuildSizesNV) F(vkCmdBuildClusterAccelerationStructureIndirectNV)
  F(vkGetPartitionedAccelerationStructuresBuildSizesNV) F(vkCmdBuildPartitionedAccelerationStructuresNV)
#undef F
};

struct Buf {
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkDeviceAddress address = 0;
  VkDeviceSize size = 0;
  void* map = nullptr;
};

struct Ctx {
  const Env& env;
  Fns f;
  std::vector<Buf> bufs;
  std::vector<VkAccelerationStructureKHR> ases;
  Ctx(const Env& e) : env(e) {}

  bool load() {
#define L(n) f.n = reinterpret_cast<PFN_##n>(env.getProc(#n)); if (!f.n) { env.log(std::string("selftest: missing ") + #n); return false; }
    L(vkCreateBuffer) L(vkDestroyBuffer) L(vkGetBufferMemoryRequirements) L(vkAllocateMemory) L(vkFreeMemory) L(vkBindBufferMemory)
    L(vkMapMemory) L(vkUnmapMemory) L(vkGetBufferDeviceAddress) L(vkCreateShaderModule) L(vkDestroyShaderModule)
    L(vkCreateDescriptorSetLayout) L(vkDestroyDescriptorSetLayout) L(vkCreatePipelineLayout) L(vkDestroyPipelineLayout)
    L(vkCreateComputePipelines) L(vkDestroyPipeline) L(vkCreateDescriptorPool) L(vkDestroyDescriptorPool) L(vkAllocateDescriptorSets)
    L(vkUpdateDescriptorSets) L(vkCreateCommandPool) L(vkDestroyCommandPool) L(vkAllocateCommandBuffers) L(vkBeginCommandBuffer)
    L(vkEndCommandBuffer) L(vkCmdBindPipeline) L(vkCmdBindDescriptorSets) L(vkCmdPushConstants) L(vkCmdDispatch) L(vkCmdPipelineBarrier)
    L(vkCmdFillBuffer) L(vkQueueSubmit) L(vkQueueWaitIdle) L(vkCreateQueryPool) L(vkDestroyQueryPool) L(vkCmdResetQueryPool)
    L(vkCmdWriteTimestamp) L(vkGetQueryPoolResults) L(vkResetCommandBuffer)
    L(vkGetAccelerationStructureBuildSizesKHR) L(vkCreateAccelerationStructureKHR) L(vkDestroyAccelerationStructureKHR)
    L(vkCmdBuildAccelerationStructuresKHR) L(vkGetAccelerationStructureDeviceAddressKHR)
    L(vkGetClusterAccelerationStructureBuildSizesNV) L(vkCmdBuildClusterAccelerationStructureIndirectNV)
#undef L
    // Partitioned acceleration structures are optional (separate extension)
    f.vkGetPartitionedAccelerationStructuresBuildSizesNV = reinterpret_cast<PFN_vkGetPartitionedAccelerationStructuresBuildSizesNV>(env.getProc("vkGetPartitionedAccelerationStructuresBuildSizesNV"));
    f.vkCmdBuildPartitionedAccelerationStructuresNV = reinterpret_cast<PFN_vkCmdBuildPartitionedAccelerationStructuresNV>(env.getProc("vkCmdBuildPartitionedAccelerationStructuresNV"));
    return true;
  }

  int memType(uint32_t bits, VkMemoryPropertyFlags want) const {
    for (uint32_t i = 0; i < env.memProps.memoryTypeCount; i++)
      if ((bits & (1u << i)) && (env.memProps.memoryTypes[i].propertyFlags & want) == want) return int(i);
    return -1;
  }

  Buf make(VkDeviceSize size, bool host) {
    size = (size + 255) & ~VkDeviceSize(255);
    if (size == 0) size = 256;
    Buf b; b.size = size;
    VkBufferCreateInfo ci = { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
    ci.size = size;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
               VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (f.vkCreateBuffer(env.device, &ci, nullptr, &b.buffer) != VK_SUCCESS) { env.log("selftest: vkCreateBuffer failed"); return b; }
    VkMemoryRequirements mr; f.vkGetBufferMemoryRequirements(env.device, b.buffer, &mr);
    int mt = host ? memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                  : memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateFlagsInfo fi = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO };
    fi.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    VkMemoryAllocateInfo ai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &fi, mr.size, uint32_t(mt) };
    if (mt < 0 || f.vkAllocateMemory(env.device, &ai, nullptr, &b.memory) != VK_SUCCESS) { env.log("selftest: vkAllocateMemory failed"); b.buffer = VK_NULL_HANDLE; return b; }
    f.vkBindBufferMemory(env.device, b.buffer, b.memory, 0);
    VkBufferDeviceAddressInfo da = { VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO };
    da.buffer = b.buffer;
    b.address = f.vkGetBufferDeviceAddress(env.device, &da);
    if (host) f.vkMapMemory(env.device, b.memory, 0, VK_WHOLE_SIZE, 0, &b.map);
    bufs.push_back(b);
    return b;
  }

  ~Ctx() {
    for (auto a : ases) f.vkDestroyAccelerationStructureKHR(env.device, a, nullptr);
    for (auto& b : bufs) { if (b.map) f.vkUnmapMemory(env.device, b.memory); f.vkDestroyBuffer(env.device, b.buffer, nullptr); f.vkFreeMemory(env.device, b.memory, nullptr); }
  }
};

struct Pipe {
  VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkDescriptorSet set = VK_NULL_HANDLE;
};

double msSince(std::chrono::steady_clock::time_point t) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

} // namespace

bool run(const Env& env) {
  auto& log = env.log;
  if (const char* g = std::getenv("REMIX_CLUSTER_AS_GRID")) { kGrid = std::clamp(std::atoi(g), 8, 128) & ~7; kClusters = kGrid * kGrid; kRays = kGrid * kQuadsPerSide; }
  Ctx c(env);
  if (!c.load()) return false;
  auto& f = c.f;
  VkDevice dev = env.device;

  // ---- limits
  VkPhysicalDeviceClusterAccelerationStructurePropertiesNV cp = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CLUSTER_ACCELERATION_STRUCTURE_PROPERTIES_NV };
  (void) cp;

  // ---- geometry: cluster-major vertex array (4 vertices per quad like Minecraft terrain), shared local 16-bit index pattern
  const uint32_t vertCount = kClusters * kVertsPerCluster;
  std::vector<float> verts(size_t(vertCount) * 3);
  auto hAt = [](int x, int z) { return terrainHeight(x, z); };
  for (uint32_t cz = 0; cz < kGrid; cz++) for (uint32_t cx = 0; cx < kGrid; cx++) {
    const uint32_t cid = cz * kGrid + cx;
    for (uint32_t qz = 0; qz < kQuadsPerSide; qz++) for (uint32_t qx = 0; qx < kQuadsPerSide; qx++) {
      const int x = int(cx * kQuadsPerSide + qx), z = int(cz * kQuadsPerSide + qz);
      const int dx[4] = {0, 1, 1, 0}, dz[4] = {0, 0, 1, 1};
      for (int k = 0; k < 4; k++) {
        float* v = &verts[(size_t(cid) * kVertsPerCluster + (qz * kQuadsPerSide + qx) * 4 + k) * 3];
        v[0] = float(x + dx[k]); v[1] = hAt(x + dx[k], z + dz[k]); v[2] = float(z + dz[k]);
      }
    }
  }
  std::vector<uint16_t> localIdx(kTrisPerCluster * 3);
  for (uint32_t q = 0; q < 64; q++) {
    const uint16_t b = uint16_t(q * 4);
    const uint16_t t[6] = {b, uint16_t(b + 1), uint16_t(b + 2), b, uint16_t(b + 2), uint16_t(b + 3)};
    std::memcpy(&localIdx[q * 6], t, sizeof(t));
  }
  std::vector<uint32_t> globalIdx(size_t(kClusters) * kTrisPerCluster * 3);
  for (uint32_t cid = 0; cid < kClusters; cid++) for (uint32_t i = 0; i < kTrisPerCluster * 3; i++) globalIdx[size_t(cid) * kTrisPerCluster * 3 + i] = cid * kVertsPerCluster + localIdx[i];

  Buf vbuf = c.make(verts.size() * 4, true);
  Buf lidx = c.make(localIdx.size() * 2, true);
  Buf gidx = c.make(globalIdx.size() * 4, true);
  if (!vbuf.buffer || !lidx.buffer || !gidx.buffer) return false;
  std::memcpy(vbuf.map, verts.data(), verts.size() * 4);
  std::memcpy(lidx.map, localIdx.data(), localIdx.size() * 2);
  std::memcpy(gidx.map, globalIdx.data(), globalIdx.size() * 4);

  // ---- command infrastructure
  VkCommandPoolCreateInfo cpi = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, env.queueFamily };
  VkCommandPool pool; f.vkCreateCommandPool(dev, &cpi, nullptr, &pool);
  VkCommandBufferAllocateInfo cbai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
  VkCommandBuffer cmd; f.vkAllocateCommandBuffers(dev, &cbai, &cmd);
  VkQueryPoolCreateInfo qpi = { VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, nullptr, 0, VK_QUERY_TYPE_TIMESTAMP, 16, 0 };
  VkQueryPool qp; f.vkCreateQueryPool(dev, &qpi, nullptr, &qp);

  auto begin = [&]() { f.vkResetCommandBuffer(cmd, 0); VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT }; f.vkBeginCommandBuffer(cmd, &bi); f.vkCmdResetQueryPool(cmd, qp, 0, 16); };
  auto submit = [&]() { f.vkEndCommandBuffer(cmd); VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO }; si.commandBufferCount = 1; si.pCommandBuffers = &cmd; f.vkQueueSubmit(env.queue, 1, &si, VK_NULL_HANDLE); f.vkQueueWaitIdle(env.queue); };
  auto barrier = [&]() {
    VkMemoryBarrier mb = { VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT };
    f.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
  };
  auto stamp = [&](uint32_t i) { f.vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, qp, i); };
  auto readStamps = [&](uint32_t n, std::vector<uint64_t>& out) { out.resize(n); f.vkGetQueryPoolResults(dev, qp, 0, n, n * 8, out.data(), 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT); };
  auto gpuMs = [&](const std::vector<uint64_t>& s, uint32_t a, uint32_t b) { return double(s[b] - s[a]) * env.timestampPeriodNs * 1e-6; };

  auto makePipe = [&](const unsigned char* spv, size_t spvSize, uint32_t bindings, VkDescriptorType type0, uint32_t pcSize) {
    Pipe p;
    std::vector<uint32_t> code((spvSize + 3) / 4);
    std::memcpy(code.data(), spv, spvSize);
    VkShaderModuleCreateInfo smi = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0, spvSize, code.data() };
    VkShaderModule sm; f.vkCreateShaderModule(dev, &smi, nullptr, &sm);
    std::vector<VkDescriptorSetLayoutBinding> b(bindings);
    for (uint32_t i = 0; i < bindings; i++) b[i] = { i, (i == 0) ? type0 : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    VkDescriptorSetLayoutCreateInfo dli = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, bindings, b.data() };
    f.vkCreateDescriptorSetLayout(dev, &dli, nullptr, &p.dsl);
    VkPushConstantRange pr = { VK_SHADER_STAGE_COMPUTE_BIT, 0, pcSize };
    VkPipelineLayoutCreateInfo pli = { VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &p.dsl, 1, &pr };
    f.vkCreatePipelineLayout(dev, &pli, nullptr, &p.layout);
    VkComputePipelineCreateInfo pi = { VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
    pi.stage = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, sm, "main", nullptr };
    pi.layout = p.layout;
    VkResult r = f.vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &pi, nullptr, &p.pipeline);
    f.vkDestroyShaderModule(dev, sm, nullptr);
    if (r != VK_SUCCESS) log("selftest: compute pipeline creation failed (" + std::to_string(r) + ")");
    return p;
  };
  VkDescriptorPoolSize dps[3] = { { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 32 }, { VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 4 }, { VK_DESCRIPTOR_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_NV, 4 } };
  VkDescriptorPoolCreateInfo dpi = { VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0, 4, env.hasPartitioned ? 3u : 2u, dps };
  VkDescriptorPool dpool; f.vkCreateDescriptorPool(dev, &dpi, nullptr, &dpool);

  Pipe cullP = makePipe(s17_cull_spv, sizeof(s17_cull_spv), 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 36);
  Pipe traceP = makePipe(s17_trace_spv, sizeof(s17_trace_spv), 3, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 24);
  Pipe tracePt = env.hasPartitioned ? makePipe(s17_trace_spv, sizeof(s17_trace_spv), 3, VK_DESCRIPTOR_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_NV, 24) : Pipe();
  if (!cullP.pipeline || !traceP.pipeline) return false;
  auto allocSet = [&](Pipe& p) { VkDescriptorSetAllocateInfo ai = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr, dpool, 1, &p.dsl }; f.vkAllocateDescriptorSets(dev, &ai, &p.set); };
  allocSet(cullP); allocSet(traceP); if (env.hasPartitioned) allocSet(tracePt);
  auto writeBuf = [&](VkDescriptorSet s, uint32_t binding, const Buf& b) {
    VkDescriptorBufferInfo bi = { b.buffer, 0, VK_WHOLE_SIZE };
    VkWriteDescriptorSet w = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, s, binding, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &bi, nullptr };
    f.vkUpdateDescriptorSets(dev, 1, &w, 0, nullptr);
  };

  // ---- 1. CLAS: one triangle cluster (128 tris, 256 verts) per terrain 8x8 block, all sharing one local index pattern
  Buf clasInfos = c.make(size_t(kClusters) * sizeof(VkClusterAccelerationStructureBuildTriangleClusterInfoNV), true);
  {
    auto* info = static_cast<VkClusterAccelerationStructureBuildTriangleClusterInfoNV*>(clasInfos.map);
    for (uint32_t cid = 0; cid < kClusters; cid++) {
      auto& i = info[cid];
      std::memset(&i, 0, sizeof(i));
      i.clusterID = cid;
      i.triangleCount = kTrisPerCluster;
      i.vertexCount = kVertsPerCluster;
      i.indexType = VK_CLUSTER_ACCELERATION_STRUCTURE_INDEX_FORMAT_16BIT_NV;
      i.baseGeometryIndexAndGeometryFlags.geometryIndex = 0x800000u | (cid * kTrisPerCluster);
      i.baseGeometryIndexAndGeometryFlags.geometryFlags = VK_CLUSTER_ACCELERATION_STRUCTURE_GEOMETRY_OPAQUE_BIT_NV;
      i.indexBufferStride = 2;
      i.vertexBufferStride = 12;
      i.indexBuffer = lidx.address;
      i.vertexBuffer = vbuf.address + VkDeviceAddress(cid) * kVertsPerCluster * 12;
    }
  }
  VkClusterAccelerationStructureTriangleClusterInputNV tci = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_TRIANGLE_CLUSTER_INPUT_NV };
  tci.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
  tci.maxGeometryIndexValue = 0x800000u | ((kClusters - 1) * kTrisPerCluster);
  tci.maxClusterUniqueGeometryCount = 1;
  tci.maxClusterTriangleCount = kTrisPerCluster;
  tci.maxClusterVertexCount = kVertsPerCluster;
  tci.maxTotalTriangleCount = kTrisPerCluster * kClusters;
  tci.maxTotalVertexCount = kVertsPerCluster * kClusters;
  VkClusterAccelerationStructureInputInfoNV clasIn = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_INPUT_INFO_NV };
  clasIn.maxAccelerationStructureCount = kClusters;
  clasIn.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
  clasIn.opType = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_TRIANGLE_CLUSTER_NV;
  clasIn.opMode = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_IMPLICIT_DESTINATIONS_NV;
  clasIn.opInput.pTriangleClusters = &tci;
  VkAccelerationStructureBuildSizesInfoKHR clasSz = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR };
  f.vkGetClusterAccelerationStructureBuildSizesNV(dev, &clasIn, &clasSz);
  log("selftest: CLAS sizes for " + std::to_string(kClusters) + " clusters: storage " + std::to_string(clasSz.accelerationStructureSize) + " B (" +
      std::to_string(clasSz.accelerationStructureSize / kClusters) + " B/cluster), scratch " + std::to_string(clasSz.buildScratchSize) + " B");
  Buf clasStore = c.make(clasSz.accelerationStructureSize, false);
  Buf clasScratch = c.make(clasSz.buildScratchSize, false);
  Buf clasAddrs = c.make(kClusters * 8, false);
  Buf counts = c.make(256, true);       // [0] = kClusters, [1] = 1
  if (!clasStore.buffer || !clasScratch.buffer || !clasAddrs.buffer || !counts.buffer) return false;
  static_cast<uint32_t*>(counts.map)[0] = kClusters;
  static_cast<uint32_t*>(counts.map)[1] = 1;
  VkClusterAccelerationStructureCommandsInfoNV clasCmd = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_COMMANDS_INFO_NV };
  clasCmd.input = clasIn;
  clasCmd.dstImplicitData = clasStore.address;
  clasCmd.scratchData = clasScratch.address;
  clasCmd.dstAddressesArray = { clasAddrs.address, 8, kClusters * 8 };
  clasCmd.srcInfosArray = { clasInfos.address, sizeof(VkClusterAccelerationStructureBuildTriangleClusterInfoNV), kClusters * sizeof(VkClusterAccelerationStructureBuildTriangleClusterInfoNV) };
  clasCmd.srcInfosCount = counts.address;
  begin(); stamp(0);
  f.vkCmdBuildClusterAccelerationStructureIndirectNV(cmd, &clasCmd);
  barrier(); stamp(1);
  submit();
  std::vector<uint64_t> ts; readStamps(2, ts);
  log("selftest: CLAS build of " + std::to_string(kClusters) + " clusters (" + std::to_string(kClusters * kTrisPerCluster) + " tris): GPU " + std::to_string(gpuMs(ts, 0, 1)) + " ms");

  // ---- 2. cluster BLAS from the surviving CLAS references (explicit destination, so the BLAS address is known up front)
  VkClusterAccelerationStructureClustersBottomLevelInputNV bli = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_CLUSTERS_BOTTOM_LEVEL_INPUT_NV };
  bli.maxTotalClusterCount = kClusters;
  bli.maxClusterCountPerAccelerationStructure = kClusters;
  VkClusterAccelerationStructureInputInfoNV blasIn = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_INPUT_INFO_NV };
  blasIn.maxAccelerationStructureCount = 1;
  blasIn.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
  blasIn.opType = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_CLUSTERS_BOTTOM_LEVEL_NV;
  blasIn.opMode = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_EXPLICIT_DESTINATIONS_NV;
  blasIn.opInput.pClustersBottomLevel = &bli;
  VkAccelerationStructureBuildSizesInfoKHR blasSz = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR };
  f.vkGetClusterAccelerationStructureBuildSizesNV(dev, &blasIn, &blasSz);
  log("selftest: cluster BLAS sizes (max " + std::to_string(kClusters) + " clusters): storage " + std::to_string(blasSz.accelerationStructureSize) + " B, scratch " + std::to_string(blasSz.buildScratchSize) + " B");
  Buf blasStore = c.make(blasSz.accelerationStructureSize, false);
  Buf blasScratch = c.make(blasSz.buildScratchSize, false);
  Buf blasDst = c.make(256, true);
  Buf refs = c.make(kClusters * 8, false);
  Buf counter = c.make(256, true);
  Buf blasSrc = c.make(256, false);
  if (!blasStore.buffer || !blasScratch.buffer || !refs.buffer || !blasSrc.buffer) return false;
  static_cast<uint64_t*>(blasDst.map)[0] = blasStore.address;
  VkClusterAccelerationStructureCommandsInfoNV blasCmd = { VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_COMMANDS_INFO_NV };
  blasCmd.input = blasIn;
  blasCmd.scratchData = blasScratch.address;
  blasCmd.dstAddressesArray = { blasDst.address, 8, 8 };
  blasCmd.srcInfosArray = { blasSrc.address, sizeof(VkClusterAccelerationStructureBuildClustersBottomLevelInfoNV), sizeof(VkClusterAccelerationStructureBuildClustersBottomLevelInfoNV) };
  blasCmd.srcInfosCount = counts.address + 4;

  // ---- 3. TLAS over the cluster BLAS (conventional KHR TLAS: today's engine builds it from CPU-written instances)
  Buf inst = c.make(sizeof(VkAccelerationStructureInstanceKHR) * 2, true);
  auto setInstance = [&](uint32_t slot, VkDeviceAddress blas) {
    auto* in = static_cast<VkAccelerationStructureInstanceKHR*>(inst.map) + slot;
    std::memset(in, 0, sizeof(*in));
    in->transform.matrix[0][0] = in->transform.matrix[1][1] = in->transform.matrix[2][2] = 1.0f;
    in->mask = 0xFF;
    in->accelerationStructureReference = blas;
  };
  struct Tlas { VkAccelerationStructureKHR as = VK_NULL_HANDLE; VkDeviceAddress addr = 0; Buf store, scratch; };
  auto makeTlas = [&](Tlas& t, uint32_t slot) {
    VkAccelerationStructureGeometryKHR g = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR };
    g.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
    g.geometry.instances = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR, nullptr, VK_FALSE, { inst.address + slot * sizeof(VkAccelerationStructureInstanceKHR) } };
    VkAccelerationStructureBuildGeometryInfoKHR bgi = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR };
    bgi.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    bgi.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
    bgi.geometryCount = 1; bgi.pGeometries = &g;
    uint32_t one = 1;
    VkAccelerationStructureBuildSizesInfoKHR sz = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR };
    f.vkGetAccelerationStructureBuildSizesKHR(dev, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &bgi, &one, &sz);
    t.store = c.make(sz.accelerationStructureSize, false);
    t.scratch = c.make(sz.buildScratchSize, false);
    VkAccelerationStructureCreateInfoKHR ci = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR };
    ci.buffer = t.store.buffer; ci.size = sz.accelerationStructureSize; ci.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    f.vkCreateAccelerationStructureKHR(dev, &ci, nullptr, &t.as);
    c.ases.push_back(t.as);
    VkAccelerationStructureDeviceAddressInfoKHR dai = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR, nullptr, t.as };
    t.addr = f.vkGetAccelerationStructureDeviceAddressKHR(dev, &dai);
  };
  auto recordTlasBuild = [&](Tlas& t, uint32_t slot) {
    VkAccelerationStructureGeometryKHR g = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR };
    g.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
    g.geometry.instances = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR, nullptr, VK_FALSE, { inst.address + slot * sizeof(VkAccelerationStructureInstanceKHR) } };
    VkAccelerationStructureBuildGeometryInfoKHR bgi = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR };
    bgi.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    bgi.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
    bgi.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    bgi.dstAccelerationStructure = t.as;
    bgi.geometryCount = 1; bgi.pGeometries = &g;
    bgi.scratchData.deviceAddress = t.scratch.address;
    VkAccelerationStructureBuildRangeInfoKHR range = { 1, 0, 0, 0 };
    const VkAccelerationStructureBuildRangeInfoKHR* pr = &range;
    f.vkCmdBuildAccelerationStructuresKHR(cmd, 1, &bgi, &pr);
  };
  Tlas tlasCluster; setInstance(0, blasStore.address); makeTlas(tlasCluster, 0);

  // ---- 4. baseline: one conventional KHR BLAS over the same triangles (single geometry, 32-bit indices)
  VkAccelerationStructureGeometryKHR bg = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR };
  bg.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR;
  bg.flags = VK_GEOMETRY_OPAQUE_BIT_KHR;
  bg.geometry.triangles = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR, nullptr, VK_FORMAT_R32G32B32_SFLOAT, { vbuf.address }, 12, vertCount - 1, VK_INDEX_TYPE_UINT32, { gidx.address }, { 0 } };
  VkAccelerationStructureBuildGeometryInfoKHR bb = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR };
  bb.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
  bb.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
  bb.geometryCount = 1; bb.pGeometries = &bg;
  uint32_t baseTris = kClusters * kTrisPerCluster;
  VkAccelerationStructureBuildSizesInfoKHR bsz = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR };
  f.vkGetAccelerationStructureBuildSizesKHR(dev, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &bb, &baseTris, &bsz);
  Buf baseStore = c.make(bsz.accelerationStructureSize, false), baseScratch = c.make(bsz.buildScratchSize, false);
  VkAccelerationStructureKHR baseAs;
  { VkAccelerationStructureCreateInfoKHR ci = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR }; ci.buffer = baseStore.buffer; ci.size = bsz.accelerationStructureSize; ci.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR; f.vkCreateAccelerationStructureKHR(dev, &ci, nullptr, &baseAs); c.ases.push_back(baseAs); }
  VkAccelerationStructureDeviceAddressInfoKHR bda = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR, nullptr, baseAs };
  VkDeviceAddress baseAddr = f.vkGetAccelerationStructureDeviceAddressKHR(dev, &bda);
  setInstance(1, baseAddr);
  Tlas tlasBase; makeTlas(tlasBase, 1);
  log("selftest: baseline KHR BLAS (1 geometry, " + std::to_string(baseTris) + " tris): storage " + std::to_string(bsz.accelerationStructureSize) + " B, cluster BLAS (all clusters) " + std::to_string(blasSz.accelerationStructureSize) + " B max; CLAS storage " + std::to_string(clasSz.accelerationStructureSize) + " B");

  // ---- descriptors
  writeBuf(cullP.set, 0, clasAddrs); writeBuf(cullP.set, 1, refs); writeBuf(cullP.set, 2, counter); writeBuf(cullP.set, 3, blasSrc);
  Buf hits = c.make(kRays * kRays * 16, true), tbuf = c.make(kRays * kRays * 4, true);
  writeBuf(traceP.set, 1, hits); writeBuf(traceP.set, 2, tbuf);
  auto bindTlas = [&](const Tlas& t) {
    VkWriteDescriptorSetAccelerationStructureKHR as = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR, nullptr, 1, &t.as };
    VkWriteDescriptorSet w = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, &as, traceP.set, 0, 0, 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, nullptr, nullptr, nullptr };
    f.vkUpdateDescriptorSets(dev, 1, &w, 0, nullptr);
  };

  struct CullPC { float camX, camZ, radius; uint32_t clusterCount, gridDim; float clusterSize; uint32_t pass; uint32_t refsLo, refsHi; };
  auto recordCull = [&](float camX, float camZ, float radius) {
    f.vkCmdFillBuffer(cmd, counter.buffer, 0, 4, 0);
    barrier();
    f.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cullP.pipeline);
    f.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cullP.layout, 0, 1, &cullP.set, 0, nullptr);
    CullPC pc = { camX, camZ, radius, kClusters, kGrid, float(kQuadsPerSide), 0, uint32_t(refs.address), uint32_t(refs.address >> 32) };
    f.vkCmdPushConstants(cmd, cullP.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    f.vkCmdDispatch(cmd, (kClusters + 63) / 64, 1, 1);
    barrier();
    pc.pass = 1;
    f.vkCmdPushConstants(cmd, cullP.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    f.vkCmdDispatch(cmd, 1, 1, 1);
    barrier();
  };
  auto recordTrace = [&](const Tlas& t) {
    bindTlas(t);   // descriptor update between submissions only (all work idle)
    f.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, traceP.pipeline);
    f.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, traceP.layout, 0, 1, &traceP.set, 0, nullptr);
    struct { uint32_t w, h; float step, oy, ox, oz; } pc = { kRays, kRays, 1.0f, kOriginY, 0.0f, 0.0f };
    f.vkCmdPushConstants(cmd, traceP.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    f.vkCmdDispatch(cmd, kRays / 8, kRays / 8, 1);
    barrier();
  };
  auto expected = [&](uint32_t px, uint32_t pz, float camX, float camZ, float radius, bool& hit, uint32_t& prim, uint32_t& geom, uint32_t& cid, float& t) {
    const float x = px + 0.37f, z = pz + 0.61f;
    const uint32_t cx = uint32_t(x) / kQuadsPerSide, cz = uint32_t(z) / kQuadsPerSide;
    cid = cz * kGrid + cx; geom = 0x800000u | (cid * kTrisPerCluster);
    const float ccx = (cx + 0.5f) * kQuadsPerSide, ccz = (cz + 0.5f) * kQuadsPerSide;
    hit = ((ccx - camX) * (ccx - camX) + (ccz - camZ) * (ccz - camZ)) <= radius * radius;
    const int qx = int(x), qz = int(z); const float fx = x - qx, fz = z - qz;
    const float h00 = hAt(qx, qz), h10 = hAt(qx + 1, qz), h11 = hAt(qx + 1, qz + 1), h01 = hAt(qx, qz + 1);
    const bool tri2 = fx < fz;
    prim = 2 * ((qz % kQuadsPerSide) * kQuadsPerSide + (qx % kQuadsPerSide)) + (tri2 ? 1 : 0);
    const float h = tri2 ? h00 + (h11 - h01) * fx + (h01 - h00) * fz : h00 + (h10 - h00) * fx + (h11 - h10) * fz;
    t = kOriginY - h;
  };
  auto verify = [&](const char* name, float camX, float camZ, float radius, bool checkIds) {
    const auto* H = static_cast<const uint32_t*>(hits.map); const auto* T = static_cast<const float*>(tbuf.map);
    uint32_t expHits = 0, gotHits = 0, mism = 0, idMism = 0; double maxDt = 0;
    for (uint32_t z = 0; z < kRays; z++) for (uint32_t x = 0; x < kRays; x++) {
      bool eh; uint32_t ep, eg, ec; float et; expected(x, z, camX, camZ, radius, eh, ep, eg, ec, et);
      const uint32_t* h = H + (size_t(z) * kRays + x) * 4; const float t = T[size_t(z) * kRays + x];
      expHits += eh; gotHits += h[0];
      if (bool(h[0]) != eh) { mism++; continue; }
      if (eh) {
        maxDt = std::max(maxDt, double(std::fabs(t - et)));
        if (std::fabs(t - et) > 8e-3f) mism++;
        if (checkIds && (h[1] != ep || h[2] != eg || h[3] != ec)) idMism++;
      }
    }
    log(std::string("selftest: trace[") + name + "] rays " + std::to_string(kRays * kRays) + ": expected hits " + std::to_string(expHits) + ", got " + std::to_string(gotHits) +
        ", hit/t mismatches " + std::to_string(mism) + " (max |dt| " + std::to_string(maxDt) + "), primitive/geometry/clusterID mismatches " + std::to_string(idMism) + (checkIds ? "" : " (not checked)"));
    return mism == 0 && idMism == 0;
  };

  bool ok = true;
  struct Cam { float x, z, r; };
  const Cam cams[] = { { 256, 256, 1e6f }, { 100, 120, 90 }, { 400, 300, 60 }, { 0, 0, 20 } };
  double cullBuildMs[4] = {};
  for (int i = 0; i < 4; i++) {
    const Cam& cm = cams[i];
    begin(); stamp(0);
    recordCull(cm.x, cm.z, cm.r); stamp(1);
    f.vkCmdBuildClusterAccelerationStructureIndirectNV(cmd, &blasCmd);
    barrier(); stamp(2);
    recordTlasBuild(tlasCluster, 0);
    barrier(); stamp(3);
    submit();
    readStamps(4, ts);
    cullBuildMs[i] = gpuMs(ts, 0, 2);
    const uint32_t kept = *static_cast<uint32_t*>(counter.map);
    begin(); stamp(0); recordTrace(tlasCluster); stamp(1); submit(); readStamps(2, ts);
    log("selftest: view " + std::to_string(i) + " cam(" + std::to_string(cm.x) + "," + std::to_string(cm.z) + ") radius " + std::to_string(cm.r) + ": kept " + std::to_string(kept) + "/" + std::to_string(kClusters) +
        " clusters; GPU cull+compact+BLAS-from-CLAS " + std::to_string(gpuMs(ts, 0, 0) + cullBuildMs[i]) + " ms, trace " + std::to_string(gpuMs(ts, 0, 1)) + " ms");
    ok &= verify(("cluster view " + std::to_string(i)).c_str(), cm.x, cm.z, cm.r, true);
  }

  // baseline: conventional BLAS (GPU build time, trace time, same picture)
  {
    begin(); stamp(0);
    bb.mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR; bb.dstAccelerationStructure = baseAs; bb.scratchData.deviceAddress = baseScratch.address;
    VkAccelerationStructureBuildRangeInfoKHR range = { baseTris, 0, 0, 0 }; const VkAccelerationStructureBuildRangeInfoKHR* pr = &range;
    f.vkCmdBuildAccelerationStructuresKHR(cmd, 1, &bb, &pr);
    barrier(); stamp(1);
    recordTlasBuild(tlasBase, 1);
    barrier(); stamp(2);
    submit(); readStamps(3, ts);
    log("selftest: baseline KHR BLAS build (" + std::to_string(baseTris) + " tris) GPU " + std::to_string(gpuMs(ts, 0, 1)) + " ms, TLAS " + std::to_string(gpuMs(ts, 1, 2)) + " ms");
    begin(); stamp(0); recordTrace(tlasBase); stamp(1); submit(); readStamps(2, ts);
    log("selftest: baseline trace " + std::to_string(gpuMs(ts, 0, 1)) + " ms");
    ok &= verify("baseline KHR BLAS (full terrain)", 256, 256, 1e6f, false);
  }

  // steady-state cost of the GPU-driven path: N frames of cull + BLAS-from-CLAS + TLAS, all recorded with a handful of commands (no per-cluster CPU work)
  {
    const int N = 200;
    auto t0 = std::chrono::steady_clock::now();
    double cpuRecord = 0;
    double gpuTotal = 0;
    for (int i = 0; i < N; i++) {
      auto r0 = std::chrono::steady_clock::now();
      begin(); stamp(0);
      recordCull(256 + 100 * std::sin(i * 0.05f), 256 + 100 * std::cos(i * 0.05f), 150);
      f.vkCmdBuildClusterAccelerationStructureIndirectNV(cmd, &blasCmd); barrier();
      recordTlasBuild(tlasCluster, 0); stamp(1);
      f.vkEndCommandBuffer(cmd);
      cpuRecord += msSince(r0);
      VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO }; si.commandBufferCount = 1; si.pCommandBuffers = &cmd; f.vkQueueSubmit(env.queue, 1, &si, VK_NULL_HANDLE); f.vkQueueWaitIdle(env.queue);
      readStamps(2, ts); gpuTotal += gpuMs(ts, 0, 1);
    }
    log("selftest: " + std::to_string(N) + " GPU-driven frames (cull + BLAS + TLAS, radius 150): " + std::to_string(msSince(t0) / N) + " ms wall/frame (incl. queue idle wait), CPU record " +
        std::to_string(cpuRecord / N) + " ms/frame, GPU " + std::to_string(gpuTotal / N) + " ms/frame; CPU work independent of the " + std::to_string(kClusters) + " clusters");
    // conventional rebuild each frame for comparison
    double cpuRecordB = 0, gpuB = 0;
    for (int i = 0; i < N; i++) {
      auto r0 = std::chrono::steady_clock::now();
      begin(); stamp(0);
      VkAccelerationStructureBuildRangeInfoKHR range = { baseTris, 0, 0, 0 }; const VkAccelerationStructureBuildRangeInfoKHR* pr = &range;
      f.vkCmdBuildAccelerationStructuresKHR(cmd, 1, &bb, &pr); barrier();
      recordTlasBuild(tlasBase, 1); stamp(1);
      f.vkEndCommandBuffer(cmd);
      cpuRecordB += msSince(r0);
      VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO }; si.commandBufferCount = 1; si.pCommandBuffers = &cmd; f.vkQueueSubmit(env.queue, 1, &si, VK_NULL_HANDLE); f.vkQueueWaitIdle(env.queue);
      readStamps(2, ts); gpuB += gpuMs(ts, 0, 1);
    }
    log("selftest: " + std::to_string(N) + " conventional full-terrain BLAS+TLAS rebuilds: CPU record " + std::to_string(cpuRecordB / N) + " ms/frame, GPU " + std::to_string(gpuB / N) + " ms/frame");
  }


  // ---- 5. partitioned TLAS (PTLAS): instances are written/updated by GPU commands instead of a CPU-built instance array
  if (env.hasPartitioned && f.vkCmdBuildPartitionedAccelerationStructuresNV && tracePt.pipeline) {
    // restore the full cluster BLAS (the steady-state loop above left a culled one in place)
    begin(); recordCull(256, 256, 1e6f); f.vkCmdBuildClusterAccelerationStructureIndirectNV(cmd, &blasCmd); barrier(); submit();
    const float spacing = float(kRays) + 88.0f;
    const uint32_t kInst = 4096, kPart = 64;   // 4096 instances of the cluster BLAS in 64 partitions of 64
    VkPartitionedAccelerationStructureFlagsNV pflags = { VK_STRUCTURE_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_FLAGS_NV };
    pflags.enablePartitionTranslation = VK_TRUE;
    VkPartitionedAccelerationStructureInstancesInputNV pin = { VK_STRUCTURE_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_INSTANCES_INPUT_NV };
    pin.pNext = &pflags;
    pin.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
    pin.instanceCount = kInst; pin.maxInstancePerPartitionCount = kInst / kPart; pin.partitionCount = kPart; pin.maxInstanceInGlobalPartitionCount = 0;
    VkAccelerationStructureBuildSizesInfoKHR psz = { VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR };
    f.vkGetPartitionedAccelerationStructuresBuildSizesNV(dev, &pin, &psz);
    log("selftest: PTLAS sizes for " + std::to_string(kInst) + " instances in " + std::to_string(kPart) + " partitions: storage " + std::to_string(psz.accelerationStructureSize) + " B, scratch " + std::to_string(psz.buildScratchSize) + " B");
    Buf pstore = c.make(psz.accelerationStructureSize, false), pscratch = c.make(psz.buildScratchSize, false);
    Buf wdata = c.make(kInst * sizeof(VkPartitionedAccelerationStructureWriteInstanceDataNV), true);
    Buf udata = c.make(kInst * sizeof(VkPartitionedAccelerationStructureUpdateInstanceDataNV), true);
    Buf tdata = c.make(kPart * sizeof(VkPartitionedAccelerationStructureWritePartitionTranslationDataNV), true);
    Buf cmds = c.make(4 * sizeof(VkBuildPartitionedAccelerationStructureIndirectCommandNV), true);
    Buf pcount = c.make(256, true);
    if (!pstore.buffer || !pscratch.buffer || !wdata.buffer) return false;
    auto* W = static_cast<VkPartitionedAccelerationStructureWriteInstanceDataNV*>(wdata.map);
    for (uint32_t i = 0; i < kInst; i++) {
      auto& w = W[i]; std::memset(&w, 0, sizeof(w));
      w.transform.matrix[0][0] = w.transform.matrix[1][1] = w.transform.matrix[2][2] = 1.0f;
      w.transform.matrix[0][3] = spacing * float(i % 64); w.transform.matrix[2][3] = spacing * float(i / 64);
      w.instanceID = i; w.instanceMask = 0xFF; w.instanceIndex = i; w.partitionIndex = i / (kInst / kPart);
      w.accelerationStructure = blasStore.address;
    }
    auto* T = static_cast<VkPartitionedAccelerationStructureWritePartitionTranslationDataNV*>(tdata.map);
    for (uint32_t p = 0; p < kPart; p++) { T[p].partitionIndex = p; T[p].partitionTranslation[0] = T[p].partitionTranslation[1] = T[p].partitionTranslation[2] = 0.0f; }
    T[3].partitionTranslation[1] = 5000.0f;   // lift partition 3 (instances 192..255) by 5000 units without touching its instances
    auto* cm = static_cast<VkBuildPartitionedAccelerationStructureIndirectCommandNV*>(cmds.map);
    cm[0] = { VK_PARTITIONED_ACCELERATION_STRUCTURE_OP_TYPE_WRITE_INSTANCE_NV, kInst, { wdata.address, sizeof(VkPartitionedAccelerationStructureWriteInstanceDataNV) } };
    cm[1] = { VK_PARTITIONED_ACCELERATION_STRUCTURE_OP_TYPE_WRITE_PARTITION_TRANSLATION_NV, kPart, { tdata.address, sizeof(VkPartitionedAccelerationStructureWritePartitionTranslationDataNV) } };
    static_cast<uint32_t*>(pcount.map)[0] = 2;   // commands in the first build
    VkBuildPartitionedAccelerationStructureInfoNV pb = { VK_STRUCTURE_TYPE_BUILD_PARTITIONED_ACCELERATION_STRUCTURE_INFO_NV };
    pb.input = pin; pb.dstAccelerationStructureData = pstore.address; pb.scratchData = pscratch.address; pb.srcInfos = cmds.address; pb.srcInfosCount = pcount.address;
    begin(); stamp(0);
    f.vkCmdBuildPartitionedAccelerationStructuresNV(cmd, &pb); barrier(); stamp(1);
    submit(); readStamps(2, ts);
    log("selftest: PTLAS initial build (write " + std::to_string(kInst) + " instances + " + std::to_string(kPart) + " partition translations): GPU " + std::to_string(gpuMs(ts, 0, 1)) + " ms");
    auto traceBlock = [&](float ox, float oz, uint32_t instIndexForRef, bool wantHit, const char* name, bool& okOut, bool ids = true) {
      (void) instIndexForRef;
      VkDeviceAddress a = pstore.address;
      VkWriteDescriptorSetPartitionedAccelerationStructureNV pw = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_PARTITIONED_ACCELERATION_STRUCTURE_NV, nullptr, 1, &a };
      VkWriteDescriptorSet w = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, &pw, tracePt.set, 0, 0, 1, VK_DESCRIPTOR_TYPE_PARTITIONED_ACCELERATION_STRUCTURE_NV, nullptr, nullptr, nullptr };
      f.vkUpdateDescriptorSets(dev, 1, &w, 0, nullptr);
      writeBuf(tracePt.set, 1, hits); writeBuf(tracePt.set, 2, tbuf);
      begin(); stamp(0);
      f.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tracePt.pipeline);
      f.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tracePt.layout, 0, 1, &tracePt.set, 0, nullptr);
      struct { uint32_t w, h; float step, oy, ox, oz; } pc = { kRays, kRays, 1.0f, kOriginY, ox, oz };
      f.vkCmdPushConstants(cmd, tracePt.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
      f.vkCmdDispatch(cmd, kRays / 8, kRays / 8, 1);
      barrier(); stamp(1); submit(); readStamps(2, ts);
      if (wantHit) okOut &= verify(name, 256, 256, 1e6f, ids);
      else {
        const auto* H = static_cast<const uint32_t*>(hits.map); uint32_t n = 0; for (uint32_t i = 0; i < kRays * kRays; i++) n += H[i * 4];
        log(std::string("selftest: trace[") + name + "]: " + std::to_string(n) + " hits (expected 0)"); okOut &= (n == 0);
      }
    };
    traceBlock(0, 0, 0, true, "PTLAS instance 0", ok);
    traceBlock(spacing * 5, spacing * 1, 69, true, "PTLAS instance 69 (translated by its instance transform)", ok);
    traceBlock(spacing * 63, spacing * 63, 4095, true, "PTLAS instance 4095", ok);
    traceBlock(-2000.0f, -2000.0f, 0, false, "PTLAS outside every instance (must miss)", ok);
    // Partition 3 holds instances 192..255 = row 3, columns 0..63: lifted by 5000 on Y, so the ray origin (Y=100) is below the terrain: expect misses from above? rays go -Y from 100, geometry at y>4900: no hit.
    traceBlock(spacing * 7, spacing * 3, 199, false, "PTLAS partition 3 translated +5000 Y (ray origin below it, must miss)", ok);
    // Update: retarget instance 69 to the conventional baseline BLAS (same terrain) with an UPDATE_INSTANCE command; picture must be unchanged.
    auto* U = static_cast<VkPartitionedAccelerationStructureUpdateInstanceDataNV*>(udata.map);
    U[0] = { 69, 0, baseAddr };
    cm[0] = { VK_PARTITIONED_ACCELERATION_STRUCTURE_OP_TYPE_UPDATE_INSTANCE_NV, 1, { udata.address, sizeof(VkPartitionedAccelerationStructureUpdateInstanceDataNV) } };
    static_cast<uint32_t*>(pcount.map)[0] = 1;
    pb.srcAccelerationStructureData = pstore.address;   // update in place
    pb.dstAccelerationStructureData = pstore.address;
    begin(); stamp(0); f.vkCmdBuildPartitionedAccelerationStructuresNV(cmd, &pb); barrier(); stamp(1); submit(); readStamps(2, ts);
    log("selftest: PTLAS UPDATE_INSTANCE of 1 instance: GPU " + std::to_string(gpuMs(ts, 0, 1)) + " ms");
    traceBlock(spacing * 5, spacing * 1, 69, true, "PTLAS instance 69 after UPDATE_INSTANCE to the KHR BLAS", ok, false);
    // GPU time of updating many instances (all 4096 retargeted) and of a 1-instance update, averaged
    for (uint32_t i = 0; i < kInst; i++) { auto* u = static_cast<VkPartitionedAccelerationStructureUpdateInstanceDataNV*>(udata.map) + i; *u = { i, 0, (i & 1) ? baseAddr : blasStore.address }; }
    cm[0] = { VK_PARTITIONED_ACCELERATION_STRUCTURE_OP_TYPE_UPDATE_INSTANCE_NV, kInst, { udata.address, sizeof(VkPartitionedAccelerationStructureUpdateInstanceDataNV) } };
    double msAll = 0; const int N = 50;
    for (int i = 0; i < N; i++) { begin(); stamp(0); f.vkCmdBuildPartitionedAccelerationStructuresNV(cmd, &pb); barrier(); stamp(1); submit(); readStamps(2, ts); msAll += gpuMs(ts, 0, 1); }
    log("selftest: PTLAS UPDATE_INSTANCE of all " + std::to_string(kInst) + " instances: GPU " + std::to_string(msAll / N) + " ms avg over " + std::to_string(N) + " (recorded with 1 CPU command)");
  } else {
    log("selftest: PTLAS not tested (extension not enabled)");
  }

  f.vkDestroyQueryPool(dev, qp, nullptr);
  f.vkDestroyDescriptorPool(dev, dpool, nullptr);
  for (Pipe* p : { &cullP, &traceP, &tracePt }) { if (!p->pipeline) continue; f.vkDestroyPipeline(dev, p->pipeline, nullptr); f.vkDestroyPipelineLayout(dev, p->layout, nullptr); f.vkDestroyDescriptorSetLayout(dev, p->dsl, nullptr); }
  f.vkDestroyCommandPool(dev, pool, nullptr);
  log(std::string("selftest: RESULT ") + (ok ? "PASS" : "FAIL"));
  return ok;
}

}
