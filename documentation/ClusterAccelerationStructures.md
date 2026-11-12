# Experimental retained cluster BLAS

`REMIX_CLUSTER_AS=1` requests `VK_NV_cluster_acceleration_structure` at device
creation. The default remains the ordinary KHR renderer. Unsupported devices,
formats, material states and exhausted budgets use that renderer. This is an
experimental implementation. Matched game validation is recorded below, with no
measured performance advantage for the tested workload.
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

## Validation status — 2026-11-12

The serial Release link and expanded GPU regression are complete. The renderer
uses live CLAS in a real game, with a matched 600-warmup/600-record-frame pair.
The tested workload shows **no performance advantage**, so the feature remains
an experimental opt-in. It must not be enabled by default or advertised as a
measured game speedup.

### Exact tested artifacts

The renderer source is `47ab12010f8d7e0754e4164e91e914ff350f6a17`, based on
`b07f55fac673e9a32d2eb53e96597ae13f616311`, including the `f3f569d` ELF COMDAT
fix and API 0.16.2. Main diagnostic fix `9579309` is present as `3f5dd79` and
main inotify fix `2bc9ba1` as `47ab120`. The later process-test and documentation
commits do not change the tested renderer sources.

- Raw runtime SHA256: `ed8e004b9f8f42f29b57edba63a55aba29fc6e92fa399acb4a8ac5a6239cd897`.
- Staged and actually loaded runtime SHA256: `e03c04cb3d39e8b3f172c7a18d3ac4c2034cceab9f686f9a38b9652cd3556ec2`.
- Private JAR SHA256: `6a2eb1a820a176f23778a753a78545764459f4547c868fdb83b4f68ce4628d4f`.
- JNI SHA256: `ad40f114652e080efee598953e43111e5356c1de9ac40cff687a28b0df7d196e`.
- Java base: `d11513cfcf284517b31aa24fbd6e1b3affbb01ae`, original JAR SHA256
  `ebbfe437bbb9a10029c5106ac098b8a6d5ab55b1f240756bb33d800cbe7c03b4`.
  The main owner's 859 passing JVM tests/package verification are reused for
  that exact base. They are not reported as a new JVM test run by this task.

The package lives under `../port-artifacts/scratch/cluster-next/stage/`. Its
embedded `META-INF/cluster-validation.json` and `stage-status.json` distinguish
raw, staged, loaded and Java-base provenance. Staging refuses an unsuccessful
link, a different exact HEAD or changed raw runtime bytes. Rebuilding/restaging
is required before running those scripts against a subsequent HEAD.

### Correctness checks

- The resumed `-O3`, `ninja -j1` runtime build completed its remaining 84 steps
  in about 767 seconds, reusing the existing build directory. The scope peak
  was 5123502080 bytes; high/max/OOM/OOM-kill events were zero. Two shader workers
  and the existing compiler GC prefix were retained.
- Planner and actual DXVK lifetime-tracker tests pass at the tested source,
  including partial clusters, insufficient capabilities, strict geometry-index
  bounds, previous-TLAS retention, unload and cancelled-command reservations.
  The imported Linux file-watcher regression also passes.
- All 574 linked ELF shader symbols match the cached SPIR-V bytes and alignment,
  including `cluster_pack`. Selected production shaders validate and contain no
  NV cluster SPIR-V capability. C API 0.16.0/1/2 canaries succeed and incompatible
  versions return the expected rejection without overwriting the output buffer.
- The expanded GPU executable SHA256 is
  `37f9f354ffe0e5a92a6b2ee314f75268726b3d01509c771e11e040e8dc0be2f9`.
  It passes on an RTX 5070 Laptop GPU, driver 615.78.08, extension revision 4.
  Queried limits are 256 triangles/256 vertices, max geometry index 16777215 and
  CLAS/BLAS/scratch alignments 128/256/256. Only common header-revision-2 operations
  are used. Synchronization validation reports no VUID/SYNC error.
- The GPU test executes the production committed-hit macro and production gather
  for reversed/interleaved uint16 and uint32 input, 65 triangles, a partial final
  cluster and an inactive primitive. Hit/distance/primitive/geometry/cluster,
  surface/material/custom-index/view-model checks have zero mismatches. Grid and
  zero-survivor tests pass. Its synthetic rebuild timings are not a game baseline.

### Real renderer and performance evidence

Private S25 sessions use the same JAR/runtime/JNI, Simplista ZIP, settings and
world. PID/start-time/cwd/instance and actual loaded paths/hashes are checked.
Both runs restore settings, remove their private world alias, reap their owned
processes and finish with zero high/max/OOM events. All build/GPU/game work is
serialized through `resource-run.sh` and its existing global lock.

The game reports, for example, 68 cluster BLAS, 940 active CLAS and 68 ordinary
opaque-TLAS instances referencing them. The traversal uses the normal instance
masks, transforms, SBT and material/surface mappings. During the recorded camera
path, 11 cluster BLAS/77 CLAS remain active. Build count stays at 303 while hits
increase from 989897 to 996497, demonstrating retained reuse rather than forced
per-frame rebuilds. The measured pair's largest sampled rounded reservation was 6574080 bytes;
this workload does not test budget pressure at the 256 MiB limit.

