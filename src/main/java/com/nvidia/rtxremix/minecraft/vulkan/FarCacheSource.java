/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Received surface source, optionally over procedural integrated-server terrain: remembers every chunk this client
 * received. Per chunk 16 x 16 columns of {top face height, RGB565 of the top block with biome tint}, 1 KB, grouped in regions of
 * 32 x 32 chunks and kept deflated on disk per server and dimension ({@code rtxremix-farcache/<server>/<dimension>/r_x_z.bin}). Tiles
 * (the same 64 x 64 cell tiles at 2 / 4 / 8 / 16 blocks per cell as {@link FarLodSource}) are averaged from those columns on a worker
 * thread; a tile is rebuilt under a 500 ms cadence gate when a chunk inside it was recorded again.
 *
 * <p>Memory is bounded: at most {@link #MAX_REGIONS} regions resident (farthest from the player written out and dropped), tiles
 * trimmed by distance. Disk is bounded by the {@code cacheMb} setting over the whole cache directory (oldest files deleted).
 * Content revisions coalesce surface records; an unload also offers the last received state.
 */
final class FarCacheSource implements FarTileSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarCacheSource.class);
    private static final int MAGIC = 0x46434348, VERSION = 1;
    private static final int MAX_REGIONS = 48;
    private static final long REBUILD_MIN_MS = 500;

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
        @Override public int compareTo(TileJob o) { int order = Double.compare(priority, o.priority);
            if (order == 0) order = Integer.compare(o.lg, lg);
            return order != 0 ? order : Long.compare(key, o.key); }
    }

    private final Path dir, diskRoot;
    private final long diskLimitBytes;
    private final FarTileSource fallback;
    private final java.util.Set<Long> missingRegions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Region> regions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, FarLodSource.Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastBuilt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> queued = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<TileJob> tileQueue = new PriorityBlockingQueue<>();
    private final LinkedBlockingQueue<FarChunkSnapshot> recordQueue = new LinkedBlockingQueue<>(32);
    private final Thread recorder, tileThread;
    private volatile boolean closed;
    private volatile int centerX, centerZ;
    private long lastFlushMs;
    private final CompletableFuture<Void> flushed = new CompletableFuture<>();

    final AtomicLong recorded = new AtomicLong();
    final AtomicLong built = new AtomicLong();
    final AtomicLong fromDisk = new AtomicLong();

    FarCacheSource(Path root, Path namespace, int diskLimitMb, FarTileSource fallback, CompletableFuture<Void> previousFlush) {
        this.diskRoot = root.toAbsolutePath().normalize();
        this.dir = diskRoot.resolve(namespace).normalize();
        if (!dir.startsWith(diskRoot)) throw new IllegalArgumentException("Surface cache namespace escapes its root");
        this.diskLimitBytes = diskLimitMb * 1024L * 1024L;
        this.fallback = fallback;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("FarCache: cannot create {}", dir, e);
        }
        recorder = new Thread(() -> {
            previousFlush.join();
            try { recordLoop(); }
            finally { flushed.complete(null); }
        }, "RemixFarCacheRec");
        tileThread = new Thread(() -> {
            previousFlush.join();
            tileLoop();
        }, "RemixFarCacheTiles");
        for (Thread t : new Thread[]{recorder, tileThread}) {
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    // ---- recording (render thread hands immutable snapshots over, worker samples them) ----

    /** Queues a chunk for sampling (dropped silently when the queue is full; the caller offers it again later). */
    boolean record(FarChunkSnapshot chunk) {
        return !closed && recordQueue.offer(chunk);
    }

    int recordBacklog() { return recordQueue.size(); }
    @Override public CompletableFuture<Void> flushed() { return flushed; }

    private void recordLoop() {
        try {
            while (!closed) {
                try {
                    FarChunkSnapshot c = recordQueue.poll(1000, TimeUnit.MILLISECONDS);
                    if (c != null) store(c.sample());
                    housekeeping(false);
                } catch (InterruptedException e) {
                    break;
                } catch (RuntimeException t) {
                    LOGGER.error("FarCache recorder", t);
                }
            }
            FarChunkSnapshot remaining;
            while ((remaining = recordQueue.poll()) != null) store(remaining.sample());
        } finally {
            // Tile builds can evict dirty regions while reading. Release every writer even if the final drain failed.
            close();
            boolean interrupted = false;
            while (tileThread.isAlive()) {
                try { tileThread.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (fallback != null) fallback.flushed().join();
            housekeeping(true);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    synchronized void store(FarChunkSnapshot.Surface surface) {
        int cx = surface.cx(), cz = surface.cz();
        short[] top = surface.top(), rgb = surface.rgb();
        long rk = regionKey(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        Region r = region(rk, true);
        int slot = (Math.floorMod(cz, 32) << 5) | Math.floorMod(cx, 32);
        if (java.util.Arrays.equals(r.top(slot), top) && java.util.Arrays.equals(r.rgb(slot), rgb)) return;
        r.set(slot, top, rgb);
        epochs.computeIfAbsent(rk, k -> new AtomicLong()).incrementAndGet();
        recorded.incrementAndGet();
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
                AtomicLong e = epochs.get(regionKey(rx, rz));
                if (e != null) sum += e.get();
            }
        }
        // Noise tiles become available asynchronously, independently of received chunk edits.
        if (fallback != null && fallback.get(lg, tx, tz) != null) sum ^= 0x4000000000000000L;
        return sum;
    }

    @Override
    public FarLodSource.Tile get(int lg, int tx, int tz) {
        long k = tileKey(lg, tx, tz);
        FarLodSource.Tile t = tiles.get(k);
        long now = System.currentTimeMillis();
        if (t != null && t.version == stamp(lg, tx, tz)) return t.usable ? t : null;
        Long lb = lastBuilt.get(k);
        if (t == null || lb == null || now - lb > REBUILD_MIN_MS) {
            if (!closed && queued.size() < 4096 && queued.putIfAbsent(k, Boolean.TRUE) == null) {
                double size = FarLodSource.TILE_CELLS << lg;
                double dx = (tx + 0.5) * size - centerX, dz = (tz + 0.5) * size - centerZ;
                tileQueue.add(new TileJob(k, lg, tx, tz, Math.max(0, Math.max(Math.abs(dx), Math.abs(dz)) - size * 0.5)));
            }
        }
        return t != null && t.usable ? t : null;
    }

    @Override
    public boolean has(int lg, int tx, int tz) {
        FarLodSource.Tile tile = tiles.get(tileKey(lg, tx, tz));
        return tile != null && tile.usable || fallback != null && fallback.has(lg, tx, tz);
    }

    @Override
    public void setCenter(int blockX, int blockZ) {
        centerX = blockX;
        centerZ = blockZ;
        if (fallback != null) fallback.setCenter(blockX, blockZ);
    }

    @Override
    public void trim(int maxBlocks) {
        if (fallback != null) fallback.trim(maxBlocks);
        missingRegions.removeIf(key -> Math.max(Math.abs(((int) (key >> 32) + 0.5) * 512 - centerX),
                Math.abs((key.intValue() + 0.5) * 512 - centerZ)) > maxBlocks + 512);
        epochs.keySet().removeIf(key -> Math.max(Math.abs(((int) (key >> 32) + 0.5) * 512 - centerX),
                Math.abs((key.intValue() + 0.5) * 512 - centerZ)) > maxBlocks + 2048);
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

    @Override public int pending() { return tileQueue.size() + recordQueue.size() + (fallback == null ? 0 : fallback.pending()); }
    @Override public long generatedCount() { return built.get() + (fallback == null ? 0 : fallback.generatedCount()); }
    @Override public long diskCount() { return fromDisk.get(); }

    @Override
    public void close() {
        closed = true;
        tileQueue.clear();
        // The recorder drains and flushes before the next session opens this cache directory. Its bounded poll wakes it.

        tileThread.interrupt();
        if (fallback != null) fallback.close();
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
                // A record or fallback publication during the build invalidates the result. Keep the old tile.
                if (!closed && st == stamp(job.lg, job.tx, job.tz)) {
                    t.version = st;
                    tiles.put(job.key, t);
                    lastBuilt.put(job.key, System.currentTimeMillis());
                    built.incrementAndGet();
                }
            } catch (Throwable t) {
                LOGGER.error("FarCache tile {} failed", job, t);
            } finally {
                queued.remove(job.key);
            }
        }
    }

    private FarLodSource.Tile build(TileJob j) {
        FarLodSource.Tile tile = new FarLodSource.Tile();
        FarLodSource.Tile base = fallback == null ? null : fallback.get(j.lg, j.tx, j.tz);
        tile.usable = base != null;
        if (base == null) java.util.Arrays.fill(tile.top, FarLodSource.EMPTY);
        else {
            System.arraycopy(base.top, 0, tile.top, 0, tile.top.length);
            System.arraycopy(base.rgb, 0, tile.rgb, 0, tile.rgb.length);
        }
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
                    synchronized (this) {
                        Region r = region(regionKey(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32)), false);
                        int slot = (Math.floorMod(cz, 32) << 5) | Math.floorMod(cx, 32);
                        if (r == null) { ct = null; cr = null; }
                        else { ct = r.top[slot]; cr = r.rgb[slot]; }
                    }
                }
                if (ct == null) continue;
                // An integrated source needs complete generated coverage before replacing a coarser tile.
                if (fallback == null) tile.usable = true;
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
                int idx = cj * FarLodSource.TILE_CELLS + ci;
                if (n == 0) {
                    tile.top[idx] = FarLodSource.EMPTY;
                    tile.rgb[idx] = 0;
                    continue;
                }
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
        if (!create && missingRegions.contains(rk)) return null;
        if (create) missingRegions.remove(rk);
        Region loaded = read(rk);
        if (loaded != null) fromDisk.incrementAndGet();
        else if (create) loaded = new Region(rk);
        else {
            if (missingRegions.size() < 8192) missingRegions.add(rk);
            return null;
        }
        if (regions.size() >= MAX_REGIONS && !evictRegion()) {
            throw new IllegalStateException("Far-field surface cache is full and cannot persist a dirty region");
        }
        regions.put(rk, loaded);
        return loaded;
    }

    /** Called under the source's I/O monitor, also held through a record's region lookup and mutation. */
    private boolean evictRegion() {
        Region far = null;
        double farthest = -1;
        for (Region region : regions.values()) {
            double distance = Math.max(Math.abs(((int) (region.key >> 32) + 0.5) * 512 - centerX),
                    Math.abs(((int) region.key + 0.5) * 512 - centerZ));
            if (distance > farthest || distance == farthest && (far == null || region.key < far.key)) {
                farthest = distance;
                far = region;
            }
        }
        if (far != null && (!far.dirty || write(far))) {
            regions.remove(far.key, far);
            return true;
        }
        return false;
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

    private boolean write(Region r) {
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
            synchronized (r) { r.dirty = true; }
            LOGGER.warn("FarCache: cannot write {}", tmp, e);
            return false;
        }
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            synchronized (r) { r.dirty = true; }
            LOGGER.warn("FarCache: cannot move {}", tmp, e);
            return false;
        }
    }

    /** Flushes dirty regions (one per second, all on shutdown), drops far regions above the memory cap, trims the disk. */
    private synchronized void housekeeping(boolean all) {
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
        } catch (IOException | java.io.UncheckedIOException e) {
            LOGGER.warn("FarCache: trim failed", e);
        }
    }

    private void trimDisk() {
        trimDisk(diskRoot, diskLimitBytes);
    }
}
