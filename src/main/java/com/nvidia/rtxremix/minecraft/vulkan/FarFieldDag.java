/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Arrays;
import java.util.Map;

/**
 * Builds a sparse voxel DAG (octree with identical subtrees shared through a hash table) from 16^3 sections.
 *
 * <p>Layout, shared with {@code farfield_march.comp}: node k occupies {@code nodes[8k .. 8k+7]}, every entry is a
 * reference: 0 = empty, bit 31 set = the whole child cell is one material (low byte), otherwise the index of a node.
 * Node 0 is a reserved zero node so that 0 can mean empty. A uniform cell is represented at any depth by one
 * reference, so solid underground and empty sky cost nothing. Dedup is by content of the eight child references, which
 * is scale free, so identical shapes at different levels also share one node.
 *
 * <p>Pure Java, no Minecraft types: runs on a worker thread.
 */
final class FarFieldDag {
    static final int LEAF = 0x80000000;
    static final int SECTION_LEVEL = 4;

    /** Result of a build. */
    record Result(int[] nodes, int nodeCount, int root, int levels, int solidSections, long nonEmptyVoxels, int uniqueNodes) {}

    private int[] nodes = new int[8 * 1024];
    private int nodeCount = 1;
    private int[] table = new int[1 << 12];
    private int tableUsed;

    private Map<Long, byte[]> sections;
    private int minSx, minSy, minSz, maxSx, maxSy, maxSz;
    private long voxels;

    /** Packs section coordinates (relative to the cube origin, each below 2^20) into a map key. */
    static long key(int sx, int sy, int sz) {
        return ((long) sx << 40) | ((long) sy << 20) | (long) sz;
    }

    /**
     * @param sections 4096 bytes (index {@code (y << 8) | (z << 4) | x}) or one byte for a uniform section; empty sections are absent
     * @param levels log2 of the cube edge in blocks, at least 4
     */
    static Result build(Map<Long, byte[]> sections, int levels) {
        FarFieldDag dag = new FarFieldDag();
        return dag.run(sections, levels);
    }

    private Result run(Map<Long, byte[]> secs, int levels) {
        sections = secs;
        minSx = minSy = minSz = Integer.MAX_VALUE;
        maxSx = maxSy = maxSz = Integer.MIN_VALUE;
        for (Long k : secs.keySet()) {
            int sx = (int) (k >> 40), sy = (int) ((k >> 20) & 0xFFFFF), sz = (int) (k & 0xFFFFF);
            minSx = Math.min(minSx, sx); maxSx = Math.max(maxSx, sx);
            minSy = Math.min(minSy, sy); maxSy = Math.max(maxSy, sy);
            minSz = Math.min(minSz, sz); maxSz = Math.max(maxSz, sz);
        }
        int root = secs.isEmpty() ? 0 : region(levels, 0, 0, 0);
        return new Result(Arrays.copyOf(nodes, nodeCount * 8), nodeCount, root, levels, secs.size(), voxels, nodeCount - 1);
    }

    /** Cell of edge 2^level at block coordinates (x, y, z) (cube relative). */
    private int region(int level, int x, int y, int z) {
        int size = 1 << level;
        // bounding box of the section set in block units
        if (x + size <= (minSx << 4) || x > ((maxSx << 4) + 15)
                || y + size <= (minSy << 4) || y > ((maxSy << 4) + 15)
                || z + size <= (minSz << 4) || z > ((maxSz << 4) + 15)) {
            return 0;
        }
        if (level == SECTION_LEVEL) {
            byte[] s = sections.get(key(x >> 4, y >> 4, z >> 4));
            if (s == null) return 0;
            if (s.length == 1) {
                voxels += 4096;
                return LEAF | (s[0] & 0xFF);
            }
            return bytes(s, 0, 0, 0, SECTION_LEVEL);
        }
        int[] refs = new int[8];
        int half = size >> 1;
        for (int i = 0; i < 8; i++) {
            refs[i] = region(level - 1, x + (i & 1) * half, y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half);
        }
        return make(refs);
    }

    private int bytes(byte[] s, int x, int y, int z, int level) {
        if (level == 0) {
            int m = s[(y << 8) | (z << 4) | x] & 0xFF;
            if (m != 0) voxels++;
            return m == 0 ? 0 : LEAF | m;
        }
        int half = 1 << (level - 1);
        int[] local = new int[8];
        for (int i = 0; i < 8; i++) {
            local[i] = bytes(s, x + (i & 1) * half, y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half, level - 1);
        }
        return make(local);
    }

    private int make(int[] refs) {
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
