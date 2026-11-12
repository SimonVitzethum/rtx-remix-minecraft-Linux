/* SPDX-License-Identifier: MIT */
#include <cstdio>
#include <algorithm>
#include <initializer_list>
#include <limits>
#include "../../../src/dxvk/rtx_render/rtx_cluster_plan.h"

int main() {
  using namespace dxvk;
  auto plan = [](uint32_t triangles) { return ClusterPlan::make(triangles, 256, 256, 1u << 24); };
  for (uint32_t triangles : {1u, 63u, 64u, 65u, 127u, 128u, 65535u, 65536u}) {
    const auto p = plan(triangles);
    if (!p.clusters) { return 1; }
    uint32_t seen = 0;
    for (uint32_t cluster = 0; cluster < p.clusters; ++cluster) {
      const uint32_t first = cluster * kClusterTriangles;
      const uint32_t count = std::min(kClusterTriangles, triangles - first);
      for (uint32_t local = 0; local < count; ++local) {
        if (first + local != seen++) { return 2; }
      }
    }
    if (seen != triangles) { return 3; }
  }
  for (uint32_t invalid : {0u, 65537u, std::numeric_limits<uint32_t>::max()}) {
    if (plan(invalid).clusters) { return 4; }
  }
  if (ClusterPlan::make(64, 63, 256, 1u << 24).clusters ||
      ClusterPlan::make(64, 256, 191, 1u << 24).clusters ||
      ClusterPlan::make(65, 256, 256, kClusterGeometryTag + 64).clusters ||
      !ClusterPlan::make(65, 256, 256, kClusterGeometryTag + 65).clusters ||
      ClusterPlan::make(64, 0, 0, 0).clusters) { return 5; }
  std::puts("cluster planner PASS: coverage, partial clusters, capability/geometry-index bounds, empty and overflow");
}
