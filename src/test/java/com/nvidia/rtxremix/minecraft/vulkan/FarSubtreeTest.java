/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FarSubtreeTest {
    /** Read the published wire representation, independently of the builder's spatial traversal. */
    static int voxel(FarSubtree.Result result, int x, int y, int z) {
        int reference = result.roots()[y >> 8];
        for (int bit = 7; bit >= 0 && reference != 0 && (reference & FarFieldDag.LEAF) == 0; bit--) {
            int child = ((x >> bit) & 1) | (((y >> bit) & 1) << 1) | (((z >> bit) & 1) << 2);
            reference = result.nodes()[reference * 8 + child];
        }
        return reference == 0 ? 0 : reference & 0xFFFF;
    }

    @Test
    void preservesIntegerHeightAndColourAtEveryLodIncludingNegativeWorldHeights() {
        Random random = new Random(0xFACADE);
        for (int lg = 1; lg <= 4; lg++) {
            int n = 256 >> lg;
            short[] top = new short[n * n], rgb = new short[n * n];
            for (int i = 0; i < top.length; i++) {
                // Includes EMPTY, slab boundaries and heights that coarse vertical rounding used to lose.
                top[i] = i % 11 == 0 ? FarLodSource.EMPTY : (short) random.nextInt(1, 385);
                rgb[i] = (short) random.nextInt(1, 65536);
            }
            FarSubtree.Result result = FarSubtree.build(lg, top, rgb, null, 2);
            for (int i = 0; i < 3000; i++) {
                int x = random.nextInt(256), z = random.nextInt(256);
                int cell = (z >> lg) * n + (x >> lg);
                int height = top[cell];
                if (height == FarLodSource.EMPTY) {
                    assertEquals(0, voxel(result, x, random.nextInt(384), z));
                } else {
                    assertEquals(rgb[cell] & 0xFFFF, voxel(result, x, height - 1, z));
                    assertEquals(0, voxel(result, x, height, z));
                    int y = random.nextInt(height);
                    assertNotEquals(0, voxel(result, x, y, z));
                }
            }
            FarSubtree.Result repeat = FarSubtree.build(lg, top, rgb, null, 2);
            assertArrayEquals(result.nodes(), repeat.nodes());
            assertArrayEquals(result.roots(), repeat.roots());
        }
    }

    @Test
    void exactChunkOverridesSurfaceAndKeepsAirCavesAndMaterialEdits() {
        short[] top = new short[64 * 64], rgb = new short[top.length];
        Arrays.fill(top, (short) 150);
        Arrays.fill(rgb, (short) 0x7E0);
        short[][][] exact = new short[256][][];
        exact[0] = new short[24][];
        exact[0][8] = new short[4096];
        int index = (3 << 8) | (5 << 4) | 2;
        exact[0][8][index] = (short) 0xF800;
        var placed = FarSubtree.build(2, top, rgb, exact, 2);
        assertEquals(0xF800, voxel(placed, 2, 131, 5));
        assertEquals(0, voxel(placed, 3, 131, 5));
        assertEquals(0, voxel(placed, 2, 100, 5));
        assertEquals(0x7E0, voxel(placed, 17, 149, 5));
        exact[0][8][index] = 0;
        var broken = FarSubtree.build(2, top, rgb, exact, 2);
        assertEquals(0, voxel(broken, 2, 131, 5));
        assertEquals(0xF800, voxel(placed, 2, 131, 5), "published geometry is immutable");
    }

    @Test
    void shutdownInterruptCancelsBeforePublishingPartialGeometry() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> FarSubtree.build(4, null, null, null, 2));
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void flatTerrainStillSharesItsVolumeRatherThanRetainingFarVoxels() {
        short[] top = new short[128 * 128], rgb = new short[top.length];
        Arrays.fill(top, (short) 101);
        Arrays.fill(rgb, (short) 0x7E0);
        var result = FarSubtree.build(1, top, rgb, null, 2);
        assertTrue(result.nodeCount() < 40, "flat repeated surfaces must collapse in the DAG");
    }
}
