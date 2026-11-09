/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Arrays;

/**
 * Node pool of a sparse voxel DAG (octree with identical subtrees shared through a hash table).
 *
 * <p>Layout, shared with {@code farfield_march.comp}: node k occupies {@code nodes[8k .. 8k+7]}, every entry is a
 * reference: 0 = empty, bit 31 set = the whole child cell is one colour (low 16 bits = RGB565), otherwise the index
 * of a node. Node 0 is a reserved zero node so that 0 can mean empty. A uniform cell is one reference at any depth, so
 * solid underground and empty sky cost nothing. Dedup is by content of the eight child references (scale free).
 *
 * <p>The world is built from independent sub-DAGs (one per 256-block column, see {@link FarSubtree}), built in parallel
 * and cached; {@link #merge} relocates them into one pool and {@link #combine} adds the few top nodes. Pure Java, no
 * Minecraft types: runs on worker threads.
 */
final class FarFieldDag {
    static final int LEAF = 0x80000000;

    private int[] nodes = new int[8 * 1024];
    private int nodeCount = 1;
    private int[] table = new int[1 << 12];
    private int tableUsed;

    int nodeCount() { return nodeCount; }

    /** Copy of the used part ({@code nodeCount * 8} ints, including the zero node). */
    int[] nodes() { return Arrays.copyOf(nodes, nodeCount * 8); }

    /** The pool array itself (valid up to {@code nodeCount * 8}); do not use after further inserts. */
    int[] rawNodes() { return nodes; }

    /**
     * Appends a sub-DAG (node 0 = zero node is skipped) and returns the offset to add to its node references:
     * reference r (a node index) becomes r + offset. Leaves and empty references are unchanged. No dedup across
     * sub-DAGs (they are disjoint areas, little is shared).
     */
    int append(int[] sub, int subNodeCount) {
        int offset = nodeCount - 1;
        int add = (subNodeCount - 1) * 8;
        if (nodeCount * 8 + add > nodes.length) {
            nodes = Arrays.copyOf(nodes, (int) Math.min(Integer.MAX_VALUE - 8, Math.max((long) nodes.length * 2, (long) nodeCount * 8 + add)));
        }
        int dst = nodeCount * 8;
        for (int i = 8; i < subNodeCount * 8; i++) {
            int r = sub[i];
            nodes[dst++] = (r == 0 || (r & LEAF) != 0) ? r : r + offset;
        }
        nodeCount += subNodeCount - 1;
        return offset;
    }

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
