/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.lod;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainQuadMergerTest {
    private static final int STRIDE = TerrainQuadMerger.STRIDE;
    private static final int BOUNDS_MIN = pack(0.25f, 0.5f);
    private static final int BOUNDS_MAX = pack(0.375f, 0.625f);

    private static int pack(float x, float y) {
        return com.nvidia.rtxremix.HardcodedVertex.packUnorm2x16(x, y);
    }

    /** One +Y block face at cell (x, z), height y, full-sprite UVs. */
    private static void putTopFace(ByteBuffer b, int quad, int x, float y, int z, int color) {
        float[][] corners = {{x, z}, {x, z + 1}, {x + 1, z + 1}, {x + 1, z}};
        float[][] uvs = {{0.25f, 0.5f}, {0.25f, 0.625f}, {0.375f, 0.625f}, {0.375f, 0.5f}};
        for (int i = 0; i < 4; i++) {
            int o = (quad * 4 + i) * STRIDE;
            b.putFloat(o, corners[i][0]);
            b.putFloat(o + 4, y);
            b.putFloat(o + 8, corners[i][1]);
            b.putFloat(o + 12, 0);
            b.putFloat(o + 16, 1);
            b.putFloat(o + 20, 0);
            b.putFloat(o + 24, uvs[i][0]);
            b.putFloat(o + 28, uvs[i][1]);
            b.putInt(o + 32, color);
            b.putInt(o + 36, BOUNDS_MIN);
            b.putInt(o + 40, BOUNDS_MAX);
        }
    }

    private static ByteBuffer buffer(int quads) {
        return ByteBuffer.allocateDirect(quads * 4 * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static double area(ByteBuffer b, int quads) {
        double total = 0;
        for (int q = 0; q < quads; q++) {
            int o = q * 4 * STRIDE;
            double w = Math.abs(b.getFloat(o + 2 * STRIDE) - b.getFloat(o));
            double d = Math.abs(b.getFloat(o + 2 * STRIDE + 8) - b.getFloat(o + 8));
            total += w * d;
        }
        return total;
    }

    @Test
    void flatRegionMergesToOneQuadWithSameAreaNormalAndUvCorners() {
        ByteBuffer in = buffer(16);
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                putTopFace(in, z * 4 + x, x, 10.0f, z, 0xFF55AA33);
            }
        }
        ByteBuffer out = buffer(16);
        TerrainQuadMerger.Merged result = TerrainQuadMerger.merge(
                List.of(new TerrainQuadMerger.Source(in, 64, 0, 0, 0)), out, false);
        assertEquals(1, result.quads());
        assertEquals(16.0, area(out, 1), 1.0e-6);
        for (int i = 0; i < 4; i++) {
            int o = i * STRIDE;
            assertEquals(10.0f, out.getFloat(o + 4));
            assertEquals(1.0f, out.getFloat(o + 16));
            assertEquals(0xFF55AA33, out.getInt(o + 32));
            assertEquals(BOUNDS_MIN, out.getInt(o + 36));
            float u = out.getFloat(o + 24);
            assertTrue(u == 0.25f || u == 0.375f);
        }
        // Vertex order (winding) of the first input quad is kept: x0z0, x0z1, x1z1, x1z0.
        assertEquals(0.0f, out.getFloat(0));
        assertEquals(4.0f, out.getFloat(STRIDE + 8));
        assertEquals(4.0f, out.getFloat(2 * STRIDE));
    }

    @Test
    void differentHeightColourOrOffsetsDoNotMergeAndSectionOffsetsApply() {
        ByteBuffer in = buffer(4);
        putTopFace(in, 0, 0, 5.0f, 0, 0xFF000001);
        putTopFace(in, 1, 1, 5.0f, 0, 0xFF000002);   // other colour
        putTopFace(in, 2, 2, 6.0f, 0, 0xFF000001);   // other height
        putTopFace(in, 3, 3, 5.0f, 0, 0xFF000001);   // gap at x=2 keeps this separate
        ByteBuffer out = buffer(4);
        TerrainQuadMerger.Merged result = TerrainQuadMerger.merge(
                List.of(new TerrainQuadMerger.Source(in, 16, 16, 32, 0)), out, false);
        assertEquals(4, result.quads());
        assertEquals(4.0, area(out, 4), 1.0e-6);
        for (int q = 0; q < 4; q++) {
            assertTrue(out.getFloat(q * 4 * STRIDE + 4) >= 37.0f);
            assertTrue(out.getFloat(q * 4 * STRIDE) >= 16.0f);
        }
    }

    @Test
    void randomRegionKeepsAreaAndNeverOverlaps() {
        Random random = new Random(3);
        int quads = 0;
        ByteBuffer in = buffer(16 * 16);
        boolean[][] filled = new boolean[16][16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (random.nextFloat() < 0.7f) {
                    putTopFace(in, quads++, x, 3.0f, z, 0xFF123456);
                    filled[z][x] = true;
                }
            }
        }
        ByteBuffer out = buffer(quads);
        TerrainQuadMerger.Merged result = TerrainQuadMerger.merge(
                List.of(new TerrainQuadMerger.Source(in, quads * 4, 0, 0, 0)), out, false);
        assertEquals(quads, result.inputQuads());
        assertTrue(result.quads() < quads);
        assertEquals(quads, area(out, result.quads()), 1.0e-6);
        int[][] cover = new int[16][16];
        for (int q = 0; q < result.quads(); q++) {
            int o = q * 4 * STRIDE;
            int x0 = Math.round(Math.min(out.getFloat(o), out.getFloat(o + 2 * STRIDE)));
            int x1 = Math.round(Math.max(out.getFloat(o), out.getFloat(o + 2 * STRIDE)));
            int z0 = Math.round(Math.min(out.getFloat(o + 8), out.getFloat(o + 2 * STRIDE + 8)));
            int z1 = Math.round(Math.max(out.getFloat(o + 8), out.getFloat(o + 2 * STRIDE + 8)));
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    cover[z][x]++;
                }
            }
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(filled[z][x] ? 1 : 0, cover[z][x]);
            }
        }
    }

    @Test
    void tiledUvsRunPastTheBoundsOnePerMergedBlock() {
        ByteBuffer in = buffer(6);
        for (int x = 0; x < 6; x++) {
            putTopFace(in, x, x, 1.0f, 0, 0xFFFFFFFF);
        }
        ByteBuffer out = buffer(6);
        TerrainQuadMerger.Merged result = TerrainQuadMerger.merge(
                List.of(new TerrainQuadMerger.Source(in, 24, 0, 0, 0)), out, true);
        assertEquals(1, result.quads());
        float maxU = 0;
        for (int i = 0; i < 4; i++) {
            maxU = Math.max(maxU, out.getFloat(i * STRIDE + 24));
        }
        assertEquals(0.375f + 5 * 0.125f, maxU, 1.0e-6);
    }

    @Test
    void lodSelectionHasHysteresis() {
        assertEquals(0, TerrainLod.select(13.0, 0, 12));
        assertEquals(1, TerrainLod.select(14.0, 0, 12));
        assertEquals(1, TerrainLod.select(11.0, 1, 12));
        assertEquals(0, TerrainLod.select(10.0, 1, 12));
        assertEquals(0, TerrainLod.select(40.0, 1, 0));
    }
}
