# Transient voxel DAG workspaces

`VoxelDag` is a Minecraft-independent CPU builder and disk-page service in the
JNI bridge. GPU allocation and submission belong to the caller's Vulkan device.
It adds no fields or functions to the Remix C API.

A handle owns one 256-by-256 block column, with 1 to 16 vertical slabs of 256
blocks. Surface messages contain at most 64 KiB of row-major packed height and
RGB565 samples. Heights are relative to the column's minimum Y. Exact messages
contain one 8 KiB section of RGB565 voxels. The builder consumes their bytes
before returning and retains no caller pointer. The first exact section marks
its whole chunk column as exact; unspecified sections in that column are air.
This prevents procedural terrain from filling exact caves or empty sections.
Every surface cell must be supplied, including explicitly empty cells.

Each builder has a fixed pool of 524,288 nodes (16 MiB), a fixed hash table
(4 MiB), and less than 1 MiB of input grids, exact-section roots and metadata.
At most two builders coexist. Capacity is admitted before allocation and remains
reserved until the last operation holding the workspace finishes. The process
budget is 64 MiB, shared with admitted disk-I/O scratch. A page that exceeds its
node bound fails explicitly; callers must partition such a page while preserving
its detail. It is never silently coarsened or clipped.

`finish` seals the page and returns its node count; subsequent calls only return
that count. Roots and output become immutable. Output is eight native-order
uint32 words per node. Zero is empty, bit 31 denotes a uniform RGB565 leaf, and
other values are node indices. Node zero contains eight zero words. `copy`
relocates only nonzero non-leaf references into a bounded direct upload slice.
The caller copies the slice to device-local memory and keeps its resources alive
through the transfer fence. It then destroys the CPU handle. Published GPU pages
have no native CPU mirror.

`metrics` returns the global limit, current/peak reserved buffer capacity, live
builder count, current/peak used node bytes, and current/peak admitted I/O scratch.
Used output includes unfinished builders and is a subset of reserved capacity.
Small handle/stream metadata, the caller's Java buffers, GPU memory, driver memory
and process RSS are separate quantities. Reservations include conservative
headroom for all builder allocations; they are capacity bounds, not RSS samples.

## Disk pages

`save` writes local references, roots and dimensions to a temporary file, then
atomically replaces the destination. `open` validates dimensions, exact length,
checksum, node zero, roots, leaf encoding, and backward-only references before
returning a handle. Validation streams through 64 KiB scratch. An open handle
retains a file descriptor and roots, and `copy` reads the requested slice directly
into caller staging memory. It does not allocate a complete DAG array. Disk
pages are immutable: updates replace files instead of overwriting open files.

The disk format is little-endian: a 96-byte header followed by 32 bytes per node.
The header contains uint32 magic `0x47414456`, version 1, lg, slab count, node
count, the low/high words of an FNV-1a-64 payload checksum, one reserved zero,
and 16 slab roots. Unused roots must be zero. Invalid or truncated files raise
an error before upload. Cache identity and source revisions belong to the caller.

## Received surfaces

`recordSurface` consumes 256 packed cells with *absolute* signed world heights.
It rewrites the existing FCC version 1 region stream using a 1 KiB record buffer
and atomically replaces the destination. `overlay` streams that format, averages
received cells at the requested horizontal LOD, subtracts minimum Y, and updates
only the workspace's bounded surface grid. Received empty cells replace the
procedural fallback with air. Negative coordinates use floor division. Source
I/O is serialized; no region, Minecraft chunk, palette, heightmap or voxel arrays
are retained in a source cache.

The vendored MIT miniz codec preserves existing zlib FCC files without a system
library dependency. Archive APIs and unaligned typed loads are disabled on all
platforms. Codec state, buffers and one-record scratch fit in one admitted MiB.

`test_voxel_dag.cpp` exercises real builder and disk lifetimes, exact-air
replacement, integer heights, relocation, malformed caches, received-source
rewrites, admission and explicit node overflow. Run it with ASan/UBSan in a
resource-guarded native test scope. JNI integration must additionally verify
buffer directness, read-only destinations, capacities, ignored positions, and
handle cleanup. Windows builds require their own compiler validation.
