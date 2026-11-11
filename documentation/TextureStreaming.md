# Texture streaming and transfer budgets

The CPU texture streamer uses one checked layout for mip sizing and row-band
planning (`rtx_texture_upload.h`). Arithmetic uses `size_t` with checked products
and sums. Compressed edge blocks are rounded up in storage and clipped in image
coordinates. Each copy addresses one array layer and one depth slice, so neither
source pitches nor layer placement depend on a flattened 32-bit image size.

## Staging ownership

`stagingBufferSizeMiB` is a ceiling, not a preallocation or a texture-size limit.
The async pool uses the smaller of this setting and 8 MiB. Immediate small uploads
and final dirty-region reconciliation share a second pool capped at 4 MiB (or the
configured ceiling, if smaller). Each pool owns at most one buffer. Buffers are
sized to current demand with a 1 MiB floor, cannot be replaced while reserved or
used by GPU commands, and are released when idle. Thus these streaming pools own
at most 12 MiB of Vulkan buffer storage at the default setting. Driver allocation
pools and the source asset's CPU mip storage are separate from this bound.

An allocation carries a shared lease that acquires a temporary write reference.
Cancellation and failed reads release it through RAII. Recorded command lists
track their own read references before the lease can expire. GPU retirement is
required before reuse, resize or release. Timed producer waits observe shutdown;
no timeout permits a second oversized allocation.

## Banded uploads

Small mip chains are packed together. Larger chains are packed on the texture
worker as complete block rows, within the async pool's ceiling. Normal frames
record at most 8 MiB and 32 ready batches. The image being filled belongs to the
upload transaction and remains invisible to materials until the final batch.
Previously resident images remain usable during promotion.

The transaction retains the source asset and image description. Hot reload or a
new demotion cancels obsolete transactions. Source caches are evicted after each
subresource and on cancellation/failure. Mutable memory textures lock each byte
copy. Animated writes made during a base upload retain their dirty rectangles;
the final batch drains those rectangles in bounded row bands using the immediate
pool before publishing the image. Existing resident-atlas partial updates retain
their separate `DxvkContext` upload path.

Explicit synchronous capture options drain and retire batches in the current
frame. They permit a frame stall while preserving the staging bound. Normal
streaming never falls back to a synchronous complete giant mip upload.

## Device memory admission

Before allocating an image, the consumer queries its Vulkan memory requirements
without binding memory, rounds to buffer/image granularity, and checks the
existing material-texture budget against current device-local usage. Private
images and resources awaiting retirement are included in that usage. Insufficient
space defers the request and signals existing budget-pressure handling. A
same-size replacement or demotion can release its old image to permit retirement.
A texture that cannot fit even by itself remains pending until the budget changes;
it does not bypass admission or force streaming off. Large non-demotable atlases
therefore still require a texture budget large enough for their actual GPU image.

## Validation

`tests/rtx/unit/test_texture_upload.cpp` plans 12288x10240, 16384x16384 and
24576x16384 textures without allocating giant arrays. Small real byte copies test
partial compressed blocks, depth slices, edge bounds and insufficient row budgets.
Overflow cases cover block rounding, products and mip-chain accumulation.
Production validation also needs a native game run that exercises GPU retirement,
atlas animation, hot reload and memory pressure. Planned slice sizes are not
measurements of physical memory usage, GPU transfer time or frame time.
