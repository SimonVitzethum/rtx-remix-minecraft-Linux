/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Version-1 affine quad descriptors consumed by Remix's GPU geometry decoder. Minecraft still selects baked/Fabric
 * model quads, assets, UVs and tints. This encoding generates topology and expanded RT vertices on the Remix device.
 * Non-affine, negative-zero, subnormal and nonfinite positions keep the ordinary capture path. Normals and all UV
 * corner bit patterns are copied unchanged. Compact origins include section Y, matching column-local RT geometry.
 */
public final class CompactQuadCapture {
    public static final int STRIDE = 96;
    private static volatile boolean enabled;
    private static final AtomicLong compactQuads = new AtomicLong();
    private static final AtomicLong cpuQuads = new AtomicLong();
    private static final AtomicLong cpuLodQuads = new AtomicLong();

    private CompactQuadCapture() {}

    /** Called by the render lifecycle only after the matching native API has initialized. */
    public static void configure(boolean active) {
        enabled = active && Boolean.parseBoolean(System.getProperty("rtxremix.terrain.gpuQuads", "true"));
    }

    public static boolean enabled() { return enabled; }
    public static long compactQuads() { return compactQuads.get(); }
    public static long cpuQuads() { return cpuQuads.get(); }
    public static long cpuLodQuads() { return cpuLodQuads.get(); }

    static void record(long compact, long cpu) {
        compactQuads.addAndGet(compact);
        cpuQuads.addAndGet(cpu);
    }

    /** Writes one descriptor, or returns false without changing the target when exact reconstruction is impossible. */
    static boolean tryWrite(ByteBuffer target, float[] positions, int positionOffset, float[] uv, int uvOffset,
                            float nx, float ny, float nz, int color, int boundsMin, int boundsMax, int sectionY) {
        for (int axis = 0; axis < 3; axis++) {
            float p0 = translated(positions[positionOffset + axis], axis, sectionY);
            float p1 = translated(positions[positionOffset + 3 + axis], axis, sectionY);
            float p2 = translated(positions[positionOffset + 6 + axis], axis, sectionY);
            float p3 = translated(positions[positionOffset + 9 + axis], axis, sectionY);
            float edgeU = p1 - p0;
            float edgeV = p3 - p0;
            if (!stablePosition(p0) || !stablePosition(p1) || !stablePosition(p2) || !stablePosition(p3) ||
                    !stablePosition(edgeU) || !stablePosition(edgeV) ||
                    !same(p0 + edgeU, p1) || !same((p0 + edgeU) + edgeV, p2) || !same(p0 + edgeV, p3)) {
                return false;
            }
        }
        int start = target.position();
        for (int axis = 0; axis < 3; axis++) putRaw(target, translated(positions[positionOffset + axis], axis, sectionY));
        target.putInt(boundsMin);
        for (int axis = 0; axis < 3; axis++) {
            putRaw(target, translated(positions[positionOffset + 3 + axis], axis, sectionY)
                    - translated(positions[positionOffset + axis], axis, sectionY));
        }
        target.putInt(boundsMax);
        for (int axis = 0; axis < 3; axis++) {
            putRaw(target, translated(positions[positionOffset + 9 + axis], axis, sectionY)
                    - translated(positions[positionOffset + axis], axis, sectionY));
        }
        target.putInt(color);
        putRaw(target, nx); putRaw(target, ny); putRaw(target, nz); target.putInt(0);
        for (int i = 0; i < 8; i++) putRaw(target, uv[uvOffset + i]);
        if (target.position() - start != STRIDE) throw new AssertionError("Compact quad layout drift");
        return true;
    }

    private static float translated(float position, int axis, int sectionY) {
        return sectionY == 0 ? position : position + (axis == 1 ? sectionY : 0);
    }

    private static boolean stablePosition(float position) {
        int bits = Float.floatToRawIntBits(position);
        return Float.isFinite(position) && bits != 0x80000000 &&
                ((bits & 0x7f800000) != 0 || (bits & 0x007fffff) == 0);
    }

    private static boolean same(float first, float second) {
        return Float.floatToRawIntBits(first) == Float.floatToRawIntBits(second);
    }

    private static void putRaw(ByteBuffer output, float value) { output.putInt(Float.floatToRawIntBits(value)); }

    /** Explicit CPU reference for the existing distant greedy LOD path; never used for normal near construction. */
    public static void expandForLod(ByteBuffer input, int count, ByteBuffer output) {
        for (int quad = 0; quad < count; quad++) {
            int base = quad * STRIDE;
            for (int corner = 0; corner < 4; corner++) {
                float x = coordinate(input, base, 0, corner);
                float y = coordinate(input, base, 1, corner);
                float z = coordinate(input, base, 2, corner);
                RemixMeshSurface.putVertex(output, x, y, z, input.getFloat(base + 48), input.getFloat(base + 52),
                        input.getFloat(base + 56), input.getFloat(base + 64 + corner * 8),
                        input.getFloat(base + 68 + corner * 8), input.getInt(base + 44), input.getInt(base + 12), input.getInt(base + 28));
            }
        }
        cpuLodQuads.addAndGet(count);
    }

    private static float coordinate(ByteBuffer input, int base, int axis, int corner) {
        float position = input.getFloat(base + axis * 4);
        if (corner == 1 || corner == 2) position = position + input.getFloat(base + 16 + axis * 4);
        if (corner == 2 || corner == 3) position = position + input.getFloat(base + 32 + axis * 4);
        return position;
    }
}
