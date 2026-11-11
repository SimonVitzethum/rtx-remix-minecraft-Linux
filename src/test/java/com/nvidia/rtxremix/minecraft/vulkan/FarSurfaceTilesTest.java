/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FarSurfaceTilesTest {
    private static FarLodSource.Tile tile(int height, int rgb) {
        var tile = new FarLodSource.Tile();
        Arrays.fill(tile.top, (short) height);
        Arrays.fill(tile.rgb, (short) rgb);
        return tile;
    }

    @Test
    void joinsFourFineTilesWithoutCrossingTheirArraysAtNegativeCoordinates() {
        var a = tile(-20, 1);
        var b = tile(-10, 2);
        var c = tile(0, 3);
        var d = tile(10, 4);
        a.top[0] = FarLodSource.EMPTY;
        var surface = new FarSurfaceTiles(1, -1, -2, new FarLodSource.Tile[]{a, b, c, d});
        short[] heights = new short[128 * 128], rgb = new short[heights.length];
        surface.copy(heights, rgb, -64);
        assertEquals(FarLodSource.EMPTY, heights[0]);
        assertEquals(44, heights[1]);
        assertEquals(54, heights[64]);
        assertEquals(64, heights[64 * 128]);
        assertEquals(74, heights[127 * 128 + 127]);
        assertEquals(4, rgb[127 * 128 + 127]);
        long before = surface.signature();
        c.version++;
        assertNotEquals(before, surface.signature());
    }

    @Test
    void selectsTheCorrectQuadrantOfACoarseSharedTile() {
        var tile = tile(70, 1);
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) tile.top[z * 64 + x] = (short) (z * 64 + x);
        }
        var surface = new FarSurfaceTiles(4, -1, -1, new FarLodSource.Tile[]{tile});
        short[] top = new short[16 * 16], rgb = new short[top.length];
        surface.copy(top, rgb, 0);
        assertEquals(48 * 64 + 48, top[0]);
        assertEquals(63 * 64 + 63, top[255]);
    }
}
