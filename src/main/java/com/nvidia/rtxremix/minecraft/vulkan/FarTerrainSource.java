/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.VoxelDag;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk-backed received surfaces and native DAG pages. Java keeps file revisions and a bounded queue of small change
 * messages; it never caches surface tiles, region payloads, far chunk objects or DAG nodes. Procedural samples use
 * the existing generator without requesting chunks. Every building caller owns one temporary native workspace.
 */
final class FarTerrainSource implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FarTerrainSource.class);
    private record Record(int cx, int cz, ByteBuffer message) {}

    private final Path received, surfaces, pages;
    private final FarTerrainSampler sampler;
    private final boolean persistReceived;
    private final long diskLimit;
    private record Region(long revision, long[] present) {}
    private final Map<Long, Region> revisions = new ConcurrentHashMap<>();
    private final java.util.Set<Long> regionQueued = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Long> regionRequests = new ArrayBlockingQueue<>(4096);
    private final ArrayBlockingQueue<Record> records = new ArrayBlockingQueue<>(32);
    private final CompletableFuture<Void> previousFlush, flushed = new CompletableFuture<>();
    private final Thread recorder;
    private volatile boolean closed;
    private final Object diskLock = new Object();
    private int leases;
    private boolean recorderDone;
    private int centerX, centerZ;
    private long pageDiskBytes = -1, surfaceDiskBytes = -1;
    private final AtomicLong generated = new AtomicLong(), fromDisk = new AtomicLong();
    private final AtomicLong messageBytes = new AtomicLong(), samplingBytes = new AtomicLong();

    FarTerrainSource(Path received, Path generated, FarTerrainSampler sampler, boolean persistReceived,
                     int cacheMb, CompletableFuture<Void> previousFlush) throws IOException {
        this.received = received;
        this.surfaces = generated.resolve("stream-surfaces-v1");
        this.pages = generated.resolve("native-dag-v1");
        this.sampler = sampler;
        this.persistReceived = persistReceived;
        this.diskLimit = cacheMb * 1024L * 1024L;
        this.previousFlush = previousFlush;
        Files.createDirectories(received);
        Files.createDirectories(surfaces);
        Files.createDirectories(pages);
        recorder = new Thread(this::recordLoop, "RemixFarSurfaceDisk");
        recorder.setDaemon(true);
        recorder.setPriority(Thread.MIN_PRIORITY);
        recorder.start();
    }

    static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    long revision(int sx, int sz) {
        long region = key(Math.floorDiv(sx, 2), Math.floorDiv(sz, 2));
        Region value = revisions.get(region);
        if (value == null && regionQueued.add(region) && !regionRequests.offer(region)) regionQueued.remove(region);
        return value == null ? 0 : value.revision;
    }
    boolean hasData(int sx, int sz) {
        if (sampler != null) return true;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (receivedChunk(sx * 16 + x, sz * 16 + z)) return true;
        return false;
    }
    boolean hasProcedural() { return sampler != null; }
    boolean receivedChunk(int cx, int cz) {
        Region region = revisions.get(key(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32)));
        int slot = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
        return region != null && (region.present[slot >>> 6] & (1L << (slot & 63))) != 0;
    }
    int pending() { return records.size(); }
    boolean canRecord() { return !closed && records.remainingCapacity() > 0 && persistReceived; }
    long generatedCount() { return generated.get(); }
    long diskCount() { return fromDisk.get(); }
    long transientMessageBytes() { return messageBytes.get() + samplingBytes.get(); }
    CompletableFuture<Void> flushed() { return flushed; }

    synchronized void acquire() {
        if (closed) throw new IllegalStateException("Far source is closed");
        leases++;
    }

    synchronized void release() {
        if (leases == 0) throw new IllegalStateException("Far source released more than acquired");
        leases--;
        if (recorderDone && leases == 0) flushed.complete(null);
    }

    /** Transfers ownership of one 1024-byte native-allocated change message on success. */
    synchronized boolean record(int cx, int cz, ByteBuffer message) {
        if (closed || !persistReceived) return false;
        messageBytes.addAndGet(message.capacity());
        if (records.offer(new Record(cx, cz, message))) return true;
        messageBytes.addAndGet(-message.capacity());
        return false;
    }

    void setCenter(int blockX, int blockZ) { centerX = blockX; centerZ = blockZ; }

    /** Metadata remains bounded by the wanted extent; recorded content remains on disk. */
    void trim(int blocks) {
        revisions.keySet().removeIf(k -> Math.max(Math.abs(((int) (k >> 32) + 0.5) * 512 - centerX),
                Math.abs(((int) (long) k + 0.5) * 512 - centerZ)) > blocks + 1024);
    }

    private Path page(int sx, int sz, int lg, long signature) {
        return pages.resolve("d_" + lg + "_" + sx + "_" + sz + "_" + Long.toHexString(signature) + ".dag");
    }

    /** File handles retain only roots and file descriptors. Missing cache files are a normal miss. */
    long open(int sx, int sz, int lg, long signature) {
        previousFlush.join();
        synchronized (diskLock) {
            Path path = page(sx, sz, lg, signature);
            if (!Files.isRegularFile(path)) return 0;
            long result = VoxelDag.open(path.toString());
            fromDisk.incrementAndGet();
            return result;
        }
    }

    void save(long handle, int sx, int sz, int lg, long signature) {
        synchronized (diskLock) {
            Path file = page(sx, sz, lg, signature);
            try {
                long previous = Files.isRegularFile(file) ? Files.size(file) : 0;
                VoxelDag.save(handle, file.toString());
                pageDiskBytes = accountDisk(pages, file, previous, pageDiskBytes);
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
    }

    private long accountDisk(Path directory, Path file, long previous, long known) throws IOException {
        if (known < 0) {
            try (var files = Files.newDirectoryStream(directory)) {
                known = 0;
                for (Path path : files) if (Files.isRegularFile(path)) known += Files.size(path);
            }
        } else known += Files.size(file) - previous;
        return known > diskLimit ? trimDisk(directory, diskLimit) : known;
    }

    /** Emits one column's surface directly into a native workspace using a reusable bounded message buffer. */
    void surface(long handle, int sx, int sz, int lg, int minY, long palette, int colourGeneration) throws IOException {
        previousFlush.join();
        checkGeneration(colourGeneration);
        int n = 256 >> lg, cells = n * n;
        ByteBuffer message = DirectBufferPool.acquire(cells * 4).order(ByteOrder.nativeOrder());
        samplingBytes.addAndGet(message.capacity());
        Path file = surfaces.resolve("s_" + lg + "_" + sx + "_" + sz + "_" + Long.toHexString(palette) + ".bin");
        try {
            message.limit(cells * 4);
            if (sampler != null && Files.isRegularFile(file)) {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    while (message.hasRemaining()) {
                        if (channel.read(message) < 0) throw new IOException("Truncated procedural surface " + file);
                    }
                }
            } else {
                int size = 1 << lg;
                for (int z = 0; z < n; z++) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    checkGeneration(colourGeneration);
                    for (int x = 0; x < n; x++) {
                        int sample = sampler == null ? (Short.MIN_VALUE & 0xFFFF) << 16
                                : sampler.sample(sx * 256 + x * size + size / 2, sz * 256 + z * size + size / 2);
                        message.putInt(sample);
                    }
                }
                if (sampler != null) {
                    checkGeneration(colourGeneration);
                    message.flip();
                    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        while (message.hasRemaining()) channel.write(message);
                    }
                    synchronized (diskLock) {
                        long previous = Files.isRegularFile(file) ? Files.size(file) : 0;
                        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        surfaceDiskBytes = accountDisk(surfaces, file, previous, surfaceDiskBytes);
                    }
                }
            }
            checkGeneration(colourGeneration);
            message.position(0).limit(cells * 4);
            for (int i = 0; i < cells; i++) {
                int packed = message.getInt(i * 4), height = (short) (packed >>> 16);
                if (height != Short.MIN_VALUE) message.putInt(i * 4, ((height - minY) << 16) | (packed & 0xFFFF));
            }
            VoxelDag.surface(handle, 0, cells, message);
            VoxelDag.overlay(handle, received.toString(), sx, sz, minY);
            generated.incrementAndGet();
        } finally {
            samplingBytes.addAndGet(-message.capacity());
            DirectBufferPool.release(message);
        }
    }

    private static void checkGeneration(int expected) {
        if (com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current() != expected) {
            throw new java.util.concurrent.CancellationException("Far-terrain palette changed during sampling");
        }
    }

    private void recordLoop() {
        previousFlush.join();
        try {
            while (!closed || !records.isEmpty()) {
                Long region;
                int metadataBudget = 64;
                while (!closed && metadataBudget-- > 0 && (region = regionRequests.poll()) != null) {
                    int rx = (int) (region >> 32), rz = (int) (long) region;
                    Path file = received.resolve("r_" + rx + "_" + rz + ".bin");
                    try {
                        revisions.put(region, inspect(file));
                    } catch (IOException failure) { LOGGER.warn("Cannot inspect received region {}", file, failure); }
                    finally { regionQueued.remove(region); }
                }
                Record record;
                try { record = records.poll(regionRequests.isEmpty() ? 100 : 1, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { continue; }
                if (record == null) continue;
                try {
                    VoxelDag.recordSurface(received.toString(), record.cx, record.cz, record.message);
                    int rx = Math.floorDiv(record.cx, 32), rz = Math.floorDiv(record.cz, 32);
                    Path file = received.resolve("r_" + rx + "_" + rz + ".bin");
                    revisions.put(key(rx, rz), inspect(file));
                } catch (IOException | RuntimeException failure) {
                    LOGGER.error("Received far surface could not be persisted at {},{}", record.cx, record.cz, failure);
                } finally {
                    messageBytes.addAndGet(-record.message.capacity());
                    DirectBufferPool.release(record.message);
                }
            }
            trimDisk(received, diskLimit);
        } finally {
            synchronized (this) {
                recorderDone = true;
                if (leases == 0) flushed.complete(null);
            }
        }
    }

    /** Scans on a worker with at most 128 file metadata records live, independently of cache history. */
    private static long trimDisk(Path directory, long maximum) {
        var order = java.util.Comparator.comparingLong(DiskEntry::modified).thenComparing(entry -> entry.path.toString());
        try {
            while (true) {
                var oldest = new java.util.PriorityQueue<DiskEntry>(128, order.reversed());
                long bytes = 0;
                try (var files = Files.newDirectoryStream(directory)) {
                    for (Path path : files) {
                        if (!Files.isRegularFile(path)) continue;
                        DiskEntry entry = new DiskEntry(path, Files.size(path), Files.getLastModifiedTime(path).toMillis());
                        bytes += entry.bytes;
                        if (oldest.size() < 128) oldest.add(entry);
                        else if (order.compare(entry, oldest.peek()) < 0) { oldest.poll(); oldest.add(entry); }
                    }
                }
                if (bytes <= maximum || oldest.isEmpty()) return bytes;
                var batch = new java.util.ArrayList<>(oldest);
                batch.sort(order);
                for (DiskEntry file : batch) {
                    if (bytes <= maximum) return bytes;
                    if (Files.deleteIfExists(file.path)) bytes -= file.bytes;
                }
            }
        } catch (IOException failure) {
            LOGGER.warn("Cannot trim far-terrain disk cache {}", directory, failure);
            return -1;
        }
    }

    private record DiskEntry(Path path, long bytes, long modified) {}

    /** Only the 1024 presence flags are inspected; source payloads are decoded by native overlay. */
    private static Region inspect(Path file) throws IOException {
        long[] present = new long[16];
        if (!Files.isRegularFile(file)) return new Region(0, present);
        try (var input = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != 0x46434348 || input.readInt() != 1) throw new IOException("Invalid received region " + file);
            for (int i = 0; i < 1024; i++) {
                int value = input.readUnsignedByte();
                if (value > 1) throw new IOException("Invalid received chunk presence in " + file);
                if (value != 0) present[i >>> 6] |= 1L << (i & 63);
            }
        }
        return new Region(Files.getLastModifiedTime(file).toMillis(), present);
    }

    @Override public synchronized void close() { closed = true; recorder.interrupt(); }
}
