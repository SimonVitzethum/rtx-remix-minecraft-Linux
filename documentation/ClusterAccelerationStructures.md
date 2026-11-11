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

## Validation status — 2026-11-11

The implementation remains experimental. The complete production link and
matched game validation are **pending**, following an explicit resource-priority
handoff to the block audit. This is not a completed game optimization or a
measured game speedup. No hard Vulkan/API blocker has been found.

The source baseline is `b07f55fac673e9a32d2eb53e96597ae13f616311`, including
API 0.16.2 and the `f3f569d` ELF COMDAT shader embedding fix. Committed main fix
`95793098b4eb5e16ae7a1a2eb9d35aa1ecab6276` is included locally as
`3f5dd79349b1ec17d7cbe7bfd64a0f84d54a8681`. Production source and the expanded
harness are at `c8deb130dfed770b45506741d7d2882f3af72700` before this documentation
update. The only pre-existing untracked state is `submodules/nrc`, which was
preserved.

### Completed checks

- The production planner passes coverage/ordering tests for 1, 63, 64, 65, 127,
  128, 65535 and 65536 primitives. Empty/oversized/overflow input, insufficient
  vertex/triangle capabilities and the strict geometry-index bound fall back.
- The actual DXVK lifetime tracker passes tests for outstanding submissions,
  cache unload, previous-TLAS references and unsubmitted-command cancellation.
  Reservations stay charged until references retire. The current CPU checks
  and harness build pass at `c8deb13` (416186368-byte compile-scope peak).
- Production shaders compile and validate, including the hit normalization
  helpers and gather shader. The cluster production C++ object compiled with
  `-O3`, and the existing production build directory is reused incrementally.
- GPU regression at `bdc9927e53520ffdfae38ad35b208ad2a16eae88` passes on an NVIDIA
  GeForce RTX 5070 Laptop GPU, cluster extension revision 4. Queried limits:
  256 triangles/256 vertices per cluster, max geometry index 16777215, CLAS/BLAS/
  scratch alignment 128/256/256 bytes. The checked-in headers expose revision 2;
  only the common supported operations are used.
- That run verifies hit/miss, distance and normalized primitive/geometry/cluster
  IDs on a bounded 8×8 grid, including zero surviving clusters. It also executes
  the actual production gather for reverse-indexed/interleaved uint16 and uint32
  input, a 65-triangle partial final cluster and an inactive primitive inside
  the first cluster. All gather/identity checks have zero mismatches. PTLAS
  regressions also pass in the isolated harness; PTLAS is not used in production.
- Synchronization validation reports no VUID/SYNC violation in that run. Its
  only warning concerns a deprecated test-layer configuration setting; subsequent
  prepared runs use `VK_LAYER_VALIDATE_SYNC=1`.

The initial GPU executable SHA256 is
`d0cb925af739c5f93f5d7d28ebe331e06ff4a27e2a23130a799cca870b818ddc`.
The GPU test's host scope peak is 123383808 bytes. These are isolated correctness
results, not game, VRAM or renderer-performance measurements. The initial harness
contains synthetic unconditional KHR rebuild timings; they are not a retained
cache baseline and are not used to claim a performance benefit.

### Audit-priority interruption and continuation

The resumed serial `-O3` build was interrupted explicitly for resource
coordination at **74/154**. Its scope retired completely and all owned resource
wrapper processes ended. Peak host scope memory was 3148054528 bytes; high, max,
OOM and OOM-kill counters were zero. This interruption is not a source failure
or memory failure. Finished TUs and the existing compiler cache remain available.
No user program was terminated and no main source/staging file was changed.

All build/GPU/game continuation must wait until the priority audit has completed,
then use the shared `resource-run.sh` admission and lock again. Prepared scripts
and exact logs/statuses are under `../port-artifacts/scratch/cluster-next/`:

- `after-priority-compile.py`: serial expanded-harness/lifetime checks, then the
  remaining incremental native build. It keeps release optimization, two shader
  workers, `ninja -j1`, compiler GC limits and the existing embedding fix.
- `gpu-check.py`: the expanded GPU regression now also consumes the real
  production committed-hit macro and checks surface ID, material type and
  custom-index/view-model flags. This **expanded GPU execution is still pending**.
- `stage.py`: refuses staging without a successful exact-HEAD native build and
  matching raw runtime hash. It writes only its own scratch package. The prepared
  Java base is `e2300af` (main owner's 854 passing tests), jar SHA256
  `81f01c858e62df0d2387799836780078ffa6b7b4faf6e7db63490b8c388a70d3`.
- `game-session.py on|off`: private S25 instance/profile, identical package,
  feature settings, resource pack, scene script and poses; virtual game-window
  captures only. It covers material views, natural terrain, block changes and
  unload/reload, followed by the existing deterministic Java camera benchmark
  (600 warmup and 600 recorded frames). It uses an alias to the existing private
  test world, never a copied world, and restores configuration/removes its alias.
  Every loaded runtime/JNI path and hash, PID/cwd/start time and cleanup result is
  checked. The whole session, including cleanup, belongs inside the game wrapper.

There is no game-stage artifact or game result from this branch yet. The prepared
session scripts have not been executed and may need ordinary harness fixes.

### Remaining evidence and integration

Complete the native link, run the expanded GPU check, then prove nonzero live
CLAS/cluster BLAS and opaque-TLAS instance counters in the actual game renderer.
Review matched material, shadow and reflection images. Compare retained-cache
hits/builds and frame distributions, CPU recording cost, host memory and measured
VRAM; do not infer game GPU AS-build time from standalone timestamps. Game GPU
AS-build timings and any performance advantage remain unmeasured.

Unsupported-device behavior is guarded by advertised extension/features and
queried limits, and the shader mapping uses ordinary KHR builtins. Planner
fallback cases are tested in software. No Nouveau/AMD/older-NVIDIA hardware
validation is claimed. Maximum-page/budget pressure and real game reload/retirement
still need execution.

No Java contract or public Remix API change is needed. Parent integration should
include the cluster commits and retain the main `9579309` diagnostic fix; when
cherry-picking into a branch already containing that fix, skip its local duplicate
`3f5dd79`. No push, PR, user installation or main merge was performed.
