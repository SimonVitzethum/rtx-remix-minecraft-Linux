/* SPDX-License-Identifier: MIT */
#pragma once

#include <atomic>
#include <memory>
#include <vector>

#include "../dxvk_buffer.h"
#include "rtx_cluster_plan.h"

namespace dxvk {
  class DxvkContext;
  class DxvkBarrierSet;
  struct BlasEntry;
  class RtInstance;

  bool clusterAccelerationStructureRequested();

  // The reservation survives its owner until the last command list releases it.
  class ClusterBlasResources : public DxvkResource {
  public:
    ClusterBlasResources(std::shared_ptr<std::atomic<uint64_t>> budget, uint64_t bytes)
      : m_budget(std::move(budget)), m_bytes(bytes) { m_budget->fetch_add(bytes); }
    ~ClusterBlasResources() { buffers.clear(); m_budget->fetch_sub(m_bytes); }

    std::vector<Rc<DxvkBuffer>> buffers;
    uint32_t frameLastUpdated = 0;
    uint32_t clusterCount = 0;
    uint64_t signature = 0;

  private:
    std::shared_ptr<std::atomic<uint64_t>> m_budget;
    uint64_t m_bytes;
  };

  class RtxClusterBlasBuilder {
  public:
    bool build(const Rc<DxvkContext>& ctx, DxvkBarrierSet& barriers,
               BlasEntry& entry, const std::vector<RtInstance*>& instances);
    void report(uint32_t frame);

  private:
    std::shared_ptr<std::atomic<uint64_t>> m_budget = std::make_shared<std::atomic<uint64_t>>(0);
    uint64_t m_builds = 0;
    uint64_t m_hits = 0;
    uint64_t m_fallbacks = 0;
    uint64_t m_clusters = 0;
    uint64_t m_ineligible = 0;
    uint64_t m_unsupported = 0;
  };
}
