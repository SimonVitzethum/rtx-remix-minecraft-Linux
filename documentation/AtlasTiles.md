# Atlas tile sampling

API mesh surfaces opt into atlas bounds with either `TEXCOORD_BOUNDS` (binary16)
or `TEXCOORD_BOUNDS_UNORM16` (API 0.16.2). The vertex remains 64 bytes, with four
16-bit components at byte 36: minimum U/V, then maximum U/V. Bounds are identical
on all vertices of a triangle. The UNORM flag takes precedence if both are set.
Neither flag means the bounds are ignored, regardless of the vertex stride.

The API converts binary16 bounds to UNORM16 on upload. Raster geometry carries
an explicit bounds flag, serialized in bit 31 of the GPU surface flags. The
shader reads bounds only for that flag and the preserved API vertex layout.
`AtlasTile` reconstructs integer texel boundaries by rounding against the actual
texture dimensions. This recovers exact texel boundaries for atlas dimensions
up to 32768, including rectangular atlases.

Material reads use the same tile for albedo, opacity, normals, emission, and
other PBR channels. Sampling repeats outside a tile while preserving the two
endpoints of an ordinary face. Every anisotropic tap is constrained to a valid
bilinear footprint. Only mip levels whose cells align with all four tile edges
are used: a mip containing two sprites cannot be repaired by clamping UVs.
Unaligned tiles therefore retain a finer mip at distance. This favors correct
materials over contaminated coarse mips, but can increase distant aliasing and
sampling cost. Authoring padded, aligned atlases or supplying tile-specific mip
chains would allow coarser filtering without cross-sprite contamination.

POM samples the existing height channel at level zero. Its march operates in
local tile coordinates and corrects the tangent basis for rectangular atlas and
tile aspect ratios. Atlas POM uses the bounded layer march for both POM modes,
because the global quadtree contains unrelated tiles. Unbounded textures retain
the original raymarch and quadtree paths.

The shared displacement G-buffer stores `uint4`: the bit patterns of the two
float UV components, followed by the two packed bounds. Integer storage preserves
all bound bits, including patterns that would represent NaNs in float storage.
This adds eight bytes per compacted render pixel without adding descriptors.
Direct, indirect, NEE-cache, and ReSTIR visibility consume this same payload;
secondary hits construct it from their own surface interaction. Height shadows
repeat within the tile rather than sampling neighbouring sprites. The Java
renderer does not override the runtime's displacement feature settings.
