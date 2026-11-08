/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.lod;

/**
 * Distance at which a chunk column switches to the merged terrain mesh (see
 * {@link TerrainQuadMerger}), with hysteresis so a column does not flip back and forth while the
 * player stands near the boundary.
 */
public final class TerrainLod {
    public static final int DEFAULT_DISTANCE_CHUNKS = 12;
    public static final int MAX_DISTANCE_CHUNKS = 32;
    /** Chunks of margin on either side of the boundary before a column changes level. */
    static final double HYSTERESIS_CHUNKS = 1.5;

    private static volatile int distance = Math.max(0, Math.min(MAX_DISTANCE_CHUNKS,
            com.nvidia.rtxremix.minecraft.util.LodOverrides.get(
                    "rtxremix.terrain.lodDistance", DEFAULT_DISTANCE_CHUNKS)));

    private TerrainLod() {
    }

    /** Distance in chunks beyond which columns are merged; 0 disables the LOD. */
    public static int getDistance() {
        return distance;
    }

    public static void setDistance(int chunks) {
        distance = Math.max(0, Math.min(MAX_DISTANCE_CHUNKS, chunks));
    }

    /** Level for a column {@code chunkDistance} chunks from the player, given its current level. */
    public static int select(double chunkDistance, int currentLevel, int boundaryChunks) {
        if (boundaryChunks <= 0) {
            return 0;
        }
        if (currentLevel == 0) {
            return chunkDistance > boundaryChunks + HYSTERESIS_CHUNKS ? 1 : 0;
        }
        return chunkDistance < boundaryChunks - HYSTERESIS_CHUNKS ? 0 : 1;
    }
}
