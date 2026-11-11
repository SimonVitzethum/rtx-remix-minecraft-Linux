# Experimental retained cluster BLAS

`REMIX_CLUSTER_AS=1` requests `VK_NV_cluster_acceleration_structure` at device
creation. The default remains the ordinary KHR renderer. Unsupported devices,
formats, material states and exhausted budgets use that renderer. This is an
experimental implementation until matched game validation is recorded below.
There is no PTLAS, camera culling, user installation or API version change.

## Architecture

The builder participates in AccelManager's existing unique dynamic-BLAS loop.
Only retained external meshes with static opaque instances, no skinning,
particles, subsurface scattering, instancing arrays or OMM attachments qualify.
Minecraft's retained terrain pages use this loop already. No routing heuristic is
changed and an unchanged cached KHR BLAS is never converted merely to enable the
experiment. The scene-generation skip and ordinary merged/dynamic caches remain
in place, including their unchanged-content behavior.

A GPU compute pass gathers indexed float32 positions into bounded triangle-soup
clusters of 64 triangles (192 vertices). Index order, winding and position bits
are preserved; no quantization, sorting or deduplication takes place. The final
cluster may be partial. Empty and oversized meshes and unknown formats fall
back. Both uint16 and uint32 source indices are supported. This initial layout
trades a bounded temporary position buffer for deterministic, readback-free
partitioning of arbitrary retained topology. Optimizing vertex reuse needs a
separate measured justification.

One cluster BLAS per existing mesh is referenced by the ordinary TLAS instance.
The original instance transforms, masks, SBT records, material and surface
buffers remain in use. All clusters remain available for primary, shadow,
reflection and indirect rays. No frustum compaction is applied.

### Hit identity

GeometryIndex bit 23 tags a cluster hit, and bits 0–22 carry its first original
primitive. The ray-hit construction helpers restore the original primitive by
adding the local PrimitiveIndex and reset GeometryIndex to zero. The instance's
21-bit surface index and material/view-model flags remain unchanged. Ordinary
merged geometry indices are below this tag because their surfaces are bounded
by the existing 21-bit surface namespace. TraceRay uses an SBT geometry stride
of zero. The runtime requires the tagged index to be strictly below the queried
maxClusterGeometryIndex. Shaders use only ordinary KHR hit instructions; NVIDIA
cluster shader capabilities are not added to fallback-device shaders.

The Vulkan CLAS contract permits application-defined per-cluster geometry
indices: <https://docs.vulkan.org/spec/latest/chapters/accelstructures.html#acceleration-structure-cluster>
The exact build input contract is documented at
<https://docs.vulkan.org/refpages/latest/refpages/source/VkClusterAccelerationStructureBuildTriangleClusterInfoNV.html>.

### Cache and lifetime

A cached hierarchy is reused while the geometry update generation, format,
stride, counts and geometry flags match. Mutations allocate a new hierarchy.
Incompatible instances retire the complete hierarchy and rebuild through KHR.
Cluster BLAS allocations never enter the KHR update/reuse selection. Allocation
happens before commands are recorded, so an allocation failure can fall back.

The device-local budget is 256 MiB of rounded reservations, including both
persistent CLAS/BLAS and transient gather/descriptors/address/scratch buffers.
Reservations remain charged through command-list retirement, including scene
clears and replacement. The ordinary previous-TLAS retention also retains the
CLAS hierarchy. Alignments come from the cluster device properties. Explicit
transfer/compute/build barriers separate gather, CLAS build, BLAS build and TLAS
use. There is no per-frame GPU-to-host readback or new per-frame scratch build
for cached geometry. Diagnostics include live CLAS, cluster BLAS and ordinary opaque-TLAS instance
counts. Build and cache counters are cumulative CPU-side record counts,
not GPU completion or performance measurements.

## Validation status

Implementation and validation are in progress. Historical S17 grid/harness
results are not evidence for this integration and do not establish a game
performance benefit. Match the complete runtime and Java package hashes when
comparing game frames. The meaningful baseline is the existing retained KHR
cache, not repeated unconditional BLAS builds.
