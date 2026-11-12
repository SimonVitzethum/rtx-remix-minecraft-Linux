/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GpuDagTopologyTest {
    @Test
    void negativeWorldOriginAndTwoHeightSlabsKeepGpuPageReferences() {
        int leaf = FarFieldDag.LEAF | 0xF800;
        Map<Long, int[]> roots = Map.of(key(-2, -3), new int[]{1234, leaf}, key(-1, -2), new int[]{0, 5678});
        GpuDagTopology.Result directory = GpuDagTopology.build(roots, 10, -3, -4, 2);
        int offset = 10000;
        int[] nodes = directory.nodesAt(offset);
        assertEquals(1234, subtree(directory.rootAt(offset), nodes, offset, 10, 256, 0, 256));
        assertEquals(leaf, subtree(directory.rootAt(offset), nodes, offset, 10, 256, 256, 256));
        assertEquals(5678, subtree(directory.rootAt(offset), nodes, offset, 10, 512, 256, 512));
        assertEquals(0, subtree(directory.rootAt(offset), nodes, offset, 10, 0, 0, 0));
        assertEquals(0, subtree(directory.rootAt(offset), nodes, offset, 10, 256, 512, 256));
        // Reuse with another directory allocation never rewrites the stored GPU subtree addresses.
        int[] other = directory.nodesAt(20000);
        assertEquals(1234, subtree(directory.rootAt(20000), other, 20000, 10, 256, 0, 256));
    }

    @Test
    void emptyDirectoryNeedsNoPayloadAndLeafBitsNeverBecomeLocalReferences() {
        var empty = GpuDagTopology.build(Map.of(), 15, -64, -64, 2);
        assertEquals(0, empty.rootAt(123));
        assertEquals(8, empty.nodes().length);
        int leaf = FarFieldDag.LEAF | 0xFFFF;
        var one = GpuDagTopology.build(Map.of(key(0, 0), new int[]{leaf}), 9, 0, 0, 1);
        assertEquals(leaf, subtree(one.rootAt(77), one.nodesAt(77), 77, 9, 0, 0, 0));
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private static int subtree(int root, int[] nodes, int offset, int level, int x, int y, int z) {
        int reference = root;
        for (int bit = level - 1; bit >= 8 && reference != 0 && (reference & FarFieldDag.LEAF) == 0; bit--) {
            int child = ((x >>> bit) & 1) | (((y >>> bit) & 1) << 1) | (((z >>> bit) & 1) << 2);
            reference = nodes[(reference - offset) * 8 + child];
        }
        return reference;
    }
}
