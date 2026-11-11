/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FarCacheSourceTest {
    @TempDir Path directory;

    private static FarChunkSnapshot.Surface surface(int height, int rgb) {
        short[] top = new short[256], colours = new short[256];
        Arrays.fill(top, (short) height);
        Arrays.fill(colours, (short) rgb);
        return new FarChunkSnapshot.Surface(0, 0, top, colours);
    }

    private static FarLodSource.Tile await(FarCacheSource source, int height) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                FarLodSource.Tile tile = source.get(1, 0, 0);
                if (tile != null && tile.top[0] == height) return tile;
                Thread.sleep(5);
            }
        });
    }

    @Test
    void liveSurfaceChangesInvalidateTilesAndSurviveCacheReopen() throws Exception {
        var first = new FarCacheSource(directory, 16, null, CompletableFuture.completedFuture(null));
        FarCacheSource reopened = null;
        try {
            first.store(surface(70, 0xF800));
            FarLodSource.Tile original = await(first, 70);
            long count = first.recorded.get();
            first.store(surface(70, 0xF800));
            assertEquals(count, first.recorded.get(), "unchanged surfaces must not invalidate tiles");
            first.store(surface(71, 0x7E0));
            FarLodSource.Tile placed = await(first, 71);
            assertNotEquals(original.version, placed.version);
            assertEquals(0x7E0, placed.rgb[0] & 0xFFFF);
            assertEquals(70, original.top[0], "old tiles remain immutable during rebuilding");
            first.store(surface(69, 0x1F));
            await(first, 69);
            first.close();
            // The next source may start immediately; its I/O waits for the previous asynchronous flush.
            reopened = new FarCacheSource(directory, 16, null, first.flushed());
            assertEquals(0x1F, await(reopened, 69).rgb[0] & 0xFFFF);
        } finally {
            first.close();
            if (reopened != null) { reopened.close(); reopened.flushed().get(5, TimeUnit.SECONDS); }
            first.flushed().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void receivedEditsAndEmptyColumnsOverrideTheIntegratedGenerator() throws Exception {
        FarLodSource.Tile terrain = new FarLodSource.Tile();
        Arrays.fill(terrain.top, (short) 80);
        Arrays.fill(terrain.rgb, (short) 0x7E0);
        FarTileSource fallback = new FarTileSource() {
            public FarLodSource.Tile get(int lg, int tx, int tz) { return terrain; }
            public boolean has(int lg, int tx, int tz) { return true; }
            public void setCenter(int x, int z) {}
            public void trim(int blocks) {}
            public int pending() { return 0; }
            public long generatedCount() { return 0; }
            public long diskCount() { return 0; }
            public void close() {}
        };
        var source = new FarCacheSource(directory, 16, fallback, CompletableFuture.completedFuture(null));
        try {
            source.store(surface(95, 0xF800));
            var edited = await(source, 95);
            assertEquals(80, edited.top[8], "unvisited neighbouring chunks retain generator terrain");
            source.store(surface(FarLodSource.EMPTY, 0));
            var empty = await(source, FarLodSource.EMPTY);
            assertEquals(0, empty.rgb[0]);
            assertEquals(80, empty.top[8]);
        } finally {
            source.close();
            source.flushed().get(5, TimeUnit.SECONDS);
        }
    }
}
