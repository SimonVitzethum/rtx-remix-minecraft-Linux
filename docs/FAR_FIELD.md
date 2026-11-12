# Far-field terrain

Far field renders classified distant terrain into the Vulkan environment dome. It
supports the configured 512-chunk extent, existing loaded/Bobby exact voxels,
procedural integrated-server surfaces, persistent received multiplayer surfaces,
integer heights, sky updates and water tints through one GPU-resident DAG.

## Ownership and representation

`FarField` keeps spatial keys, revisions, source presence flags, GPU node ranges and
small slab roots. It does not retain Java surface tiles, copied chunks, heightmaps,
exact voxel arrays or a flat scene DAG. `FarTerrainSource` stores received surfaces
and compact DAG pages on disk. The old Java subtree/tile/snapshot implementation is
only a test reference; no production fallback invokes it.

`VoxelDag` is a Minecraft-independent JNI CPU service, separate from the unchanged
Remix C API. At most two jobs own native workspaces. A job streams procedural surface
messages of at most 64 KiB, then exact 8 KiB section messages from existing gameplay
or Bobby chunks. The native builder compresses each exact section immediately and
marks its whole chunk column exact; absent sections are air, so procedural surfaces
cannot fill caves. Every surface cell, including empty cells, is explicitly supplied.
The job seals its native DAG, writes a compact disk page, uploads bounded slices and
releases the CPU handle after the final transfer fence. An open disk page owns roots
and a file descriptor; slice reads never create a complete RAM mirror.

Native workspace admission counts node storage, hash storage and bounded decoding
scratch under one 64 MiB process budget, with at most two builders. Allocation failure
is explicit, with no coarsening, distance reduction or silent dropped geometry.
See [the native service contract](../rtxremix-java/native/VOXEL_DAG.md) for its exact
allocator and disk format. Source message buffers and GPU memory are separate from
this budget: the surface record queue holds at most 32 messages (4 KiB pool capacity
each), active surface messages are at most 64 KiB each, and the render thread reuses
one 8 KiB exact encoder. Source diagnostics expose their live capacities.

`GpuDagStorage` allocates unmapped device-local node storage on Minecraft's Vulkan
device. Its arena starts lazily at 8 MiB, grows geometrically, and copies existing
nodes GPU-to-GPU only when growing. Node addresses stay stable. Arena capacity is at
most the device storage-buffer limit or 128 MiB, whichever is smaller; the growth
budget is 256 MiB including old/new arenas and the active staging allocation. An
exhausted arena fails visibly; it never evicts still-wanted pages to disguise pressure.
The upload buffer starts lazily at 64 KiB and grows geometrically to at most 4 MiB,
with an 8 MiB old-plus-new replacement allowance. Each frame uploads at most 4 MiB.
No capacity miss forces a global synchronization. Idle buffers are trimmed after
30 seconds; retained pages keep their arena. Freed GPU ranges coalesce and are reused.

The root directory contains only subtree addresses, not subtree node payloads. A
new directory is committed after its transfer completes. Replaced subtree and
directory ranges retire only after the last image reader completes and no current
or uploading directory refers to them. Transfer writes have explicit dependencies
for both subsequent compute reads and arena-growth transfer reads. Submitted and
published image generations retain provenance metadata, without owning CPU DAG data.

## Sources, edits and world changes

Minecraft render/simulation distance is never increased for far field. Existing
client chunks and Bobby fakes are inspected on their owning thread; the mod neither
requests nor retains extra remote gameplay chunks. `FarChunkMessages` encodes exact
sections into one reused buffer and computes cheap content revisions without a
voxel scan. Block/biome packet changes and live edits invalidate their native pages;
light-only rebuilds do not. A capture rechecks identity and content before publication.

For unvisited integrated terrain, `FarTerrainSampler` uses the existing generator's
`getBaseHeight` and biome resolver. This does not load `ChunkAccess`, `LevelChunk` or
`ProtoChunk`, or acquire chunk tickets. In 26.3 that call does create a temporary
`NoiseChunk` density evaluator and scoped density buffers, which are closed by vanilla.
There are at most two sampling jobs. This is bounded transient procedural work,
not a claim of zero Java or system RAM. A different native/GPU world generator would
need independent parity evidence before replacing those samples.

