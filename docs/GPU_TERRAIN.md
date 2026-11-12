# GPU construction of near terrain quads

This is an experimental native Linux Vulkan path. CPU compilation and unit
checks alone are not rendering evidence. The implementation must pass the
standalone device gate and the combined Minecraft/runtime integration before
it is described as validated for a game workload.

Minecraft 26.3 still evaluates BakedModel and Fabric custom meshes, selects
materials and sprites, applies tint and overlay policy, and supplies quad
corners. `CompactQuadCapture` classifies solid and cutout quads before
`DirectQuadWriter` constructs expanded vertices. Exactly representable affine
quads become 96-byte descriptors. The capture keeps no parallel expanded
vertex mesh for those quads. The Remix device generates four 64-byte vertices
and six canonical indices per descriptor, directly in device-local storage
used by the existing RasterGeometry and KHR BLAS path. Eligible CLAS users
receive the same generated buffers through their existing eligibility checks.

A descriptor contains the origin, two edges, the original normal, BGRA tint,
packed UNORM16 sprite bounds, and all four arbitrary float2 UV corners. The
origin includes section Y in column-local coordinates. The encoder checks the
ordered binary32 reconstruction bit for bit, including the section offset.
Nonfinite, subnormal, negative-zero and non-affine positions use ordinary
capture. Normals and UV corners retain their raw bits. Shader additions are
`precise`; raw loads/stores preserve the other attributes and zero every
reserved vertex word. Triangle winding matches existing terrain capture:
`0,2,1,0,3,2`.

Fluids, blended surfaces and overlays retain their existing representation.
Entities keep their existing capture. Stairs, fences and vegetation are not
replaced with cubes: individual affine faces may use descriptors, while every
nonrepresentable quad keeps its original shape. The distant greedy CPU LOD
remains available. Its explicit descriptor expansion is counted separately;
it must not be included in a claim of zero CPU expansion for near geometry.
`Chunk.GpuQuadDescriptors`, `Chunk.CpuQuadFallbacks` and
`Chunk.CpuLodExpandedQuads` expose this boundary in the ordinary diagnostics.

After native initialization, the Vulkan renderer enables compact capture.
OpenGL retains ordinary capture. `-Drtxremix.terrain.gpuQuads=false` selects the
ordinary representation for a controlled comparison and requires recapture,
just like other capture-changing settings.

## Device and ownership contract

API 0.16.3 adds `MESH_INFO_COMPACT_QUADS_EXT` (32) on `MeshInfo.pNext`, version 1.
The original 36 interface pointers and existing structure layouts remain
unchanged. Triangle and compact surfaces can coexist in one mesh. JNI accepts
direct little-endian buffers at position zero and validates byte ranges before
entering the runtime. The runtime copies descriptors while CreateMesh is
active. No Minecraft Vulkan handle, FD or device address crosses this API.

All descriptor staging, generated buffers and indirect control storage are
admitted synchronously through `RtxGpuWorkBudget` before success. Pipeline
creation also precedes success. A temporary admission denial returns
`RESOURCE_BUSY` (14); it creates no mesh and the Java installer retains the
pending input and old published column. It retries in a later admission frame.
Invalid layouts return `INVALID_ARGUMENTS`. Pipeline and allocation faults are
nonretry failures. The memory ledger belongs to the shared native allocator;
this feature introduces no separate byte cap or AS admission policy.

Queued construction owns its descriptor copy and resources. The command list
retains them through its fence, including cancelled or replaced work. The
published geometry retains output buffers; normal mesh and BLAS lifetime
rules retain them as long as any consumer needs them. Replacements therefore
charge old and new allocations together. There is no geometry readback and
re-upload in this production path.

## Task/mesh and compute generation

The runtime queries and explicitly enables `VK_EXT_mesh_shader`, task and
mesh features, and `vertexPipelineStoresAndAtomics`. Limits are checked for
32-thread workgroups, 16 quads per meshlet, 64 vertices, 32 triangles, payload
and output storage, dispatch dimensions and storage buffer ranges. Unsupported
hardware logs compute generation. `REMIX_GPU_QUAD_MODE=mesh` requires the EXT
path and reports an explicit failure if it cannot run;
`REMIX_GPU_QUAD_MODE=compute` selects the shared-decoder compute comparison.

A compute pass builds indirect task counts. Task shaders select valid
meshlets; mesh shaders emit on-chip primitives and store the persistent RT
vertices and indices. Production construction uses rasterizer discard because
Remix path tracing consumes the persistent buffers through acceleration
structures. The standalone gate additionally enables rasterization and checks
actual output pixels. On-chip mesh output alone is not BLAS input.

Camera culling only affects optional raster primitives. Persistent stores are
independent of that culling, preserving geometry for shadows and reflections.
The first implementation does not add a new GPU LOD simplifier. It preserves
the existing distant LOD and measures the construction boundary independently.

`DxvkContext.recordExternalCommands` spills the active render pass, records
pending execution barriers and invalidates compute, graphics and ray pipeline
bindings after raw recording. Construction explicitly orders host descriptor
writes, compute indirect writes, task/mesh storage writes, AS input reads and
ray/compute reads. It does not force a queue submission, flush small buffers
or wait for the device to become idle.

`REMIX_GPU_QUAD_DIAGNOSTICS=1` creates actual task/mesh pipeline-statistics and
GPU timestamp queries where supported. Results and shared allocator peaks are
logged as `GPU_QUAD_RESULT` after command-list retirement. Ready flags are
reported; absent query support is not represented as a successful measurement.
This option adds diagnostic work and should be measured separately from the
ordinary frame-time baseline.

## Validation

`tools/gpu_geometry_selftest` in the runtime builds the same production shader
sources. Its CPU-only startup check initializes no Vulkan object. The bounded
GPU gate checks empty input and meshlet boundaries, exact vertex attributes,
index winding, guard regions, GPU-produced indirect counts, hardware task/mesh
invocation queries, a real offscreen raster image, and KHR ray hits using the
persistent generated buffers. Rays include offscreen geometry and two
instances with distinct custom IDs and opaque/nonopaque flags. Readback in
that program is validation only.

A passing device gate is not a Minecraft visual regression. Combined game
validation still needs block changes, world switches, resource reloads, pack
materials, fluids and custom models, UVs/normals, shadow/reflection parity,
Khronos synchronization checks on both devices, and matched frame-time and
memory measurements. Report AS build, construction, waits and descriptor
upload separately. Frame generation FPS is a separate measurement from base
render FPS; no performance improvement is assumed from the shader choice.

The design follows the distinction between programmable meshlet generation
and persistent geometry in the [Khronos EXT proposal](https://docs.vulkan.org/features/latest/features/proposals/VK_EXT_mesh_shader.html).
The [NVIDIA Asteroids discussion](https://developer.nvidia.com/blog/using-turing-mesh-shaders-nvidia-asteroids-demo/)
and [professional graphics guidance](https://developer.nvidia.com/blog/using-mesh-shaders-for-professional-graphics/)
motivate small workgroups and explicit measurement. Their raster performance
results are not predictions for Remix path tracing.
