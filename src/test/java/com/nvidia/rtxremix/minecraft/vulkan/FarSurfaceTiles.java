/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/** Immutable tile references covering one 256-block subtree. Handles tiles both smaller and larger than the subtree. */
record FarSurfaceTiles(int lg, int sx, int sz, FarLodSource.Tile[] tiles) {
    static FarSurfaceTiles request(FarTileSource source, int lg, int sx, int sz, boolean generate) {
        int span = Math.max(1, 4 >> lg);
        int blocks = FarLodSource.TILE_CELLS << lg;
        int tx = Math.floorDiv(sx * 256, blocks), tz = Math.floorDiv(sz * 256, blocks);
        FarLodSource.Tile[] tiles = new FarLodSource.Tile[span * span];
        boolean complete = true;
        for (int z = 0; z < span; z++) {
            for (int x = 0; x < span; x++) {
                FarLodSource.Tile tile = generate || source.has(lg, tx + x, tz + z) ? source.get(lg, tx + x, tz + z) : null;
                tiles[z * span + x] = tile;
                complete &= tile != null;
            }
        }
        return complete ? new FarSurfaceTiles(lg, sx, sz, tiles) : null;
    }

    long signature() {
        long signature = lg;
        for (FarLodSource.Tile tile : tiles) signature = signature * 1000003L + tile.version;
        return signature;
    }

    /** Copies to subtree-relative heights; runs on workers, reading only immutable published tiles. */
    void copy(short[] top, short[] rgb, int minY) {
        int n = 256 >> lg;
        int tileBlocks = FarLodSource.TILE_CELLS << lg;
        int span = Math.max(1, 4 >> lg);
        int ox = Math.floorMod(sx * 256, tileBlocks) >> lg;
        int oz = Math.floorMod(sz * 256, tileBlocks) >> lg;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int cellX = ox + x, cellZ = oz + z;
                FarLodSource.Tile tile = tiles[(cellZ / 64) * span + cellX / 64];
                int index = (cellZ % 64) * 64 + cellX % 64;
                short height = tile.top[index];
                top[z * n + x] = height == FarLodSource.EMPTY ? height : (short) (height - minY);
                rgb[z * n + x] = tile.rgb[index];
            }
        }
    }
}