Received surfaces override generated surfaces, including explicitly empty columns.
Their existing FCC version 1 files remain readable and writable; native code streams
one region with bounded decoding scratch. Java's disk worker reads only presence
metadata, so an unobserved multiplayer area is never reported as valid empty terrain.
New compact DAG files retain local references and can reopen without rebuilding.
Procedural source files stay on disk; palette content fingerprints and generation
checks prevent a resource reload from reusing old colours or publishing mixed generations.
Existing exact sources retain map-colour voxels at 1-block precision. Generated bands
use 2/4/8/16-block horizontal cells at the existing distance thresholds, while vertical
surface boundaries remain at integer block heights.

Planning has a 500 ms cadence. Capturing and surface recording each have a cooperative
2 ms owner-thread deadline; one indivisible section/column operation may exceed it.
Exact capture processes at most 64 sections per frame. Initial far coverage gets a
rotating admission scan, alternating with published-page edits, so repeated near
changes cannot monopolize both job slots. Uploads finish their selected page and
directory before starting replacements. Continuous edits may still repeatedly
invalidate a particular page; this is eventual refresh, not a hard latency guarantee.

An unload offers the final surface immediately without retaining the departing chunk.
If the bounded recording queue is full, the last persisted surface stays available and
the skipped final offer is logged. Loaded sources deferred by admission are retried
through the normal planning pass. An orderly source shutdown drains accepted records;
its completion also waits for all active build leases. The next session waits on that
future on its workers, never on the render thread. Old-session build completions,
GPU directory commits and image readbacks cannot publish into the new world.

Native terrain stays visible for Chebyshev chunk distance `< radius`; the far cut is
`>= radius`. The half-open ring convention, source coverage and native page visibility
are distinct from actual ray hits. The existing automatic distance fade is unchanged
by this memory migration. Its 70%..80% planar band can hide the outer native ring;
changing that quality policy requires a separately identified comparison.

## Diagnostics and evidence limits

`diag coverage` retains wanted, accepted GPU cache, current directory, active upload,
submitted image and published image stages. Each includes session, generation, source
signatures and spatial row ranges. Upload diagnostics distinguish a subtree slice
from a directory upload. Valid empty source subtrees count as covered without geometry.
Native-region, exact live/Bobby and surface-source counts are not pixel counts.
A published stage proves a completed far-field image and accepted runtime texture
patch; it is not a presentation fence proving later pixels sampled that image.

Diagnostics report native reserved/output/scratch metrics, GPU allocated/used/retired
bytes, staging capacity, source-message capacities, upload/growth bytes, capacity
misses and deferred frames. Native used output includes unfinished jobs; it is not
persistent mirror memory. `residentJavaSources=0` and `nativeMirrorBytes=0` describe
this pipeline's persistent representation, not process RSS or zero temporary memory.
Live histograms after GC, native metrics and GPU lifetime checks are needed to verify
those lifetimes in a real game. JFR allocation samples alone do not prove retention.

Per-frame `Far.*` counters add upload bytes, arena-copy bytes, capacity misses, staging
encoding/growth CPU time, copy-fence poll wall time and live capacities to the existing
benchmark rows. Fence poll wall time includes time until the next client poll; it is
not isolated GPU execution time or a synchronous CPU wait. Capacity pressure is a
hypothesis for movement stalls until correlated against those counters and native
submission/driver diagnostics. Reducing full DAG copies alone does not establish the
cause of multi-GiB pressure from ordinary RD32 terrain and native assets.

The native test suite checks bounded allocation, wire data, disk corruption and JNI
buffer contracts. `VoxelDagNativeTest` compares thousands of seeded native voxels
against the old independent Java reference, exercises negative coordinates, exact air,
disk reopening and source lease teardown. Set `RTX_VOXEL_DAG_TEST_LIBRARY` to a built
JNI library to run those CPU tests without starting a GPU or the Remix runtime.
`GpuDagAllocatorTest`, `GpuDagTopologyTest`, `UploadBufferBudgetTest` and
`FarBuildCacheTest` cover movement retirement, address reuse, topology relocation,
replacement budgets and stale/reset disposal. These tests do not prove visual parity,
full 512 coverage, GPU synchronization or movement performance.

The dome still uses classified RGB565 terrain, not textured/PBR far meshes. Unreceived
procedural terrain approximates biome surfaces without structures, trees or caves;
existing client/Bobby exact sources retain caves. Fixed Nether/End skies keep their
own sky behavior. Source coverage must never be presented as complete 512-pixel or
32-RTX-ring visual approval.
