# Far-field terrain

Far field uses the existing Vulkan environment DAG. The supported 512-chunk distance,
exact loaded client voxels outside the path-traced radius, Bobby fake chunks, generated
single-player terrain, persistent received multiplayer surfaces, sky updates and water
tints share this pipeline.

## Design choices

Renderer dirty flags alone miss off-screen content changes and mix geometry changes with
light-only rebuilds. Hashing every voxel repeatedly would detect mutations but makes the
cost follow the loaded world volume. Section content revisions instead make the existing
planning pass proportional to section count; immutable owner-thread palette copies put
voxel work on workers without racing client-world mutation.

The existing 256-block subtree is the invalidation and publication unit. A changed input
rebuilds that subtree while unrelated cached subtrees remain reusable. Finer per-section
DAG patching would require additional retained intermediate state and relocation rules;
this change keeps the single DAG architecture and bounds capture/worker admission instead.

Additional far detail comes from a bounded 2-block horizontal band and integer-height
surface boundaries. Retaining full remote voxels everywhere would preserve overhangs and
materials but scale memory with remote volume. Received surfaces and procedural fallback
therefore share the tile source, while loaded real/Bobby chunks retain their exact voxels.
There is no second distant renderer or separate publication system.

## Content ownership and refresh

`FarSectionRevisionMixin` increments a section revision only when `setBlockState` changes
the state, or when block/biome packet data or generated biomes replace its contents.
The overload with the lock argument is the common block mutation boundary in 26.3;
the other overload delegates to it. This catches edits to the same chunk object and
in-place packet reloads. Light-only renderer dirty flags do not invalidate geometry.
The client thread samples these counters and section identities during the existing
500 ms planning pass over loaded client/Bobby chunks. It never scans all world voxels
per frame. Chunk slots contribute to the signature in spatial order, independently
of Bobby collection iteration order.

One column at a time captures private copies of block and biome palettes, at most eight
chunks and a cooperative 2 ms deadline per frame. One indivisible chunk copy may exceed
the deadline. Surface recording has a separate eight-chunk/2 ms allowance per frame, a coalesced
1024-key pending queue and a 32-snapshot worker queue. An unload hook also offers the
final state of chunks that leave between planning passes. Loaded chunks whose admission
is deferred are offered again on the next planning pass. Worker jobs only read the private copies. There are at
most twice the configured build-thread count of outstanding subtree jobs, including
completed results waiting for the client thread. The build-thread setting is bounded
to 1..4 and noise workers to 1..2. Tile request queues admit at most 4096 distinct jobs
each; equal-distance jobs use their spatial key as a deterministic tie breaker.

`FarBuildCache` owns request identities and publication on the client thread. A changed
signature, eviction or session reset invalidates old requests, including A -> B -> A.
Before accepting an exact build it rechecks the captured revisions and the identity of
live real chunks. Failed/stale results leave the last published subtree intact and are
eligible for another build. A partially captured column is also rejected if it changed.
Merges consume an immutable set of published subtrees; merged DAGs carry the world
session through upload, and old-session readbacks never become the new world's texture.
GPU upload retains its existing 4 MiB/frame slicing and idle-buffer swap. An upload
finishes its selected DAG before selecting the newest merged replacement; incoming
merges cannot keep restarting the slice cursor and starving image publication. Sky-light
release also shuts down the far field before the next world's dome is selected. Fixed
Nether/End skies use their own texture and cannot acquire the preceding Overworld's
composite, even though they do not execute an atmospheric far-field tick.

This is eventual refresh under explicit work budgets, not a hard wall-clock guarantee:
planning and merge have 500/400 ms cadence gates, image dispatch has a 1 s gate, and
capture, worker queues, upload and GPU completion add workload-dependent time. Continuous
edits may supersede a build repeatedly. The published old subtree remains visible during
that work. Logs distinguish observed-to-subtree-publication latency from GPU dispatch /
fence time; neither alone is end-to-end edit-to-image latency. Set
`-Drtxremix.farfield.diagnostics=true` to log every accepted subtree batch and image
publication with its session/generation during a controlled comparison.

## Surface sources and detail

