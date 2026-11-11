/* SPDX-License-Identifier: MIT */
#pragma once

#include <cstdint>

namespace dxvk {
  // Encoded in GeometryIndex, leaving the 24-bit instance custom index unchanged.
  constexpr uint32_t kClusterGeometryTag = 1u << 23;
  constexpr uint32_t kClusterTriangles = 64;

  struct ClusterPlan {
    uint32_t triangles = 0;
    uint32_t clusters = 0;

    static ClusterPlan make(uint32_t triangles, uint32_t maxTriangles,
                            uint32_t maxVertices, uint32_t maxGeometryIndex) {
      // A bounded triangle soup avoids CPU reads and topology-dependent partitions.
      if (!triangles || triangles > 65536 || maxTriangles < kClusterTriangles ||
          maxVertices < 3 * kClusterTriangles ||
          (kClusterGeometryTag | ((triangles - 1) / kClusterTriangles * kClusterTriangles)) >= maxGeometryIndex) {
        return {};
      }
      return {triangles, (triangles + kClusterTriangles - 1) / kClusterTriangles};
    }
  };
}
