/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.ByteBuffer;

/** Owner-thread encoding of existing gameplay chunks into bounded native change messages; no chunk copies or snapshots. */
final class FarChunkMessages {
    private final java.util.IdentityHashMap<net.minecraft.world.level.block.state.BlockState, Integer> classes = new java.util.IdentityHashMap<>();

    /** Content identity only; no block/heightmap scan or payload copy. Read on the chunk's owner thread. */
    static long signature(LevelChunk chunk) {
        long signature = System.identityHashCode(chunk) * 1000003L
                + com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current();
        for (var section : chunk.getSections()) {
            signature = signature * 1000003L + System.identityHashCode(section);
            if (section != null) signature = signature * 1000003L + ((FarSectionRevision) section).rtxremix$farRevision();
        }
        return signature;
    }

    private int classify(net.minecraft.world.level.block.state.BlockState state) {
        return classes.computeIfAbsent(state, FarField::classify);
    }

    private static int colour(LevelChunk chunk, int classified, int x, int y, int z) {
        int kind = (classified >> 24) & 0x7F;
        if (kind == FarField.KIND_PLAIN) return classified & 0xFFFFFF;
        var section = chunk.getSections()[Math.clamp((y - chunk.getMinY()) >> 4, 0, chunk.getSections().length - 1)];
        Biome biome = section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2).value();
        return switch (kind) {
            case FarField.KIND_GRASS -> FarVoxelFormat.scale(biome.getGrassColor(chunk.getPos().x() * 16 + x,
                    chunk.getPos().z() * 16 + z), 0.85f);
            case FarField.KIND_FOLIAGE -> FarVoxelFormat.scale(biome.getFoliageColor(), 0.8f);
            case FarField.KIND_WATER -> FarVoxelFormat.scale(biome.getWaterColor(), 0.85f);
            default -> classified & 0xFFFFFF;
        };
    }

    void exact(LevelChunk chunk, int sectionIndex, ByteBuffer message) {
        message.clear();
        var sections = chunk.getSections();
        var section = sectionIndex < sections.length ? sections[sectionIndex] : null;
        if (section == null || section.hasOnlyAir()) {
            for (int i = 0; i < 4096; i++) message.putShort((short) 0);
        } else {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int classified = classify(section.getBlockState(x, y, z));
                        message.putShort(classified == 0 ? 0 : FarVoxelFormat.rgb565(colour(chunk, classified,
                                x, chunk.getMinY() + sectionIndex * 16 + y, z)));
                    }
                }
            }
        }
        message.flip();
    }

    void surface(LevelChunk chunk, ByteBuffer message) {
        message.clear();
        var position = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), classified = 0;
                for (; y >= chunk.getMinY(); y--) {
                    position.set(x, y, z);
                    classified = classify(chunk.getBlockState(position));
                    if (classified != 0) break;
                }
                if (classified == 0) { message.putInt((Short.MIN_VALUE & 0xFFFF) << 16); continue; }
                int rgb = colour(chunk, classified, x, y, z);
                if (((classified >> 24) & 0x7F) == FarField.KIND_WATER) {
                    int depth = 0;
                    while (depth < 40 && y - depth - 1 >= chunk.getMinY()) {
                        position.set(x, y - depth - 1, z);
                        if (chunk.getBlockState(position).getFluidState().isEmpty()) break;
                        depth++;
                    }
                    rgb = FarVoxelFormat.scale(rgb, Math.max(0.3f, 1f - depth / 70f));
                }
                message.putInt(((y + 1) << 16) | (FarVoxelFormat.rgb565(rgb) & 0xFFFF));
            }
        }
        message.flip();
    }
}
