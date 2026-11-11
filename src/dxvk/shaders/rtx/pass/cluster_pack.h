/* SPDX-License-Identifier: MIT */
#pragma once
#include "rtx/utility/cpu_gpu_compat.h"

struct ClusterPackArgs {
  uint indexCount;
  uint vertexCount;
  uint vertexStride;
  uint indexStride;
  uint vertexOffset;
  uint indexOffset;
};

#include "rtx/utility/cpu_gpu_compat_undef.h"
