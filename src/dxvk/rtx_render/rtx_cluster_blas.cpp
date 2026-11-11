/* SPDX-License-Identifier: MIT */
#include <algorithm>
#include <array>
#include <cstdlib>

#include "rtx_cluster_blas.h"
#include "rtx_types.h"
#include "rtx_instance_manager.h"
#include "rtx_shader_manager.h"
#include "dxvk_context.h"
#include "dxvk_device.h"
#include "dxvk_scoped_annotation.h"
#include "rtx/pass/cluster_pack.h"

#include <rtx_shaders/cluster_pack.h>

namespace dxvk {
  namespace {
    constexpr uint64_t kBudget = 256ull << 20;

    class ClusterPackShader : public ManagedShader {
      SHADER_SOURCE(ClusterPackShader, VK_SHADER_STAGE_COMPUTE_BIT, cluster_pack)
      PUSH_CONSTANTS(ClusterPackArgs)
      BEGIN_PARAMETER()
        STRUCTURED_BUFFER(0)
        STRUCTURED_BUFFER(1)
        RW_STRUCTURED_BUFFER(2)
      END_PARAMETER()
    };
  }

  bool clusterAccelerationStructureRequested() {
    const auto value = std::getenv("REMIX_CLUSTER_AS");
    return value && std::string(value) == "1";
  }

