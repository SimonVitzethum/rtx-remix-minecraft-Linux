/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.IdentityHashMap;

/** Privately owned palette copies shared by exact voxel and persistent surface builders. No worker reads a live chunk. */
final class FarChunkSnapshot {
    final int cx, cz, minY;
    private final LevelChunkSection[] sections;
    private final int[] heights;

    /** Cheap content signature; no block or heightmap scan. Must be read on the chunk's owner thread. */
    static long signature(LevelChunk chunk) {
        long signature = System.identityHashCode(chunk) * 1000003L
                + com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current();
        for (LevelChunkSection section : chunk.getSections()) {
            signature = signature * 1000003L + System.identityHashCode(section);
            if (section != null) signature = signature * 1000003L + ((FarSectionRevision) section).rtxremix$farRevision();
        }
        return signature;
    }

    /** Capture on the client thread. The section copy includes both block and biome palettes. */
    FarChunkSnapshot(LevelChunk chunk) {
        cx = chunk.getPos().x();
        cz = chunk.getPos().z();
        minY = chunk.getMinY();
        LevelChunkSection[] source = chunk.getSections();
        sections = new LevelChunkSection[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] != null) sections[i] = source[i].copy();
        }
        heights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) heights[z * 16 + x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        }
    }

    private BlockState state(int x, int y, int z) {
        int section = Math.floorDiv(y - minY, 16);
        return section < 0 || section >= sections.length || sections[section] == null ? Blocks.AIR.defaultBlockState()
                : sections[section].getBlockState(x, y & 15, z);
    }

    private Biome biome(int x, int y, int z) {
        int section = Math.clamp((y - minY) >> 4, 0, sections.length - 1);
        return sections[section].getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2).value();
    }

    private int colour(int classified, int x, int y, int z) {
        int kind = (classified >> 24) & 0x7F;
        if (kind == FarField.KIND_PLAIN) return classified & 0xFFFFFF;
        Biome biome = biome(x, y, z);
        return switch (kind) {
            case FarField.KIND_GRASS -> FarLodSource.scale(biome.getGrassColor(cx * 16 + x, cz * 16 + z), 0.85f);
            case FarField.KIND_FOLIAGE -> FarLodSource.scale(biome.getFoliageColor(), 0.8f);
            case FarField.KIND_WATER -> FarLodSource.scale(biome.getWaterColor(), 0.85f);
            default -> classified & 0xFFFFFF;
        };
    }

    /** Surface top face and biome-tinted colour per block column, including the last received block edits. */
    record Surface(int cx, int cz, short[] top, short[] rgb) {}

    Surface sample() {
        short[] top = new short[256], rgb = new short[256];
        IdentityHashMap<BlockState, Integer> classes = new IdentityHashMap<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int y = heights[index] - 1;
                int classified = 0;
                for (; y >= minY; y--) {
                    classified = classes.computeIfAbsent(state(x, y, z), FarField::classify);
                    if (classified != 0) break;
                }
                if (classified == 0) {
                    top[index] = FarLodSource.EMPTY;
                    continue;
                }
                int colour = colour(classified, x, y, z);
                if (((classified >> 24) & 0x7F) == FarField.KIND_WATER) {
                    int depth = 0;
                    while (depth < 40 && y - depth - 1 >= minY && !state(x, y - depth - 1, z).getFluidState().isEmpty()) depth++;
                    colour = FarLodSource.scale(colour, Math.max(0.3f, 1f - depth / 70f));
                }
                top[index] = (short) (y + 1);
                rgb[index] = FarLodSource.rgb565(colour);
            }
        }
        return new Surface(cx, cz, top, rgb);
    }

    /** Exact RGB565 sections from the world bottom; null = air, length 1 = uniform, else 4096 voxels. */
    short[][] voxelize() {
        IdentityHashMap<BlockState, Integer> classes = new IdentityHashMap<>();
        short[][] out = new short[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            short[] data = new short[4096];
            boolean uniform = true;
            short first = 0;
            int solid = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int classified = classes.computeIfAbsent(section.getBlockState(x, y, z), FarField::classify);
                        short value = classified == 0 ? 0 : FarLodSource.rgb565(colour(classified, x, minY + i * 16 + y, z));
                        int index = (y << 8) | (z << 4) | x;
                        data[index] = value;
                        if (index == 0) first = value;
                        else if (value != first) uniform = false;
                        if (value != 0) solid++;
                    }
                }
            }
            if (solid != 0) out[i] = uniform ? new short[]{first} : data;
        }
        return out;
    }
}
