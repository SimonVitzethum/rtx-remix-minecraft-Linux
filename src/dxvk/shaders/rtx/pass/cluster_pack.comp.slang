/* SPDX-License-Identifier: MIT */
#include "cluster_pack.h"

[[vk::binding(0)]] ByteAddressBuffer positions;
[[vk::binding(1)]] ByteAddressBuffer indices;
[[vk::binding(2)]] RWByteAddressBuffer packedPositions;
[[vk::push_constant]] ConstantBuffer<ClusterPackArgs> args;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint id : SV_DispatchThreadID)
{
  if (id >= args.indexCount)
  {
    return;
  }
  uint address = args.indexOffset + id * args.indexStride;
  uint index = indices.Load(address & ~3u);
  if (args.indexStride == 2)
  {
    index = (index >> ((address & 2u) * 8)) & 0xffffu;
  }
  // Reject malformed source indices without accessing outside the vertex buffer.
  uint3 position = uint3(0x7fc00000u);
  if (index < args.vertexCount)
  {
    position = positions.Load3(args.vertexOffset + index * args.vertexStride);
  }
  packedPositions.Store3(id * 12, position);
}