The existing fixed-60-Hz Java camera path records 600 frames after 600 warmup
frames, at 1280×720 and render distance 6. Reported simulation distance is 12 in
both runs. The KHR baseline keeps its ordinary cache and all rendering features.

| Frame time (ms) | KHR/off | CLAS/on |
| --- | ---: | ---: |
| Mean | 8.643 | 8.827 |
| p50 | 9.530 | 9.483 |
| p95 | 12.443 | 12.483 |
| p99, linear quantile | 16.711 | 18.755 |
| Maximum | 21.379 | 25.909 |

Neither run records a frame above 33 ms. On has 2.13% higher mean frame time in
this single sequential pair, which is not evidence of a speedup or a statistical
estimate of a universal regression. Four conservative interior 120-call CPU
windows show dynamic-BLAS phases of 0.031317 ms off and 0.033429 ms on. These are
CPU recording phases, not GPU AS timestamps. Most frame time belongs to ordinary
submit/render and atlas work; no production GPU AS build timestamp was isolated.
There is no rebuild saving to claim during a window with unchanged CLAS builds.

Global `nvidia-smi` peaks are 2391 MiB off and 2435 MiB on; these include other
applications and the compositor and are not precise CLAS allocations. Sampled
Java RSS peaks are 6206968 KiB off and 8515340 KiB on. The shared session scope
peak is cumulatively 9189801984 bytes. Startup/JIT/charged file-cache differences
are not attributed to CLAS storage. Raw frame series, CPU windows, memory samples,
artifact comparisons and the chart are preserved in `scratch/cluster-next/`.

### Visual checks and harness fixes

All 26 original images in the follow-up pair were opened and reviewed. All 13
actual player/camera poses and the JAR/runtime/JNI/pack/configuration hashes match.
The wall material layout, colored glass, shulkers, end-portal sources, ordinary
shadows and refracted/reflected surfaces remain present. The stone edit is visible
and server-confirmed, then removed; the fixture is present again after unload and
reload. Fullbright stays off. Direct/indirect displacement lighting, NEE cache and
ReSTIR GI are queried as enabled. These are bounded scene observations, not
exhaustive pixel equivalence for stochastic lighting or every reflected ray.

A transient purple geometry streak appeared in the first flag-off freecam image;
it is absent in both corresponding matched-pose follow-up images. No fix for that
baseline observation is claimed. Preserve and validate the parent's later render
fixes when integrating. The natural-terrain pair retains the same coastline and
water geometry, with differing temporal water/lighting samples under heavy fog.

Evidence is in `game-on-20261112-021631`, `game-off-20261112-022051`,
`visual-on-20261112-023947`, `visual-off-20261112-024339`,
`pair-analysis.json`, `benchmark-cpu-windows.json` and
`matched-visual-status.json`, all under `../port-artifacts/scratch/cluster-next/`.

The first launch attempt did not start a Game JVM because an old private Prism
instance stored its Java path in the UI section. The harness now sets General's
Java override and a 60-second JVM start bound. A serial-controller cleanup defect
was also found: collecting every cgroup PID included the parent Python controller.
The shared `tools/cluster_selftest/session_processes.py` excludes the full ancestor
lineage and preserves PID/start-time ownership for cleanup even after a scope
error. Its nested-controller regression and subsequent real serial sessions test
this behavior. The live follow-up used helper SHA256 `e8450238c3b3fadac8c6a5df3202dc869d19b6d38d16ee0b03a72655c7b07662`.
The final process-only regression also covers the added stale-PID and cgroup
identity guards in helper SHA256 `7300f8b8e54b65003a72a1f737636f59a25ba0ee8cb30a1557329598512a11f2`.
It reaps the real nested launcher/session, passes, and records a 16404480-byte
scope peak with zero memory events. Restore data is persisted before launch as
well as held in memory.
The failed attempt is recorded separately and is not a successful game check.

The first pair's close wall images had a camera-angle race: the existing `tp`
hook sets client angles before the queued server teleport completes. The visual
follow-up reapplies `look` after teleport acknowledgement and records the actual
pose before every shot. It does not reuse mismatched images as a visual pass.

### Remaining bounds and parent integration

No physical unsupported-device, maximum-page or budget-pressure execution is
claimed. Software capability/limit rejection and the flag-off KHR game path are
covered. Full production Vulkan validation, exhaustive reflected/indirect ray
coverage and isolated game GPU AS timestamps are not established by these runs.
Natural-terrain views at the selected pose are heavily affected by distance fog.
The targeted fixture review is not a replacement for the separate block audit.

Main advanced during this task to later render fixes, including `fdb4fb9`'s
AS-scratch destination write synchronization and `5e82180`'s explicit API-instance
sidedness. They are outside this immutable measured artifact. Parent integration
must retain those changes, render-pass/format/frame-generation fixes and the
cluster hierarchy lifetimes, then rebuild, verify exact loaded hashes and run a
bounded combined regression. These results do not validate that future artifact.
Skip the duplicate local `3f5dd79` and `47ab120` when importing into a main branch
that already contains `9579309` and `2bc9ba1`. No main source/staging changes,
user installation, push, PR, new checkout or subagent was performed by this task.
