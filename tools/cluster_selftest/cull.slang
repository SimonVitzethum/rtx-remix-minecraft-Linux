// GPU-side cluster culling + compaction. Keeps the clusters whose centre is within `radius` of the camera (XZ), appends their CLAS
// addresses to `outRefs` and counts them. Pass 2 (one thread) writes the BLAS build source info {count, stride, refs address}.
[[vk::binding(0, 0)]] RWStructuredBuffer<uint64_t> clasAddrs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint64_t> outRefs;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> counter;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint4> blasSrcInfo;   // {clusterReferencesCount, clusterReferencesStride, refsAddr lo, hi}
struct PC { float camX; float camZ; float radius; uint clusterCount; uint gridDim; float clusterSize; uint pass; uint refsLo; uint refsHi; };
[[vk::push_constant]] ConstantBuffer<PC> pc;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
  if (pc.pass == 1) {
    if (id.x == 0) blasSrcInfo[0] = uint4(counter[0], 8, pc.refsLo, pc.refsHi);
    return;
  }
  if (id.x >= pc.clusterCount) return;
  uint cx = id.x % pc.gridDim, cz = id.x / pc.gridDim;
  float2 c = float2((cx + 0.5f) * pc.clusterSize, (cz + 0.5f) * pc.clusterSize);
  float2 d = c - float2(pc.camX, pc.camZ);
  if (dot(d, d) <= pc.radius * pc.radius) {
    uint slot;
    InterlockedAdd(counter[0], 1, slot);
    outRefs[slot] = clasAddrs[id.x];
  }
}
