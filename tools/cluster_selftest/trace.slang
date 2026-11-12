#define CUSTOM_INDEX_SURFACE_MASK 0x1fffffu
#define CUSTOM_INDEX_MATERIAL_TYPE_BIT 21
static const uint surfaceMaterialTypeMask = 3u;
// The identity regression does not consume packed barycentrics.
uint barycentricsToUint(float2 value) { return 0; }
#include "rtx/concept/ray/ray_helper.slangh"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outHits;  // x = hit, y = primitiveIndex, z = geometryIndex, w = clusterID
[[vk::binding(2, 0)]] RWStructuredBuffer<float> outT;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint4> outIdentity;
struct PC { uint width; uint height; float step; float originY; float originX; float originZ; };
[[vk::push_constant]] ConstantBuffer<PC> pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
  if (id.x >= pc.width || id.y >= pc.height) return;
  RayDesc ray;
  ray.Origin = float3(pc.originX + id.x * pc.step + 0.37f, pc.originY, pc.originZ + id.y * pc.step + 0.61f);
  ray.Direction = float3(0, -1, 0);
  ray.TMin = 0.0f;
  ray.TMax = 1000.0f;
  RayQuery<RAY_FLAG_NONE> q;
  q.TraceRayInline(tlas, RAY_FLAG_NONE, 0xFF, ray);
  q.Proceed();
  uint idx = id.y * pc.width + id.x;
  if (q.CommittedStatus() == COMMITTED_TRIANGLE_HIT)
  {
    RAY_HIT_INFO_CREATE_RAY_QUERY_COMMITTED(hit, q)
    outIdentity[idx] = uint4(hit.customIndex, calculateSurfaceIndex(hit), calculateMaterialType(hit), hit.customIndex >> 23);
    outHits[idx] = uint4(1, hit.primitiveIndex, hit.geometryIndex, uint(q.CommittedClusterID()));
    outT[idx] = q.CommittedRayT();
  }
  else
  {
    outHits[idx] = uint4(0, 0, 0, 0);
    outT[idx] = -1.0f;
  }
}
