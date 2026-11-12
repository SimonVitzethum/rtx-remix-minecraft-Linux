/* SPDX-License-Identifier: MIT */
#pragma once
#include "rtx/utility/cpu_gpu_compat.h"

#ifdef __cplusplus
namespace dxvk {
#endif

struct ClusterPackArgs {
  uint32_t indexCount;
  uint32_t vertexCount;
  uint32_t vertexStride;
  uint32_t indexStride;
  uint32_t vertexOffset;
  uint32_t indexOffset;
};

#ifdef __cplusplus
}
#endif

#include "rtx/utility/cpu_gpu_compat_undef.h"
