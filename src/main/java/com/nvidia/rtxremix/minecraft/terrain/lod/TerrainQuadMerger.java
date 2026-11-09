/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy merge of the unit block faces of one terrain bucket, used for the distant terrain LOD.
 *
 * <p>A quad is mergeable when it is an axis-aligned 1x1 square on the block lattice whose four
 * vertices carry the same colour and atlas bounds and whose texture coordinates are exactly the
 * sprite's bounds corners (a whole-sprite block face). Mergeable quads with the same plane,
 * normal, colour, bounds and corner orientation are covered by maximal rectangles. Every other
 * quad is copied through unchanged, so the covered area, normals and materials never change.
 *
 * <p>The runtime has no texcoord-bounds wrapping yet, so by default a merged rectangle shows the
 * whole sprite stretched over it ({@code tileUv == false}). Distant blocks are a few pixels, and
 * the sprite's mip average is what survives at that size. With {@code tileUv} the texture
 * coordinates run past the bounds by one sprite per merged block, for a runtime that wraps inside
 * the bounds.
 *
 * <p>Vertices are 64-byte {@code HardcodedVertex} records: position at 0, normal at 12,
 * texcoord at 24, colour at 32, bounds min and max at 36 and 40 (UNORM16 uv pairs, the runtime's POM reads them).
 */
public final class TerrainQuadMerger {
    public static final int STRIDE = 64;
    private static final float LATTICE_EPSILON = 1.0e-4f;
    private static final float UV_EPSILON_FRACTION = 0.02f;
    private static final int PACK_BIAS = 4096;

    private TerrainQuadMerger() {
    }

    /** Result of {@link #merge}: {@code quads} quads of four vertices each at the start of the buffer. */
    public record Merged(int quads, int inputQuads, int mergedGroups) {
    }

    /** One input vertex buffer with the offset that places its section in column space. */
    public record Source(ByteBuffer vertices, int vertexCount, int xOff, int yOff, int zOff) {
    }

    /**
     * Merges {@code sources} into {@code out}, which must hold at least the total number of input
     * quads. Offsets are applied, so output positions are column-local.
     */
    public static Merged merge(List<Source> sources, ByteBuffer out, boolean tileUv) {
        ByteBuffer dst = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        Map<GroupKey, Group> groups = new HashMap<>();
        List<Group> order = new ArrayList<>();
        int outQuads = 0;
        int inputQuads = 0;
        QuadInfo info = new QuadInfo();

        for (Source source : sources) {
            ByteBuffer src = source.vertices().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int quads = source.vertexCount() / 4;
            for (int q = 0; q < quads; q++) {
                inputQuads++;
                int base = q * 4 * STRIDE;
                if (classify(src, base, source.xOff(), source.yOff(), source.zOff(), info)) {
                    GroupKey key = new GroupKey(info.axis, info.normalSign, Float.floatToIntBits(info.plane),
                            info.color, info.boundsMin, info.boundsMax, info.orientation);
                    Group group = groups.get(key);
                    if (group == null) {
                        group = new Group(key, info);
                        groups.put(key, group);
                        order.add(group);
                    }
                    group.add(info.cellA, info.cellB);
                } else {
                    // Copy through with the section offset applied.
                    int outBase = outQuads * 4 * STRIDE;
                    for (int v = 0; v < 4; v++) {
                        int s = base + v * STRIDE;
                        int d = outBase + v * STRIDE;
                        for (int i = 0; i < STRIDE; i += 4) {
                            dst.putInt(d + i, src.getInt(s + i));
                        }
                        dst.putFloat(d, src.getFloat(s) + source.xOff());
                        dst.putFloat(d + 4, src.getFloat(s + 4) + source.yOff());
                        dst.putFloat(d + 8, src.getFloat(s + 8) + source.zOff());
                    }
                    outQuads++;
                }
            }
        }

        int merged = 0;
        for (Group group : order) {
            outQuads = group.emit(dst, outQuads, tileUv);
            merged++;
        }
        return new Merged(outQuads, inputQuads, merged);
    }

    /** Scratch description of one classified quad. */
    private static final class QuadInfo {
        int axis;
        int normalSign;
        float plane;
        int color;
        int boundsMin;
        int boundsMax;
        int orientation;
        int cellA;
        int cellB;
        // Corner of each vertex in the first-seen order: aHi, bHi, uHi, vHi packed per vertex.
        float uMin, uMax, vMin, vMax;
        float nx, ny, nz;
    }

    private record GroupKey(int axis, int normalSign, int planeBits, int color,
                            int boundsMin, int boundsMax, int orientation) {
    }

    private static boolean classify(ByteBuffer v, int base, int xOff, int yOff, int zOff, QuadInfo out) {
        float nx = v.getFloat(base + 12);
        float ny = v.getFloat(base + 16);
        float nz = v.getFloat(base + 20);
        int axis;
        float sign;
        if (nx != 0 && ny == 0 && nz == 0) {
            axis = 0;
            sign = nx;
        } else if (ny != 0 && nx == 0 && nz == 0) {
            axis = 1;
            sign = ny;
        } else if (nz != 0 && nx == 0 && ny == 0) {
            axis = 2;
            sign = nz;
        } else {
            return false;
        }
        if (Math.abs(Math.abs(sign) - 1.0f) > 1.0e-3f) {
            return false;
        }
        int color = v.getInt(base + 32);
        int bMin = v.getInt(base + 36);
        int bMax = v.getInt(base + 40);
        if (bMin == 0 && bMax == 0) {
            return false;
        }
        for (int i = 1; i < 4; i++) {
            int o = base + i * STRIDE;
            if (v.getInt(o + 32) != color || v.getInt(o + 36) != bMin || v.getInt(o + 40) != bMax
                    || v.getFloat(o + 12) != nx || v.getFloat(o + 16) != ny || v.getFloat(o + 20) != nz) {
                return false;
            }
        }

        float uLo = (bMin & 0xFFFF) / 65535.0f;
        float vLo = (bMin >>> 16) / 65535.0f;
        float uHi = (bMax & 0xFFFF) / 65535.0f;
        float vHi = (bMax >>> 16) / 65535.0f;
        float uEps = Math.abs(uHi - uLo) * UV_EPSILON_FRACTION + 1.0e-6f;
        float vEps = Math.abs(vHi - vLo) * UV_EPSILON_FRACTION + 1.0e-6f;

        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;
        float[] pos = new float[12];
        for (int i = 0; i < 4; i++) {
            int o = base + i * STRIDE;
            pos[i * 3] = v.getFloat(o) + xOff;
            pos[i * 3 + 1] = v.getFloat(o + 4) + yOff;
            pos[i * 3 + 2] = v.getFloat(o + 8) + zOff;
        }
        float plane = pos[axis];
        for (int i = 1; i < 4; i++) {
            if (pos[i * 3 + axis] != plane) {
                return false;
            }
        }
        float aMin = Math.min(Math.min(pos[a], pos[3 + a]), Math.min(pos[6 + a], pos[9 + a]));
        float bMinPos = Math.min(Math.min(pos[b], pos[3 + b]), Math.min(pos[6 + b], pos[9 + b]));
        if (!isLattice(aMin) || !isLattice(bMinPos)) {
            return false;
        }
        int orientation = 0;
        int seenCorners = 0;
        for (int i = 0; i < 4; i++) {
            float pa = pos[i * 3 + a] - aMin;
            float pb = pos[i * 3 + b] - bMinPos;
            int aHi = Math.abs(pa - 1.0f) < LATTICE_EPSILON ? 1 : Math.abs(pa) < LATTICE_EPSILON ? 0 : -1;
            int bHi = Math.abs(pb - 1.0f) < LATTICE_EPSILON ? 1 : Math.abs(pb) < LATTICE_EPSILON ? 0 : -1;
            if (aHi < 0 || bHi < 0) {
                return false;
            }
            float u = v.getFloat(base + i * STRIDE + 24);
            float w = v.getFloat(base + i * STRIDE + 28);
            int uBit;
            if (Math.abs(u - uHi) <= uEps) {
                uBit = 1;
            } else if (Math.abs(u - uLo) <= uEps) {
                uBit = 0;
            } else {
                return false;
            }
            int vBit;
            if (Math.abs(w - vHi) <= vEps) {
                vBit = 1;
            } else if (Math.abs(w - vLo) <= vEps) {
                vBit = 0;
            } else {
                return false;
            }
            if (uHi == uLo && uBit == 1 || vHi == vLo && vBit == 1) {
                return false;
            }
            seenCorners |= 1 << (aHi * 2 + bHi);
            orientation |= (aHi | bHi << 1 | uBit << 2 | vBit << 3) << (i * 4);
        }
        if (seenCorners != 0xF) {
            return false;
        }
        out.axis = axis;
        out.normalSign = sign > 0 ? 1 : -1;
        out.plane = plane;
        out.color = color;
        out.boundsMin = bMin;
        out.boundsMax = bMax;
        out.orientation = orientation;
        out.cellA = (int) Math.round(aMin);
        out.cellB = (int) Math.round(bMinPos);
        out.uMin = uLo;
        out.uMax = uHi;
        out.vMin = vLo;
        out.vMax = vHi;
        out.nx = nx;
        out.ny = ny;
        out.nz = nz;
        return true;
    }

    private static boolean isLattice(float value) {
        return Math.abs(value - Math.rint(value)) < LATTICE_EPSILON;
    }

    /** Cells of one (plane, normal, colour, bounds, orientation) group. */
    private static final class Group {
        final GroupKey key;
        final int axis;
        final float plane;
        final float nx, ny, nz;
        final int color, boundsMin, boundsMax, orientation;
        final float uMin, uMax, vMin, vMax;
        int[] cells = new int[16];
        int count;
        int minA = Integer.MAX_VALUE, minB = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE, maxB = Integer.MIN_VALUE;

        Group(GroupKey key, QuadInfo info) {
            this.key = key;
            this.axis = info.axis;
            this.plane = info.plane;
            this.nx = info.nx;
            this.ny = info.ny;
            this.nz = info.nz;
            this.color = info.color;
            this.boundsMin = info.boundsMin;
            this.boundsMax = info.boundsMax;
            this.orientation = info.orientation;
            this.uMin = info.uMin;
            this.uMax = info.uMax;
            this.vMin = info.vMin;
            this.vMax = info.vMax;
        }

        void add(int a, int b) {
            if (count == cells.length) {
                cells = java.util.Arrays.copyOf(cells, count * 2);
            }
            cells[count++] = (a + PACK_BIAS) << 16 | (b + PACK_BIAS);
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }

        int emit(ByteBuffer dst, int quadIndex, boolean tileUv) {
            int width = maxA - minA + 1;
            int height = maxB - minB + 1;
            boolean[] grid = new boolean[width * height];
            for (int i = 0; i < count; i++) {
                int a = (cells[i] >>> 16) - PACK_BIAS;
                int b = (cells[i] & 0xFFFF) - PACK_BIAS;
                grid[(b - minB) * width + (a - minA)] = true;
            }
            for (int b = 0; b < height; b++) {
                for (int a = 0; a < width; a++) {
                    if (!grid[b * width + a]) {
                        continue;
                    }
                    int runA = 1;
                    while (a + runA < width && grid[b * width + a + runA]) {
                        runA++;
                    }
                    int runB = 1;
                    boolean grow = true;
                    while (grow && b + runB < height) {
                        for (int i = 0; i < runA; i++) {
                            if (!grid[(b + runB) * width + a + i]) {
                                grow = false;
                                break;
                            }
                        }
                        if (grow) {
                            runB++;
                        }
                    }
                    for (int j = 0; j < runB; j++) {
                        for (int i = 0; i < runA; i++) {
                            grid[(b + j) * width + a + i] = false;
                        }
                    }
                    writeRect(dst, quadIndex++, minA + a, minB + b, runA, runB, tileUv);
                }
            }
            return quadIndex;
        }

        private void writeRect(ByteBuffer dst, int quadIndex, int a0, int b0, int runA, int runB,
                               boolean tileUv) {
            int a = axis == 0 ? 1 : 0;
            int b = axis == 2 ? 1 : 2;
            // Which texture axis follows which spatial axis, from the first vertex pair.
            boolean uFollowsA = uFollowsA();
            for (int i = 0; i < 4; i++) {
                int bits = (orientation >>> (i * 4)) & 0xF;
                int aHi = bits & 1;
                int bHi = (bits >>> 1) & 1;
                int uBit = (bits >>> 2) & 1;
                int vBit = (bits >>> 3) & 1;
                int o = (quadIndex * 4 + i) * STRIDE;
                float[] p = new float[3];
                p[axis] = plane;
                p[a] = a0 + (aHi == 1 ? runA : 0);
                p[b] = b0 + (bHi == 1 ? runB : 0);
                for (int k = 0; k < STRIDE; k += 4) {
                    dst.putInt(o + k, 0);
                }
                dst.putFloat(o, p[0]);
                dst.putFloat(o + 4, p[1]);
                dst.putFloat(o + 8, p[2]);
                dst.putFloat(o + 12, nx);
                dst.putFloat(o + 16, ny);
                dst.putFloat(o + 20, nz);
                float u = uBit == 1 ? uMax : uMin;
                float w = vBit == 1 ? vMax : vMin;
                if (tileUv) {
                    int nu = uFollowsA ? runA : runB;
                    int nv = uFollowsA ? runB : runA;
                    if (uBit == 1) {
                        u += (nu - 1) * (uMax - uMin);
                    }
                    if (vBit == 1) {
                        w += (nv - 1) * (vMax - vMin);
                    }
                }
                dst.putFloat(o + 24, u);
                dst.putFloat(o + 28, w);
                dst.putInt(o + 32, color);
                dst.putInt(o + 36, boundsMin);
                dst.putInt(o + 40, boundsMax);
            }
        }

        /** True when the texture's u axis varies with the first spatial axis (otherwise with the second). */
        private boolean uFollowsA() {
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    int bi = (orientation >>> (i * 4)) & 0xF;
                    int bj = (orientation >>> (j * 4)) & 0xF;
                    boolean sameB = ((bi >>> 1) & 1) == ((bj >>> 1) & 1);
                    boolean differA = (bi & 1) != (bj & 1);
                    if (sameB && differA) {
                        return ((bi >>> 2) & 1) != ((bj >>> 2) & 1);
                    }
                }
            }
            return true;
        }
    }
}
