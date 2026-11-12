/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.VoxelDag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the built JNI service without initializing a GPU or the Remix runtime. */
@EnabledIfEnvironmentVariable(named = "RTX_VOXEL_DAG_TEST_LIBRARY", matches = ".+")
class VoxelDagNativeTest {
    @BeforeAll static void load() { System.load(System.getenv("RTX_VOXEL_DAG_TEST_LIBRARY")); }

    @Test
    void nativeSurfaceAndExactWireDataPreserveTheEstablishedVoxelContract() {
        Random random = new Random(0xDAD565);
        for (int lg = 1; lg <= 4; lg++) {
            int n = 256 >> lg;
            short[] heights = new short[n * n], colours = new short[n * n];
            ByteBuffer surface = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < heights.length; i++) {
                heights[i] = i % 13 == 0 ? Short.MIN_VALUE : (short) random.nextInt(1, 384);
                colours[i] = (short) random.nextInt(1, 65536);
                surface.putInt((heights[i] << 16) | (colours[i] & 0xFFFF));
            }
            short[][][] exact = new short[256][][];
            exact[0] = new short[32][];
            exact[0][8] = new short[4096];
            ByteBuffer voxels = ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 4096; i++) {
                exact[0][8][i] = i % 7 == 0 ? 0 : (short) random.nextInt(1, 65536);
                voxels.putShort(exact[0][8][i]);
            }
            var reference = FarSubtree.build(lg, heights, colours, exact, 2);
            long handle = VoxelDag.create(lg, 2);
            try {
                VoxelDag.surface(handle, 0, heights.length, surface);
                VoxelDag.exact(handle, 0, 8, 0, voxels);
                int count = VoxelDag.finish(handle);
                int[] roots = {VoxelDag.root(handle, 0), VoxelDag.root(handle, 1)};
                ByteBuffer output = ByteBuffer.allocateDirect(count * 32).order(ByteOrder.nativeOrder());
                VoxelDag.copy(handle, 0, count * 8, 0, output);
                int[] nodes = new int[count * 8]; output.asIntBuffer().get(nodes);
                var actual = new FarSubtree.Result(nodes, count, roots, 0);
                for (int i = 0; i < 4000; i++) {
                    int x = random.nextInt(256), y = random.nextInt(384), z = random.nextInt(256);
                    assertEquals(FarSubtreeTest.voxel(reference, x, y, z), FarSubtreeTest.voxel(actual, x, y, z),
                            "lg=" + lg + " at " + x + "," + y + "," + z);
                }
                // An exact column's unspecified sections are air, even with nonempty procedural surfaces.
                assertEquals(0, FarSubtreeTest.voxel(actual, 3, 100, 7));
            } finally { VoxelDag.destroy(handle); }
        }
        assertEquals(0, VoxelDag.metrics()[1]);
        assertEquals(0, VoxelDag.metrics()[4]);
    }

    @Test
    void sourceFlushWaitsForEveryInflightBuildAndKeepsOnlyDiskPayload(@TempDir Path root) throws Exception {
        FarTerrainSource source = new FarTerrainSource(root.resolve("received"), root.resolve("generated"), null,
                true, 16, CompletableFuture.completedFuture(null));
        source.acquire();
        source.close();
        assertFalse(source.flushed().isDone());
        source.release();
        source.flushed().get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, source::acquire);
        assertThrows(IllegalStateException.class, source::release);
    }

    @Test
    void receivedNegativeCoordinatesEmptyOverridesAndNativeDiskReopenSurviveSourceRetirement(@TempDir Path root) throws Exception {
        Path received = root.resolve("received"), generated = root.resolve("generated");
        FarTerrainSource writer = new FarTerrainSource(received, generated, null, true, 16, CompletableFuture.completedFuture(null));
        ByteBuffer message = com.nvidia.rtxremix.minecraft.util.DirectBufferPool.acquire(1024).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 256; i++) message.putInt((65 << 16) | 0xF800);
        assertTrue(writer.record(-1, -1, message));
        writer.close(); writer.flushed().get(5, TimeUnit.SECONDS);
        FarTerrainSource reader = new FarTerrainSource(received, generated, null, true, 16, writer.flushed());
        long handle = VoxelDag.create(2, 2);
        try {
            reader.surface(handle, -1, -1, 2, -64, 0, com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration.current());
            int nodes = VoxelDag.finish(handle);
            ByteBuffer output = ByteBuffer.allocateDirect(nodes * 32).order(ByteOrder.nativeOrder());
            VoxelDag.copy(handle, 0, nodes * 8, 0, output);
            int[] data = new int[nodes * 8]; output.asIntBuffer().get(data);
            var actual = new FarSubtree.Result(data, nodes, new int[]{VoxelDag.root(handle, 0), VoxelDag.root(handle, 1)}, 0);
            assertEquals(0xF800, FarSubtreeTest.voxel(actual, 243, 128, 243));
            assertEquals(0, FarSubtreeTest.voxel(actual, 243, 129, 243));
            assertEquals(0, FarSubtreeTest.voxel(actual, 3, 128, 3));
            reader.save(handle, -1, -1, 2, 0x1234);
            long disk = reader.open(-1, -1, 2, 0x1234);
            try {
                assertEquals(nodes, VoxelDag.finish(disk));
                ByteBuffer read = ByteBuffer.allocateDirect(nodes * 32).order(ByteOrder.nativeOrder());
                VoxelDag.copy(disk, 0, nodes * 8, 0, read);
                assertEquals(-1, output.mismatch(read));
            } finally { VoxelDag.destroy(disk); }
        } finally {
            VoxelDag.destroy(handle); reader.close(); reader.flushed().get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, VoxelDag.metrics()[1]);
        assertEquals(0, VoxelDag.metrics()[4]);
        assertEquals(0, VoxelDag.metrics()[6]);
    }
}
