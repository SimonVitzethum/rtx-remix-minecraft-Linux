/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Far terrain source for chunks the client does not have (integrated server only): one column (surface height + colour)
 * per 2^lg block cell, generated from the world generator's density noise ({@code ChunkGenerator.getBaseHeight}) and
 * the biome source, without ever generating or loading a chunk. Cells are grouped in tiles of 64 x 64 cells
 * (256 / 512 / 1024 blocks for lg = 2 / 3 / 4), generated asynchronously on low priority threads, nearest first, and
 * cached on disk (one small file per tile, ~16 KB), so a revisit costs a file read.
 *
 * <p>Colour is an approximation of the top block (biome grass / water colour, sand for beaches and deserts, snow when
 * cold, stone on high mountains, terracotta in badlands); trees, structures and caves are not represented.
 */
final class FarLodSource implements FarTileSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarLodSource.class);
    static final int TILE_CELLS = 64;
    static final int MIN_LG = 2, MAX_LG = 4;
    /** Marks a column without terrain (no solid block in the world). */
    static final short EMPTY = Short.MIN_VALUE;
    private static final int MAGIC = 0x464C4F44, VERSION = 2;

    /** One tile: {@code top[j * 64 + i]} = world Y of the surface (top face), {@code rgb} = RGB565. */
    static final class Tile {
        final short[] top = new short[TILE_CELLS * TILE_CELLS];
        final short[] rgb = new short[TILE_CELLS * TILE_CELLS];
        /** Changes whenever the content changed (cache sources); part of the column signature. */
        long version;
    }

    private final ChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessorProxy heights;
    private final int seaLevel;
    private final Path dir;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> queued = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final Thread[] threads;
    private final ThreadLocal<BiomeResolver> resolvers;
    private final ServerLevel level;
    private volatile boolean closed;
    private volatile int centerX, centerZ; // block coordinates of the player, for the priority of new jobs

    final AtomicLong generated = new AtomicLong();
    final AtomicLong loadedFromDisk = new AtomicLong();
    final AtomicLong columnNanos = new AtomicLong();
    final AtomicLong columns = new AtomicLong();
    /** Increments whenever a tile became available. */
    final AtomicInteger epoch = new AtomicInteger();

    private record LevelHeightAccessorProxy(int minY, int height) {}

    private record Job(long key, int lg, int tx, int tz, double priority) implements Comparable<Job> {
        @Override
        public int compareTo(Job o) {
            return Double.compare(priority, o.priority);
        }
    }

    FarLodSource(ServerLevel level, Path dir, int threadCount) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.random = level.getChunkSource().randomState();
        this.heights = new LevelHeightAccessorProxy(level.getMinY(), level.getHeight());
        this.seaLevel = level.getSeaLevel();
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("FarLod: cannot create {}", dir, e);
        }
        resolvers = ThreadLocal.withInitial(() ->
                generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)));
        threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(this::run, "RemixFarLod-" + i);
            threads[i].setDaemon(true);
            threads[i].setPriority(Thread.MIN_PRIORITY);
            threads[i].start();
        }
    }

    static long key(int lg, int tx, int tz) {
        return ((long) lg << 56) | ((long) (tx & 0xFFFFFFF) << 28) | (long) (tz & 0xFFFFFFF);
    }

    @Override
    public void setCenter(int blockX, int blockZ) {
        centerX = blockX;
        centerZ = blockZ;
    }

    int sea() { return seaLevel; }

    @Override
    public int pending() { return queue.size(); }

    /** The tile when available, else null and (once) a generation job is queued. */
    @Override
    public Tile get(int lg, int tx, int tz) {
        long k = key(lg, tx, tz);
        Tile t = tiles.get(k);
        if (t != null) return t;
        if (queued.putIfAbsent(k, Boolean.TRUE) == null) {
            double size = TILE_CELLS << lg;
            double dx = (tx + 0.5) * size - centerX, dz = (tz + 0.5) * size - centerZ;
            queue.add(new Job(k, lg, tx, tz, Math.max(Math.abs(dx), Math.abs(dz))));
        }
        return null;
    }

    @Override
    public boolean has(int lg, int tx, int tz) {
        return tiles.containsKey(key(lg, tx, tz));
    }

    @Override
    public void trim(int maxBlocks) {
        if (tiles.size() < 600) return;
        tiles.keySet().removeIf(k -> {
            int lg = (int) (k >>> 56);
            int tx = (int) ((k >> 28) & 0xFFFFFFF), tz = (int) (k & 0xFFFFFFF);
            if (tx >= 0x8000000) tx -= 0x10000000;
            if (tz >= 0x8000000) tz -= 0x10000000;
            double size = TILE_CELLS << lg;
            double d = Math.max(Math.abs((tx + 0.5) * size - centerX), Math.abs((tz + 0.5) * size - centerZ));
            if (d > maxBlocks + size) {
                queued.remove(k);
                return true;
            }
            return false;
        });
    }

    @Override
    public void close() {
        closed = true;
        for (Thread t : threads) t.interrupt();
    }

    @Override
    public long generatedCount() { return generated.get(); }

    @Override
    public long diskCount() { return loadedFromDisk.get(); }

    private void run() {
        while (!closed) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                Tile t = readFile(job);
                if (t != null) {
                    loadedFromDisk.incrementAndGet();
                } else {
                    t = generate(job);
                    writeFile(job, t);
                    generated.incrementAndGet();
                }
                tiles.put(job.key, t);
                epoch.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.error("FarLod tile {} failed", job, t);
                queued.remove(job.key);
            }
        }
    }

    private Path file(Job j) {
        return dir.resolve("t" + j.lg + "_" + j.tx + "_" + j.tz + ".bin");
    }

    private Tile readFile(Job j) {
        Path f = file(j);
        if (!Files.isRegularFile(f)) return null;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            Tile t = new Tile();
            for (int i = 0; i < t.top.length; i++) t.top[i] = in.readShort();
            for (int i = 0; i < t.rgb.length; i++) t.rgb[i] = in.readShort();
            return t;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeFile(Job j, Tile t) {
        Path f = file(j);
        Path tmp = dir.resolve(f.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            for (short s : t.top) out.writeShort(s);
            for (short s : t.rgb) out.writeShort(s);
        } catch (IOException e) {
            LOGGER.warn("FarLod: cannot write {}", tmp, e);
            return;
        }
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("FarLod: cannot move {}", tmp, e);
        }
    }

    private Tile generate(Job j) {
        long t0 = System.nanoTime();
        Tile t = new Tile();
        int size = 1 << j.lg;
        BiomeResolver resolver = resolvers.get();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cj = 0; cj < TILE_CELLS; cj++) {
            for (int ci = 0; ci < TILE_CELLS; ci++) {
                int x = j.tx * TILE_CELLS * size + ci * size + size / 2;
                int z = j.tz * TILE_CELLS * size + cj * size + size / 2;
                int idx = cj * TILE_CELLS + ci;
                int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                if (floor <= heights.minY()) {
                    t.top[idx] = EMPTY;
                    continue;
                }
                boolean water = floor <= seaLevel;
                int top = water ? seaLevel : floor;
                t.top[idx] = (short) top;
                Holder<Biome> biome = resolver.getNoiseBiome(x >> 2, Math.min(floor, 300) >> 2, z >> 2);
                pos.set(x, top, z);
                t.rgb[idx] = rgb565(colour(biome, x, z, floor, water, pos));
            }
        }
        columnNanos.addAndGet(System.nanoTime() - t0);
        columns.addAndGet((long) TILE_CELLS * TILE_CELLS);
        return t;
    }

    private int colour(Holder<Biome> holder, int x, int z, int floor, boolean water, BlockPos pos) {
        Biome biome = holder.value();
        if (water) {
            int w = biome.getWaterColor();
            int depth = seaLevel - floor;
            float f = Math.max(0.3f, 1f - depth / 70f) * 0.85f;
            if (holder.is(BiomeTags.IS_RIVER) || depth < 3) f = 0.9f;
            return scale(w, f);
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            int band = Math.floorMod(floor / 3, 5);
            return new int[]{0xB5663A, 0xD4A076, 0x9A5233, 0xCB8A5E, 0xA9744F}[band];
        }
        if (holder.is(Biomes.DESERT) || holder.is(BiomeTags.IS_BEACH)) return 0xD9CE9C;
        boolean cold = biome.coldEnoughToSnow(pos, seaLevel);
        if (cold) return 0xF1F5F7;
        if (holder.is(BiomeTags.IS_MOUNTAIN) && floor > 120) return floor > 200 ? 0xE9EDEF : 0x7E7E7E;
        int grass = biome.getGrassColor(x, z);
        if (holder.is(BiomeTags.IS_FOREST) || holder.is(BiomeTags.IS_TAIGA) || holder.is(BiomeTags.IS_JUNGLE)) {
            return scale(biome.getFoliageColor(), 0.8f);
        }
        return scale(grass, 0.85f);
    }

    static int scale(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** RGB888 to RGB565 (never 0, so 0 can stay "no colour"). */
    static short rgb565(int rgb) {
        int r = (rgb >> 19) & 31, g = (rgb >> 10) & 63, b = (rgb >> 3) & 31;
        int v = (r << 11) | (g << 5) | b;
        return (short) Math.max(v, 1);
    }
}