  bool RtxClusterBlasBuilder::build(const Rc<DxvkContext>& ctx, DxvkBarrierSet& barriers,
                                  BlasEntry& entry, const std::vector<RtInstance*>& instances) {
    const auto& device = ctx->getDevice();
    if (!device->extensions().nvClusterAccelerationStructure) {
      return false;
    }
    ScopedCpuProfileZone();
    const auto old = entry.dynamicBlas;
    const auto& geo = entry.modifiedGeometryData;
    bool eligible = entry.input.isExternalDraw && entry.input.getSkinningState().numBones == 0 &&
      entry.buildGeometries.size() == 1 && entry.buildRanges.size() == 1;
    for (const auto instance : instances) {
      eligible &= instance->surface.isStatic && !instance->isAnimated() && instance->isOpaque() &&
        !instance->usesUnorderedApproximations() && !instance->isSubsurface() &&
        instance->surface.instancesToObject == nullptr && instance->getBillboardCount() == 0 &&
        (instance->getGeometryFlags() & VK_GEOMETRY_OPAQUE_BIT_KHR);
    }
    if (!eligible) {
      ++m_ineligible;
      return false;
    }
    const auto& geometry = entry.buildGeometries[0];
    const auto& tri = geometry.geometry.triangles;
    const auto& range = entry.buildRanges[0];
    const auto& properties = device->properties().nvClusterAccelerationStructureProperties;
    const auto plan = ClusterPlan::make(range.primitiveCount, properties.maxTrianglesPerCluster,
                                       properties.maxVerticesPerCluster, properties.maxClusterGeometryIndex);
    // Unknown formats and attached extensions continue through the ordinary KHR path.
    if (!plan.clusters || tri.pNext || geometry.pNext || geometry.geometryType != VK_GEOMETRY_TYPE_TRIANGLES_KHR ||
        tri.vertexFormat != VK_FORMAT_R32G32B32_SFLOAT ||
        (tri.indexType != VK_INDEX_TYPE_UINT16 && tri.indexType != VK_INDEX_TYPE_UINT32) ||
        range.firstVertex || range.primitiveOffset || range.transformOffset || !geo.vertexCount ||
        tri.vertexStride < 12 || tri.vertexStride % 4 || tri.vertexStride > UINT32_MAX) {
      ++m_unsupported;
      return false;
    }
    const auto& positions = geo.positionBuffer.buffer();
    const auto& indices = geo.indexBuffer.buffer();
    const uint32_t indexStride = tri.indexType == VK_INDEX_TYPE_UINT16 ? 2 : 4;
    const auto vertexOffset = tri.vertexData.deviceAddress - positions->getDeviceAddress();
    const auto indexOffset = tri.indexData.deviceAddress - indices->getDeviceAddress();
    if (vertexOffset > UINT32_MAX || indexOffset > UINT32_MAX || vertexOffset % 4 || indexOffset % indexStride ||
        vertexOffset + uint64_t(geo.vertexCount - 1) * tri.vertexStride + 12 > positions->info().size ||
        align(indexOffset + uint64_t(plan.triangles) * 3 * indexStride, VkDeviceSize(4)) > indices->info().size ||
        positions->info().size > std::min(uint64_t(UINT32_MAX), uint64_t(device->properties().core.properties.limits.maxStorageBufferRange)) ||
        indices->info().size > std::min(uint64_t(UINT32_MAX), uint64_t(device->properties().core.properties.limits.maxStorageBufferRange)) ||
        !(positions->info().usage & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !(indices->info().usage & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
      ++m_unsupported;
      return false;
    }
    const std::array<uint64_t, 7> key = {entry.frameLastUpdated, tri.vertexStride, geo.vertexCount,
      tri.indexType, range.primitiveCount, geometry.flags, uint64_t(tri.vertexFormat)};
    const uint64_t signature = XXH3_64bits(key.data(), sizeof(key));
    if (old.ptr() && old->clusters.ptr() && old->clusters->signature == signature) {
      old->frameLastTouched = device->getCurrentFrameId();
      ++m_hits;
      return true;
    }
    // Keep ordinary cached BLAS unchanged. Conversion is only useful when a build is already due.
    if (old.ptr() && !old->clusters.ptr() && entry.frameLastUpdated != device->getCurrentFrameId()) {
      return false;
    }

    const auto vkd = device->vkd();
    if (!vkd->vkGetClusterAccelerationStructureBuildSizesNV || !vkd->vkCmdBuildClusterAccelerationStructureIndirectNV) {
      return false;
    }
    VkClusterAccelerationStructureTriangleClusterInputNV triangleInput {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_TRIANGLE_CLUSTER_INPUT_NV};
    triangleInput.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
    triangleInput.maxGeometryIndexValue = kClusterGeometryTag | ((plan.clusters - 1) * kClusterTriangles);
    triangleInput.maxClusterUniqueGeometryCount = 1;
    triangleInput.maxClusterTriangleCount = kClusterTriangles;
    triangleInput.maxClusterVertexCount = kClusterTriangles * 3;
    triangleInput.maxTotalTriangleCount = plan.triangles;
    triangleInput.maxTotalVertexCount = plan.triangles * 3;
    VkClusterAccelerationStructureInputInfoNV clusterInput {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_INPUT_INFO_NV};
    clusterInput.maxAccelerationStructureCount = plan.clusters;
    clusterInput.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
    clusterInput.opType = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_TRIANGLE_CLUSTER_NV;
    clusterInput.opMode = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_IMPLICIT_DESTINATIONS_NV;
    clusterInput.opInput.pTriangleClusters = &triangleInput;
    VkAccelerationStructureBuildSizesInfoKHR clusterSize {VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};
    vkd->vkGetClusterAccelerationStructureBuildSizesNV(device->handle(), &clusterInput, &clusterSize);

    VkClusterAccelerationStructureClustersBottomLevelInputNV bottomInput {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_CLUSTERS_BOTTOM_LEVEL_INPUT_NV};
    bottomInput.maxTotalClusterCount = plan.clusters;
    bottomInput.maxClusterCountPerAccelerationStructure = plan.clusters;
    VkClusterAccelerationStructureInputInfoNV bottomInfo {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_INPUT_INFO_NV};
    bottomInfo.maxAccelerationStructureCount = 1;
    bottomInfo.flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
    bottomInfo.opType = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_TYPE_BUILD_CLUSTERS_BOTTOM_LEVEL_NV;
    bottomInfo.opMode = VK_CLUSTER_ACCELERATION_STRUCTURE_OP_MODE_EXPLICIT_DESTINATIONS_NV;
    bottomInfo.opInput.pClustersBottomLevel = &bottomInput;
    VkAccelerationStructureBuildSizesInfoKHR bottomSize {VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};
    vkd->vkGetClusterAccelerationStructureBuildSizesNV(device->handle(), &bottomInfo, &bottomSize);

    // Reserve persistent and transient storage together, including in-flight retired allocations.
    // Round each allocation up to cover buffer alignment and account for the small descriptor buffers.
    const uint64_t persistentBytes = align(uint64_t(clusterSize.accelerationStructureSize), uint64_t(4096)) + align(uint64_t(bottomSize.accelerationStructureSize), uint64_t(4096));
    const uint64_t transientBytes = align(uint64_t(plan.triangles) * 36, uint64_t(4096)) +
      align(std::max(clusterSize.buildScratchSize, bottomSize.buildScratchSize), VkDeviceSize(4096)) +
      align(uint64_t(plan.clusters) * (sizeof(VkClusterAccelerationStructureBuildTriangleClusterInfoNV) + 8), uint64_t(4096)) + 4 * 4096;
    if (!clusterSize.accelerationStructureSize || !bottomSize.accelerationStructureSize ||
        persistentBytes + transientBytes > kBudget - std::min(kBudget, m_budget->load())) {
      ++m_fallbacks;
      return false;
    }
    Rc<ClusterBlasResources> retained = new ClusterBlasResources(m_budget, persistentBytes);
    Rc<ClusterBlasResources> temporary = new ClusterBlasResources(m_budget, transientBytes);
    Rc<PooledBlas> result = new PooledBlas();
    Rc<DxvkBuffer> store, packed, scratch, metadata, addresses;
    auto allocate = [&](uint64_t size, uint64_t alignment, const char* name) {
      DxvkBufferCreateInfo info {};
      info.size = std::max(uint64_t(4), size);
      info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
      info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
      info.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
      info.requiredAlignmentOverride = alignment;
      return device->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, DxvkMemoryStats::Category::RTXAccelerationStructure, name);
    };
    try {
      store = allocate(clusterSize.accelerationStructureSize, properties.clusterByteAlignment, "Terrain CLAS");
      packed = allocate(uint64_t(plan.triangles) * 36, 4, "Cluster triangle positions");
      scratch = allocate(std::max(clusterSize.buildScratchSize, bottomSize.buildScratchSize), properties.clusterScratchByteAlignment, "Cluster scratch");
      metadata = allocate(uint64_t(plan.clusters) * sizeof(VkClusterAccelerationStructureBuildTriangleClusterInfoNV) + 1024, 8, "Cluster build descriptors");
      addresses = allocate(uint64_t(plan.clusters) * 8, 8, "CLAS addresses");
      DxvkBufferCreateInfo info {};
      info.size = bottomSize.accelerationStructureSize;
      info.usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
      info.stages = VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
      info.access = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
      info.requiredAlignmentOverride = properties.clusterBottomLevelByteAlignment;
      result->accelStructure = device->createAccelStructure(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, "Cluster BLAS");
    } catch (const DxvkError& error) {
      ONCE(Logger::warn(str::format("Cluster AS allocation failed, using KHR: ", error.message())));
      ++m_fallbacks;
      return false;
    }
    retained->buffers = {store, result->accelStructure};
    temporary->buffers = {packed, scratch, metadata, addresses};
    retained->signature = signature;
    retained->frameLastUpdated = entry.frameLastUpdated;
    retained->clusterCount = plan.clusters;
    result->clusters = retained;
    result->frameLastTouched = device->getCurrentFrameId();
    // NV cluster BLAS references are buffer addresses, not VkAccelerationStructureKHR addresses.
    result->accelerationStructureReference = result->accelStructure->getDeviceAddress();

    std::vector<VkClusterAccelerationStructureBuildTriangleClusterInfoNV> descriptors(plan.clusters);
    const uint64_t controlOffset = uint64_t(plan.clusters) * sizeof(descriptors[0]);
    const uint64_t localIndexOffset = controlOffset + 64;
    for (uint32_t i = 0; i < plan.clusters; ++i) {
      auto& desc = descriptors[i];
      const uint32_t first = i * kClusterTriangles;
      desc.clusterID = first;
      desc.triangleCount = std::min(kClusterTriangles, plan.triangles - first);
      desc.vertexCount = desc.triangleCount * 3;
      desc.indexType = VK_CLUSTER_ACCELERATION_STRUCTURE_INDEX_FORMAT_16BIT_NV;
      desc.opacityMicromapIndexType = VK_CLUSTER_ACCELERATION_STRUCTURE_INDEX_FORMAT_16BIT_NV;
      desc.baseGeometryIndexAndGeometryFlags.geometryIndex = kClusterGeometryTag | first;
      desc.baseGeometryIndexAndGeometryFlags.geometryFlags = VK_CLUSTER_ACCELERATION_STRUCTURE_GEOMETRY_OPAQUE_BIT_NV;
      desc.indexBuffer = metadata->getDeviceAddress() + localIndexOffset;
      desc.vertexBuffer = packed->getDeviceAddress() + uint64_t(first) * 36;
      desc.vertexBufferStride = 12;
      desc.indexBufferStride = 2;
    }
    std::array<uint16_t, 3 * kClusterTriangles> localIndices;
    for (uint32_t i = 0; i < localIndices.size(); ++i) { localIndices[i] = i; }
    const std::array<uint64_t, 2> control = {uint64_t(plan.clusters) | (1ull << 32), result->accelerationStructureReference};
    VkClusterAccelerationStructureBuildClustersBottomLevelInfoNV bottomDesc {};
    bottomDesc.clusterReferencesCount = plan.clusters;
    bottomDesc.clusterReferencesStride = 8;
    bottomDesc.clusterReferences = addresses->getDeviceAddress();

    ScopedGpuProfileZone(ctx, "Terrain cluster AS build");
    barriers.recordCommands(ctx->getCommandList());
    ctx->writeToBuffer(metadata, 0, controlOffset, descriptors.data());
    ctx->writeToBuffer(metadata, controlOffset, sizeof(control), control.data());
    ctx->writeToBuffer(metadata, controlOffset + 16, sizeof(bottomDesc), &bottomDesc);
    ctx->writeToBuffer(metadata, localIndexOffset, sizeof(localIndices), localIndices.data());
    ctx->emitMemoryBarrier(0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    ctx->bindResourceBuffer(0, DxvkBufferSlice(positions));
    ctx->bindResourceBuffer(1, DxvkBufferSlice(indices));
    ctx->bindResourceBuffer(2, DxvkBufferSlice(packed));
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, ClusterPackShader::getShader());
    const ClusterPackArgs args {plan.triangles * 3, geo.vertexCount, uint32_t(tri.vertexStride), indexStride, uint32_t(vertexOffset), uint32_t(indexOffset)};
    ctx->pushConstants(0, sizeof(args), &args);
    ctx->dispatch((args.indexCount + 63) / 64, 1, 1);
    auto buildBarrier = [&] {
      ctx->emitMemoryBarrier(0,
        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
    };
    buildBarrier();
    VkClusterAccelerationStructureCommandsInfoNV command {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_COMMANDS_INFO_NV};
    command.input = clusterInput;
    command.dstImplicitData = store->getDeviceAddress();
    command.scratchData = scratch->getDeviceAddress();
    command.dstAddressesArray = {addresses->getDeviceAddress(), 8, uint64_t(plan.clusters) * 8};
    command.srcInfosArray = {metadata->getDeviceAddress(), sizeof(descriptors[0]), controlOffset};
    command.srcInfosCount = metadata->getDeviceAddress() + controlOffset;
    const auto buffer = ctx->getCommandList()->getCmdBuffer(DxvkCmdBuffer::ExecBuffer);
    vkd->vkCmdBuildClusterAccelerationStructureIndirectNV(buffer, &command);
    buildBarrier();
    command = {VK_STRUCTURE_TYPE_CLUSTER_ACCELERATION_STRUCTURE_COMMANDS_INFO_NV};
    command.input = bottomInfo;
    command.scratchData = scratch->getDeviceAddress();
    command.dstAddressesArray = {metadata->getDeviceAddress() + controlOffset + 8, 8, 8};
    command.srcInfosArray = {metadata->getDeviceAddress() + controlOffset + 16, sizeof(bottomDesc), sizeof(bottomDesc)};
    command.srcInfosCount = metadata->getDeviceAddress() + controlOffset + 4;
    vkd->vkCmdBuildClusterAccelerationStructureIndirectNV(buffer, &command);
    buildBarrier();
    ctx->getCommandList()->trackResource<DxvkAccess::Write>(temporary);
    ctx->getCommandList()->trackResource<DxvkAccess::Write>(retained);
    ctx->getCommandList()->trackResource<DxvkAccess::Read>(positions);
    ctx->getCommandList()->trackResource<DxvkAccess::Read>(indices);
    entry.dynamicBlas = result;
    ++m_builds;
    m_clusters += plan.clusters;
    return true;
  }

  void RtxClusterBlasBuilder::report(uint32_t frame) {
    if (clusterAccelerationStructureRequested() && frame % 120 == 0) {
      Logger::info(str::format("Cluster AS frame=", frame, " builds=", m_builds, " cacheHits=", m_hits,
        " CLAS=", m_clusters, " budgetFallbacks=", m_fallbacks, " ineligible=", m_ineligible, " unsupported=", m_unsupported, " liveReservedBytes=", m_budget->load()));
    }
  }
}
