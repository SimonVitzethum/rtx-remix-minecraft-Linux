/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/**
 * Coarse terrain for chunks the client does not have: tiles of 64 x 64 cells of 2^lg blocks (lg 2..4) with the surface height
 * and an RGB565 colour per cell. Implemented by {@link FarLodSource} (integrated server, generated from the world generator) and
 * {@link FarCacheSource} (multiplayer, built from the chunks this client received earlier).
 */
interface FarTileSource {
    /** The tile when available, else null and (once) a job is queued. A stale tile may be returned while a rebuild is queued. */
    FarLodSource.Tile get(int lg, int tx, int tz);

    boolean has(int lg, int tx, int tz);

    /** Player position in blocks, the priority of new jobs. */
    void setCenter(int blockX, int blockZ);

    /** Drops tiles farther than the given block distance from the centre (memory bound). */
    void trim(int maxBlocks);

    int pending();

    void close();

    long generatedCount();

    long diskCount();
}