The nearest generated/cached band (column-centre distance below 32 chunks) uses 2-block
horizontal cells, followed by the existing 4/8/16-block bands. A 2-block tile is smaller
than a subtree; `FarSurfaceTiles` assembles its four tile references, including at negative
coordinates. Coarser tiles are shared across neighbouring subtrees. Heights end at the
integer block boundary at every LOD: only boundary nodes subdivide below the horizontal
cell size. Uniform underground volume and repeated boundary nodes still collapse in the
same DAG. Surface and exact voxel colours remain RGB565; exact voxels now use copied
section biomes rather than a single biome/tint for the entire chunk.

Received chunk surfaces override procedural single-player terrain as well as filling
multiplayer terrain. Live revisions cause another surface recording; unchanged content
does not increment the cache epoch. Final changes of retained unloaded chunks are offered
again within the recording budget. Old immutable tiles remain usable while replacements
are built. A tile changed during its build is rejected and retried on a later request.
The received-surface disk format remains version 1, so existing multiplayer caches are
readable. An empty received column also overrides generated ground. Sampling starts at
`ChunkAccess.getHeight()` itself: that method already returns the highest occupied Y;
subtracting again would discard a one-block surface/material layer. Old disk entries
remain approximate until revisited and recorded again.

Cache recorder shutdown drains admitted snapshots and flushes on its worker. A future
waits for tile readers that can still evict dirty regions and procedural workers that can still write tile files, then hands disk ownership to the next session without blocking the client thread, including
world swaps and far-field off/on cycles. Missing regions are remembered, avoiding a file
existence check for each cell of unvisited generated terrain. The disk quota root is explicit and never inferred from a directory's parents. The
resident-region cap is enforced before loading another region, under the same worker
I/O monitor as record publication; dirty regions are persisted before eviction. Read/write failures are
logged; failed writes retain the dirty state and cannot evict the only resident copy.

## Source limits

* Exact data means classified map-colour voxels, not textured meshes, transparency,
  per-block PBR materials or path-traced lighting. Non-colliding decorations with no
  classified distant surface remain excluded as before.
* The current dome integration runs far terrain for Overworld skyboxes. Fixed Nether/End
  skies retain their constant sky behavior; no Overworld cache/image is reused there.
* Generated terrain has no knowledge of unreceived player edits, structures, trees or
  caves. Received surfaces preserve edits after unload, but overhangs/caves require a
  loaded real or Bobby chunk for exact geometry. Surface cells average height and colour;
  2-block cells can still lose a one-block horizontal feature.
* Bobby fake chunks are snapshotted and rendered exactly. Their content revisions are
  observed while enumerated; removing a fake is noticed by the next planning pass. They
  do not overwrite the received-surface cache with possibly older disk replay data.
* Integrated-server changes outside all chunks ever delivered to this client are not
  read from live server chunks. Such reads would violate thread ownership; supporting
  them needs an owner-thread server snapshot/delivery protocol. Procedural noise remains
  the explicit approximation for those areas.
* A surface-record overload at unload (1024 pending chunk keys) is explicitly logged;
  the last persisted surface is retained, but the newest unadmitted surface may be lost.
  There is no claim of lossless caching under an arbitrarily large unload burst.
* Persistent cache durability requires an orderly recorder flush and successful disk
  writes. Only snapshots already admitted to the 32-entry recorder queue are drained at
  shutdown; unsnapshotted offers in the client-side pending queue can be lost on a world
  switch or when far field is disabled. Copying that entire backlog synchronously would
  violate the owner-thread frame budget. Process termination cannot guarantee the last
  queued snapshots reach disk.

## Verification

`FarBuildCacheTest` exercises repeated edits during builds, completion-versus-publication
races, unchanged inputs, bounded slots, failed jobs, eviction/reload and session changes.
`FarSubtreeTest` reads the resulting wire-format DAG independently and checks seeded
height/material cases across all four LODs, slab boundaries, exact place/break and air
caves, determinism and flat-volume sharing. `FarSurfaceTilesTest` checks fine tile joins
and negative/coarse tile offsets. `FarCacheSourceTest` exercises real asynchronous tiles,
unchanged surfaces, colour/height updates, persistent reopen and received overrides.
These JVM checks do not prove mixin execution, visual quality or game performance.
