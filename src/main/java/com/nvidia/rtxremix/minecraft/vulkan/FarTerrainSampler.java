/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Stateless procedural samples from the existing generator; never creates or retains a world chunk or tile. */
final class FarTerrainSampler {
    private final ChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final int seaLevel;
    private record Context(BiomeResolver resolver, BlockPos.MutableBlockPos position) {}
    private static final int[] BADLAND_COLOURS = {0xB5663A, 0xD4A076, 0x9A5233, 0xCB8A5E, 0xA9744F};
    private final ThreadLocal<Context> contexts;

    /** Stable cache identity for the resource data used by biome colour lookup, including a pack change on restart. */
    static long paletteIdentity() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var resources = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            byte[] buffer = new byte[8192];
            for (String name : new String[]{"grass", "foliage", "dry_foliage"}) {
                var resource = resources.getResource(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "minecraft", "textures/colormap/" + name + ".png"));
                if (resource.isEmpty()) continue;
                digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (var input = resource.get().open()) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
                }
            }
            return java.nio.ByteBuffer.wrap(digest.digest()).getLong();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint the far-terrain colour palette", failure);
        }
    }

    FarTerrainSampler(ServerLevel level) {
        generator = level.getChunkSource().getGenerator();
        random = level.getChunkSource().randomState();
        heights = LevelHeightAccessor.create(level.getMinY(), level.getHeight());
        seaLevel = level.getSeaLevel();
        contexts = ThreadLocal.withInitial(() -> new Context(
                generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)),
                new BlockPos.MutableBlockPos()));
    }

    /** One packed message: absolute signed surface Y in high16, RGB565 in low16; no surface uses Short.MIN_VALUE. */
    int sample(int x, int z) {
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random);
        if (floor <= heights.getMinY()) return (Short.MIN_VALUE & 0xFFFF) << 16;
        boolean water = floor <= seaLevel;
        int top = water ? seaLevel : floor;
        Context context = contexts.get();
        Holder<Biome> biome = context.resolver.getNoiseBiome(x >> 2, Math.min(floor, 300) >> 2, z >> 2);
        int rgb = colour(biome, x, z, floor, water, context.position.set(x, top, z));
        return ((top & 0xFFFF) << 16) | (FarVoxelFormat.rgb565(rgb) & 0xFFFF);
    }

    private int colour(Holder<Biome> holder, int x, int z, int floor, boolean water, BlockPos pos) {
        Biome biome = holder.value();
        if (water) {
            int w = biome.getWaterColor();
            int depth = seaLevel - floor;
            float f = Math.max(0.3f, 1f - depth / 70f) * 0.85f;
            if (holder.is(BiomeTags.IS_RIVER) || depth < 3) f = 0.9f;
            return FarVoxelFormat.scale(w, f);
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            int band = Math.floorMod(floor / 3, 5);
            return BADLAND_COLOURS[band];
        }
        if (holder.is(Biomes.DESERT) || holder.is(BiomeTags.IS_BEACH)) return 0xD9CE9C;
        boolean cold = biome.coldEnoughToSnow(pos, seaLevel);
        if (cold) return 0xF1F5F7;
        if (holder.is(BiomeTags.IS_MOUNTAIN) && floor > 120) return floor > 200 ? 0xE9EDEF : 0x7E7E7E;
        int grass = biome.getGrassColor(x, z);
        if (holder.is(BiomeTags.IS_FOREST) || holder.is(BiomeTags.IS_TAIGA) || holder.is(BiomeTags.IS_JUNGLE)) {
            return FarVoxelFormat.scale(biome.getFoliageColor(), 0.8f);
        }
        return FarVoxelFormat.scale(grass, 0.85f);
    }

}
