/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/**
 * Builds the sub-DAG of one 256 x 256 block column (all height slabs of 256 blocks) from
 * <ul>
 * <li>a LOD height grid (cell = 2^lg blocks, column top + RGB565 colour, from {@link FarLodSource}) where the client has
 * no chunk: horizontal cells keep their exact integer surface height, coloured with the column colour
 * in the top two cells and a constant rock colour below (so the volume under the surface collapses to single leaves), and
 * <li>exact 1-block voxel sections of client chunks that are loaded but outside the path-traced radius.
 * </ul>
 * Chunks inside the path-traced radius are holes (their cells are empty in the grid and they have no sections). Min / max
 * pyramids over the grid make empty and deep-rock regions leaves, so the work follows the surface, not the volume.
 * Pure Java: runs on worker threads.
 */
final class FarSubtree {
    static final int SIZE_LEVEL = 8; // 256 blocks
    static final int EMPTY_TOP = -1_000_000;
    static final int ROCK = FarFieldDag.LEAF | 0x6B4D; // RGB565 of a mid grey

    /** Result of a build. */
    record Result(int[] nodes, int nodeCount, int[] roots, long leaves) {}

    /**
     * @param lg        log2 of the grid cell size in blocks (1..4), the grid is {@code 256 >> lg} cells square
     * @param top       cube-relative top (blocks above the cube floor) per cell or {@link #EMPTY_TOP}; null = no LOD data
     * @param rgb       RGB565 per cell
     * @param exact     16 x 16 chunk columns (index {@code cz * 16 + cx}), each null or sections indexed by section number
     *                  from the world bottom: null = air, length 1 = uniform colour, else 4096 shorts {@code (y << 8) | (z << 4) | x}
     * @param slabs     number of 256-block height slabs
     */
    static Result build(int lg, short[] top, short[] rgb, short[][][] exact, int slabs) {
        return new FarSubtree(lg, top, rgb, exact).run(slabs);
    }

    private final int lg;
    private final int n;
    private final int[][] minT, maxT;
    private final short[] rgb;
    private final short[][][] exact;
    private final int[] exactPrefix = new int[17 * 17];
    private final FarFieldDag dag = new FarFieldDag();
    private long leaves;
    private int slabBase;

    private FarSubtree(int lg, short[] top, short[] rgb, short[][][] exact) {
        this.lg = lg;
        this.n = 256 >> lg;
        this.rgb = rgb;
        this.exact = exact;
        int levels = SIZE_LEVEL - lg + 1;
        minT = new int[levels][];
        maxT = new int[levels][];
        minT[0] = new int[n * n];
        maxT[0] = new int[n * n];
        for (int i = 0; i < n * n; i++) {
            int t = top == null || top[i] == FarLodSource.EMPTY ? EMPTY_TOP : top[i];
            minT[0][i] = t;
            maxT[0][i] = t;
        }
        for (int k = 1; k < levels; k++) {
            int m = n >> k, p = n >> (k - 1);
            minT[k] = new int[m * m];
            maxT[k] = new int[m * m];
            for (int z = 0; z < m; z++) {
                for (int x = 0; x < m; x++) {
                    int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
                    for (int d = 0; d < 4; d++) {
                        int idx = (z * 2 + (d >> 1)) * p + x * 2 + (d & 1);
                        mn = Math.min(mn, minT[k - 1][idx]);
                        mx = Math.max(mx, maxT[k - 1][idx]);
                    }
                    minT[k][z * m + x] = mn;
                    maxT[k][z * m + x] = mx;
                }
            }
        }
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                exactPrefix[(cz + 1) * 17 + cx + 1] = (exact != null && exact[cz * 16 + cx] != null ? 1 : 0)
                        + exactPrefix[cz * 17 + cx + 1] + exactPrefix[(cz + 1) * 17 + cx] - exactPrefix[cz * 17 + cx];
            }
        }
    }

    private int exactCount(int cx, int cz, int span) {
        int x1 = Math.min(16, cx + span), z1 = Math.min(16, cz + span);
        return exactPrefix[z1 * 17 + x1] - exactPrefix[cz * 17 + x1] - exactPrefix[z1 * 17 + cx] + exactPrefix[cz * 17 + cx];
    }

    private Result run(int slabs) {
        int[] roots = new int[slabs];
        for (int s = 0; s < slabs; s++) {
            slabBase = s * 256;
            roots[s] = region(SIZE_LEVEL, 0, 0, 0);
        }
        return new Result(dag.nodes(), dag.nodeCount(), roots, leaves);
    }

    private int region(int level, int x, int y, int z) {
        if (level >= 6 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        int size = 1 << level;
        int yAbs = slabBase + y;
        if (level >= 4 && exactCount(x >> 4, z >> 4, size >> 4) > 0) {
            if (level == 4) return exactSection(x >> 4, z >> 4, yAbs >> 4);
            return children(level, x, y, z);
        }
        int k = Math.max(0, level - lg);
        int m = n >> k;
        int idx = (z >> Math.max(level, lg)) * m + (x >> Math.max(level, lg));
        int mn = minT[k][idx], mx = maxT[k][idx];
        int s = 1 << lg;
        if (mx <= yAbs) return 0;
        if (mn - (yAbs + size) >= s) {
            leaves++;
            return ROCK;
        }
        if (level <= lg && mn >= yAbs + size) {
            leaves++;
            return FarFieldDag.LEAF | (rgb[idx] & 0xFFFF);
        }
        // Only the surface boundary subdivides below the horizontal cell size. Integer tops end at block precision;
        // the homogeneous volume below still collapses, and all repeated children are shared by the existing DAG.
        return children(level, x, y, z);
    }

    private int children(int level, int x, int y, int z) {
        int[] refs = new int[8];
        int half = 1 << (level - 1);
        for (int i = 0; i < 8; i++) {
            refs[i] = region(level - 1, x + (i & 1) * half, y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half);
        }
        return dag.make(refs);
    }

    private int exactSection(int cx, int cz, int sy) {
        short[][] col = exact[cz * 16 + cx];
        if (col == null || sy >= col.length || col[sy] == null) return 0;
        short[] s = col[sy];
        if (s.length == 1) {
            leaves++;
            return s[0] == 0 ? 0 : FarFieldDag.LEAF | (s[0] & 0xFFFF);
        }
        return voxels(s, 0, 0, 0, 4);
    }

    private int voxels(short[] s, int x, int y, int z, int level) {
        if (level == 0) {
            int m = s[(y << 8) | (z << 4) | x] & 0xFFFF;
            if (m == 0) return 0;
            leaves++;
            return FarFieldDag.LEAF | m;
        }
        int half = 1 << (level - 1);
        int[] local = new int[8];
        for (int i = 0; i < 8; i++) {
            local[i] = voxels(s, x + (i & 1) * half, y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half, level - 1);
        }
        return dag.make(local);
    }
}
