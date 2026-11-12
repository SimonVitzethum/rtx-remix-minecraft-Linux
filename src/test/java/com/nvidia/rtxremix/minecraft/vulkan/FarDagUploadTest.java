/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FarDagUploadTest {
    @Test
    void slicedUploadMatchesFlatDagWithRelocatedBranchesLeavesAndTopNodes() {
        Random random = new Random(0xDA61234);
        FarFieldDag flat = new FarFieldDag();
        FarDagUpload.Builder upload = new FarDagUpload.Builder();
        int[] roots = new int[8];
        for (int column = 0; column < roots.length; column++) {
            FarFieldDag subtree = new FarFieldDag();
            int root = 0;
            for (int depth = 0; depth < 20; depth++) {
                int[] children = new int[8];
                for (int i = 0; i < 8; i++) {
                    children[i] = switch (random.nextInt(3)) {
                        case 0 -> 0;
                        case 1 -> FarFieldDag.LEAF | random.nextInt(1, 65536);
                        default -> root;
                    };
                }
                root = subtree.make(children);
            }
            int offset = flat.append(subtree.nodes(), subtree.nodeCount());
            assertEquals(offset, upload.append(subtree.nodes(), subtree.nodeCount()));
            roots[column] = root + offset;
        }
        int topOffset = upload.nodeCount() - 1;
        FarFieldDag top = new FarFieldDag();
        int branch = top.make(roots) + topOffset;
        assertEquals(flat.make(roots), branch);
        int[] parent = {branch, branch, 0, FarFieldDag.LEAF | 123, 0, 0, 0, 0};
        assertEquals(flat.make(parent), top.make(parent) + topOffset);
        // Shared top nodes must preserve their final flat reference even when deduplicated.
        assertEquals(branch, top.make(roots) + topOffset);
        FarDagUpload snapshot = upload.finish(top);
        int[] expected = flat.nodes();
        assertEquals(expected.length, snapshot.words());
        for (int slice : new int[]{1, 3, 8, 31, 157, expected.length}) {
            IntBuffer actual = IntBuffer.allocate(expected.length);
            for (int first = 0; first < expected.length; first += slice) {
                IntBuffer destination = actual.slice(first, Math.min(slice, expected.length - first));
                snapshot.copy(first, destination);
                assertFalse(destination.hasRemaining());
            }
            assertArrayEquals(expected, actual.array(), "slice words=" + slice);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.copy(snapshot.words(), IntBuffer.allocate(1)));
    }

    @Test
    void publishedSnapshotSurvivesCacheReplacementWithoutChangingItsWireData() {
        FarDagUpload.Builder builder = new FarDagUpload.Builder();
        FarFieldDag cached = new FarFieldDag();
        cached.make(new int[]{FarFieldDag.LEAF | 42, 0, 0, 0, 0, 0, 0, 0});
        int[] original = cached.nodes();
        builder.append(original, cached.nodeCount());
        FarDagUpload published = builder.finish(new FarFieldDag());
        cached = new FarFieldDag();
        cached.make(new int[]{FarFieldDag.LEAF | 17, 0, 0, 0, 0, 0, 0, 0});
        builder = new FarDagUpload.Builder();
        builder.append(cached.nodes(), cached.nodeCount());
        FarDagUpload replacement = builder.finish(new FarFieldDag());
        IntBuffer before = IntBuffer.allocate(published.words());
        published.copy(0, before);
        assertArrayEquals(original, before.array());
        IntBuffer after = IntBuffer.allocate(replacement.words());
        replacement.copy(0, after);
        assertNotEquals(before.get(8), after.get(8));
    }

    @Test
    void emptyGeometryKeepsReservedZeroNodeAndSupportsEmptySlices() {
        FarDagUpload.Builder builder = new FarDagUpload.Builder();
        builder.append(new int[8], 1);
        FarDagUpload empty = builder.finish(new FarFieldDag());
        IntBuffer destination = IntBuffer.allocate(8);
        empty.copy(0, destination);
        assertArrayEquals(new int[8], destination.array());
        empty.copy(8, IntBuffer.allocate(0));
    }
}
