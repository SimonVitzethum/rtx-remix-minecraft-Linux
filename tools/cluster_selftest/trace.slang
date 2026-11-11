[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outHits;  // x = hit, y = primitiveIndex, z = geometryIndex, w = clusterID
[[vk::binding(2, 0)]] RWStructuredBuffer<float> outT;
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
    outHits[idx] = uint4(1, q.CommittedPrimitiveIndex(), q.CommittedGeometryIndex(), uint(q.CommittedClusterID()));
    outT[idx] = q.CommittedRayT();
  }
  else
  {
    outHits[idx] = uint4(0, 0, 0, 0);
    outT[idx] = -1.0f;
  }
}
