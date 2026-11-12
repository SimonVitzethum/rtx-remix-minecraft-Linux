/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Arrays;

/** Small hash-consed address-directory builder. Subtree payloads are built natively and remain on the GPU. */
final class FarFieldDag {
    static final int LEAF = 0x80000000;

    private int[] nodes = new int[8 * 1024];
    private int nodeCount = 1;
    private int[] table = new int[1 << 12];
    private int tableUsed;

    int nodeCount() { return nodeCount; }

    /** Copy of the used part ({@code nodeCount * 8} ints, including the zero node). */
    int[] nodes() { return Arrays.copyOf(nodes, nodeCount * 8); }

    /** Hash-consed node creation; collapses to the child reference when all eight are the same empty / leaf reference. */
    int make(int[] refs) {
        boolean allSame = true;
        for (int i = 1; i < 8; i++) {
            if (refs[i] != refs[0]) { allSame = false; break; }
        }
        if (allSame && (refs[0] == 0 || (refs[0] & LEAF) != 0)) {
            return refs[0];
        }
        int h = 0;
        for (int r : refs) h = h * 0x9E3779B1 + r + (h >>> 15);
        h ^= h >>> 16;
        int mask = table.length - 1;
        int slot = h & mask;
        while (table[slot] != 0) {
            int idx = table[slot];
            int base = idx * 8;
            boolean eq = true;
            for (int i = 0; i < 8; i++) {
                if (nodes[base + i] != refs[i]) { eq = false; break; }
            }
            if (eq) return idx;
            slot = (slot + 1) & mask;
        }
        if (nodeCount * 8 + 8 > nodes.length) nodes = Arrays.copyOf(nodes, nodes.length * 2);
        int idx = nodeCount++;
        System.arraycopy(refs, 0, nodes, idx * 8, 8);
        table[slot] = idx;
        if (++tableUsed * 2 > table.length) rehash();
        return idx;
    }

    private void rehash() {
        int[] old = table;
        table = new int[old.length * 2];
        int mask = table.length - 1;
        for (int idx : old) {
            if (idx == 0) continue;
            int h = 0;
            for (int i = 0; i < 8; i++) h = h * 0x9E3779B1 + nodes[idx * 8 + i] + (h >>> 15);
            h ^= h >>> 16;
            int slot = h & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = idx;
        }
    }
}
