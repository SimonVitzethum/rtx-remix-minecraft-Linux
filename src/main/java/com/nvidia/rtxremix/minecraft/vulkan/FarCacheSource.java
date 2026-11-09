/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Far terrain source for multiplayer (no integrated server to ask): remembers the surface of every chunk this client ever
 * received. Per chunk 16 x 16 columns of {top face height, RGB565 of the top block with biome tint}, 1 KB, grouped in regions of
 * 32 x 32 chunks and kept deflated on disk per server and dimension ({@code rtxremix-farcache/<server>/<dimension>/r_x_z.bin}). Tiles
 * (the same 64 x 64 cell tiles at 4 / 8 / 16 blocks per cell as {@link FarLodSource}) are averaged from those columns on a worker
 * thread; a tile is rebuilt (at most every few seconds) when a chunk inside it was recorded again.
 *
 * <p>Memory is bounded: at most {@link #MAX_REGIONS} regions resident (farthest from the player written out and dropped), tiles
 * trimmed by distance. Disk is bounded by the {@code cacheMb} setting over the whole cache directory (oldest files deleted).
 * A chunk is recorded when the client first has it and once more when it leaves, so far terrain stays after the server stops sending it.
 */
final class FarCacheSource implements FarTileSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarCacheSource.class);
    private static final int MAGIC = 0x46434348, VERSION = 1;
    private static final int MAX_REGIONS = 48;
    private static final long REBUILD_MIN_MS = 3000;
    private static final int KIND_PLAIN = 0, KIND_GRASS = 1, KIND_FOLIAGE = 2, KIND_WATER = 3;

    /** 32 x 32 chunk slots; the arrays are immutable once stored. */
    private static final class Region {
        final long key;
        final short[][] top = new short[1024][];
        final short[][] rgb = new short[1024][];
        boolean dirty;
        Region(long key) { this.key = key; }
        synchronized void set(int slot, short[] t, short[] c) { top[slot] = t; rgb[slot] = c; dirty = true; }
        synchronized short[] top(int slot) { return top[slot]; }
        synchronized short[] rgb(int slot) { return rgb[slot]; }
    }

    private record TileJob(long key, int lg, int tx, int tz, double priority) implements Comparable<TileJob> {
        @Override public int compareTo(TileJob o) { return Double.compare(priority, o.priority); }
    }

    private final Path dir;
    private final long diskLimitBytes;
    private final int minY;
    private final ConcurrentHashMap<Long, Region> regions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, FarLodSource.Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastBuilt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> queued = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<TileJob> tileQueue = new PriorityBlockingQueue<>();
    private final LinkedBlockingQueue<LevelChunk> recordQueue = new LinkedBlockingQueue<>(2048);
    private final Thread recorder, tileThread;
    private volatile boolean closed;
    private volatile int centerX, centerZ;
    private long lastFlushMs;

    final AtomicLong recorded = new AtomicLong();
    final AtomicLong built = new AtomicLong();
    final AtomicLong fromDisk = new AtomicLong();

    FarCacheSource(Path dir, int diskLimitMb, int minY) {
        this.dir = dir;
        this.diskLimitBytes = diskLimitMb * 1024L * 1024L;
        this.minY = minY;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("FarCache: cannot create {}", dir, e);
        }
        recorder = new Thread(this::recordLoop, "RemixFarCacheRec");
        tileThread = new Thread(this::tileLoop, "RemixFarCacheTiles");
        for (Thread t : new Thread[]{recorder, tileThread}) {
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    // ---- recording (render thread hands chunks over, worker samples them) ----

    /** Queues a chunk for sampling (dropped silently when the queue is full; the caller offers it again later). */
    boolean record(LevelChunk chunk) {
        return recordQueue.offer(chunk);
    }

    int recordBacklog() { return recordQueue.size(); }

    private void recordLoop() {
        while (!closed) {
            try {
                LevelChunk c = recordQueue.poll(1000, TimeUnit.MILLISECONDS);
                if (c != null) {
                    try {
                        store(c);
                    } catch (RuntimeException e) {
                        // chunk mutated or unloaded while sampled: next time
                    }
                }
                housekeeping(false);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOGGER.error("FarCache recorder", t);
            }
        }
        housekeeping(true);
    }

    private void store(LevelChunk chunk) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        short[] top = new short[256], rgb = new short[256];
        sample(chunk, top, rgb, minY);
        long rk = regionKey(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        Region r = region(rk, true);
        int slot = (Math.floorMod(cz, 32) << 5) | Math.floorMod(cx, 32);
        if (java.util.Arrays.equals(r.top(slot), top) && java.util.Arrays.equals(r.rgb(slot), rgb)) return;
        r.set(slot, top, rgb);
        epochs.computeIfAbsent(rk, k -> new AtomicInteger()).incrementAndGet();
        recorded.incrementAndGet();
    }

    /** Surface of one chunk: top face height (EMPTY when the column has no solid block) and RGB565 per column, index z * 16 + x. */
    static void sample(LevelChunk chunk, short[] top, short[] rgb, int minY) {
        int bx0 = chunk.getPos().getMinBlockX(), bz0 = chunk.getPos().getMinBlockZ();
        IdentityHashMap<BlockState, Integer> classes = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = z * 16 + x;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int cls = 0;
                BlockState st = null;
                for (int steps = 0; y >= minY && steps < 32; steps++, y--) {
                    st = chunk.getBlockState(pos.set(bx0 + x, y, bz0 + z));
                    Integer c = classes.get(st);
                    if (c == null) {
                        c = FarField.classify(st);
                        classes.put(st, c);
                    }
                    cls = c;
                    if (cls != 0) break;
                }
                if (cls == 0 || y < minY) {
                    top[idx] = FarLodSource.EMPTY;
                    continue;
                }
                int bx = bx0 + x, bz = bz0 + z;
                Biome biome = chunk.getNoiseBiome(bx >> 2, Math.max(y, minY) >> 2, bz >> 2).value();
                int rgbv = cls & 0xFFFFFF;
                switch ((cls >> 24) & 0x7F) {
                    case KIND_GRASS -> rgbv = FarLodSource.scale(biome.getGrassColor(bx, bz), 0.85f);
                    case KIND_FOLIAGE -> rgbv = FarLodSource.scale(biome.getFoliageColor(), 0.8f);
                    case KIND_WATER -> {
                        int depth = 0;
                        for (int yy = y - 1; yy >= minY && depth < 40; yy--, depth++) {
                            if (chunk.getBlockState(pos.set(bx, yy, bz)).getFluidState().isEmpty()) break;
                        }
                        rgbv = FarLodSource.scale(biome.getWaterColor(), Math.max(0.3f, 1f - depth / 70f) * 0.85f);
                    }
                    default -> { }
                }
                top[idx] = (short) (y + 1);
                rgb[idx] = FarLodSource.rgb565(rgbv);
            }
        }
    }

    // ---- tiles ----

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private static long tileKey(int lg, int tx, int tz) {
        return FarLodSource.key(lg, tx, tz);
    }

    /** Sum of the record epochs of the regions the tile overlaps. */
    private long stamp(int lg, int tx, int tz) {
        int blocks = FarLodSource.TILE_CELLS << lg;
        int x0 = tx * blocks, z0 = tz * blocks;
        long sum = 0;
        for (int rz = Math.floorDiv(z0, 512); rz <= Math.floorDiv(z0 + blocks - 1, 512); rz++) {
            for (int rx = Math.floorDiv(x0, 512); rx <= Math.floorDiv(x0 + blocks - 1, 512); rx++) {
                AtomicInteger e = epochs.get(regionKey(rx, rz));
                if (e != null) sum += e.get();
            }
        }
        return sum;
    }

    @Override
    public FarLodSource.Tile get(int lg, int tx, int tz) {
        long k = tileKey(lg, tx, tz);
        FarLodSource.Tile t = tiles.get(k);
        long now = System.currentTimeMillis();
        if (t != null && t.version == stamp(lg, tx, tz)) return t;
        Long lb = lastBuilt.get(k);
        if (t == null || lb == null || now - lb > REBUILD_MIN_MS) {
            if (queued.putIfAbsent(k, Boolean.TRUE) == null) {
                double size = FarLodSource.TILE_CELLS << lg;
                double dx = (tx + 0.5) * size - centerX, dz = (tz + 0.5) * size - centerZ;
                tileQueue.add(new TileJob(k, lg, tx, tz, Math.max(Math.abs(dx), Math.abs(dz))));
            }
        }
        return t;
    }

    @Override
    public boolean has(int lg, int tx, int tz) {
        return tiles.containsKey(tileKey(lg, tx, tz));
    }

    @Override
    public void setCenter(int blockX, int blockZ) {
        centerX = blockX;
        centerZ = blockZ;
    }

    @Override
    public void trim(int maxBlocks) {
        if (tiles.size() < 600) return;
        tiles.keySet().removeIf(k -> {
            int lg = (int) (k >>> 56);
            int tx = (int) ((k >> 28) & 0xFFFFFFF), tz = (int) (k & 0xFFFFFFF);
            if (tx >= 0x8000000) tx -= 0x10000000;
            if (tz >= 0x8000000) tz -= 0x10000000;
            double size = FarLodSource.TILE_CELLS << lg;
            double d = Math.max(Math.abs((tx + 0.5) * size - centerX), Math.abs((tz + 0.5) * size - centerZ));
            if (d > maxBlocks + size) {
                lastBuilt.remove(k);
                return true;
            }
            return false;
        });
    }

    @Override public int pending() { return tileQueue.size() + recordQueue.size(); }
    @Override public long generatedCount() { return built.get(); }
    @Override public long diskCount() { return fromDisk.get(); }

    @Override
    public void close() {
        closed = true;
        recorder.interrupt();
        tileThread.interrupt();
    }

    private void tileLoop() {
        while (!closed) {
            TileJob job;
            try {
                job = tileQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                long st = stamp(job.lg, job.tx, job.tz);
                FarLodSource.Tile t = build(job);
                t.version = st;
                tiles.put(job.key, t);
                lastBuilt.put(job.key, System.currentTimeMillis());
                built.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.error("FarCache tile {} failed", job, t);
            } finally {
                queued.remove(job.key);
            }
        }
    }

    private FarLodSource.Tile build(TileJob j) {
        FarLodSource.Tile tile = new FarLodSource.Tile();
        java.util.Arrays.fill(tile.top, FarLodSource.EMPTY);
        int size = 1 << j.lg;
        long lastChunk = Long.MIN_VALUE;
        short[] ct = null, cr = null;
        for (int cj = 0; cj < FarLodSource.TILE_CELLS; cj++) {
            for (int ci = 0; ci < FarLodSource.TILE_CELLS; ci++) {
                int bx = j.tx * FarLodSource.TILE_CELLS * size + ci * size;
                int bz = j.tz * FarLodSource.TILE_CELLS * size + cj * size;
                int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
                long ck = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (ck != lastChunk) {
                    lastChunk = ck;
                    Region r = region(regionKey(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32)), false);
                    int slot = (Math.floorMod(cz, 32) << 5) | Math.floorMod(cx, 32);
                    ct = r == null ? null : r.top(slot);
                    cr = r == null ? null : r.rgb(slot);
                }
                if (ct == null) continue;
                int lx = bx & 15, lz = bz & 15;
                int n = 0, sumTop = 0, sr = 0, sg = 0, sb = 0;
                for (int z = lz; z < lz + size; z++) {
                    for (int x = lx; x < lx + size; x++) {
                        short t = ct[z * 16 + x];
                        if (t == FarLodSource.EMPTY) continue;
                        int c = cr[z * 16 + x];
                        sumTop += t;
                        sr += ((c >> 11) & 31) * 255 / 31;
                        sg += ((c >> 5) & 63) * 255 / 63;
                        sb += (c & 31) * 255 / 31;
                        n++;
                    }
                }
                if (n == 0) continue;
                int idx = cj * FarLodSource.TILE_CELLS + ci;
                tile.top[idx] = (short) Math.round(sumTop / (float) n);
                tile.rgb[idx] = FarLodSource.rgb565(((sr / n) << 16) | ((sg / n) << 8) | (sb / n));
            }
        }
        return tile;
    }

    // ---- regions on disk ----

    private Path file(long rk) {
        return dir.resolve("r_" + (int) (rk >> 32) + "_" + (int) rk + ".bin");
    }

    private Region region(long rk, boolean create) {
        Region r = regions.get(rk);
        if (r != null) return r;
        return regions.computeIfAbsent(rk, k -> {
            Region loaded = read(k);
            if (loaded != null) {
                fromDisk.incrementAndGet();
                return loaded;
            }
            return create ? new Region(k) : null;
        });
    }

    private Region read(long rk) {
        Path f = file(rk);
        if (!Files.isRegularFile(f)) return null;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            Region r = new Region(rk);
            byte[] present = new byte[1024];
            in.readFully(present);
            for (int i = 0; i < 1024; i++) {
                if (present[i] == 0) continue;
                short[] t = new short[256], c = new short[256];
                for (int k = 0; k < 256; k++) t[k] = in.readShort();
                for (int k = 0; k < 256; k++) c[k] = in.readShort();
                r.top[i] = t;
                r.rgb[i] = c;
            }
            return r;
        } catch (IOException e) {
            LOGGER.warn("FarCache: cannot read {}", f, e);
            return null;
        }
    }

    private void write(Region r) {
        short[][] t, c;
        synchronized (r) {
            t = r.top.clone();
            c = r.rgb.clone();
            r.dirty = false;
        }
        Path f = file(r.key);
        Path tmp = dir.resolve(f.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            byte[] present = new byte[1024];
            for (int i = 0; i < 1024; i++) present[i] = (byte) (t[i] != null ? 1 : 0);
            out.write(present);
            for (int i = 0; i < 1024; i++) {
                if (t[i] == null) continue;
                for (short s : t[i]) out.writeShort(s);
                for (short s : c[i]) out.writeShort(s);
            }
        } catch (IOException e) {
            LOGGER.warn("FarCache: cannot write {}", tmp, e);
            return;
        }
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("FarCache: cannot move {}", tmp, e);
        }
    }

    /** Flushes dirty regions (one per second, all on shutdown), drops far regions above the memory cap, trims the disk. */
    private void housekeeping(boolean all) {
        long now = System.currentTimeMillis();
        if (!all && now - lastFlushMs < 1000) return;
        lastFlushMs = now;
        int flushed = 0;
        for (Region r : regions.values()) {
            boolean d;
            synchronized (r) { d = r.dirty; }
            if (d && (all || flushed < 1)) {
                write(r);
                flushed++;
            }
        }
        if (!all && regions.size() > MAX_REGIONS) {
            Region far = null;
            double fd = -1;
            for (Region r : regions.values()) {
                double dx = ((int) (r.key >> 32) + 0.5) * 512 - centerX, dz = ((int) r.key + 0.5) * 512 - centerZ;
                double d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d > fd) { fd = d; far = r; }
            }
            if (far != null) {
                write(far);
                regions.remove(far.key);
            }
        }
        if (all) trimDisk();
    }

    /** Deletes the oldest files of the whole cache (all servers) until it fits the limit. */
    static void trimDisk(Path root, long limit) {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".bin")) {
                    files.add(p);
                    total += Files.size(p);
                }
            }
            if (total <= limit) return;
            files.sort(java.util.Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
            }));
            for (Path p : files) {
                if (total <= limit) break;
                total -= Files.size(p);
                Files.delete(p);
            }
        } catch (IOException e) {
            LOGGER.warn("FarCache: trim failed", e);
        }
    }

    private void trimDisk() {
        Path root = dir.getParent() == null ? dir : dir.getParent().getParent() == null ? dir.getParent() : dir.getParent().getParent();
        trimDisk(root, diskLimitBytes);
    }
}
