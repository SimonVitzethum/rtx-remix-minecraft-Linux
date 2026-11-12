/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Render-thread node-address allocator. It retains only ranges, never node payloads. Owners release replaced
 * pages after the last GPU reader completes; allocation identity rejects stale releases even after address reuse.
 * Node zero is permanently reserved. Growing the device buffer preserves all node addresses.
 */
final class GpuDagAllocator {
    static final class Page {
        final int firstNode, nodes;
        private Page(int firstNode, int nodes) { this.firstNode = firstNode; this.nodes = nodes; }
        int referenceOffset() { return firstNode - 1; }
    }

    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private final Set<Page> live = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int maximumNodes;
    private int end = 1;
    private long liveNodes;

    GpuDagAllocator(int maximumNodes) {
        if (maximumNodes < 1) throw new IllegalArgumentException("DAG arena must fit the empty node");
        this.maximumNodes = maximumNodes;
    }

    /** Allocates nonzero payload nodes. Fails explicitly at the device limit; never discards another live page. */
    Page allocate(int nodes) {
        if (nodes <= 0) throw new IllegalArgumentException("DAG page must contain nodes");
        int first = -1;
        for (var item : free.entrySet()) {
            if (item.getValue() >= nodes) {
                first = item.getKey();
                int remainder = item.getValue() - nodes;
                free.remove(first);
                if (remainder > 0) free.put(first + nodes, remainder);
                break;
            }
        }
        if (first < 0) {
            if (nodes > maximumNodes - end) throw new IllegalStateException("Device-local DAG arena exhausted: "
                    + liveNodes + " live nodes, " + nodes + " requested, device limit " + maximumNodes);
            first = end;
            end += nodes;
        }
        Page page = new Page(first, nodes);
        live.add(page);
        liveNodes += nodes;
        return page;
    }

    void release(Page page) {
        if (!live.remove(page)) throw new IllegalStateException("DAG page already released or belongs to another arena");
        liveNodes -= page.nodes;
        int first = page.firstNode, nodes = page.nodes;
        var before = free.lowerEntry(first);
        if (before != null && before.getKey() + before.getValue() == first) {
            first = before.getKey();
            nodes += before.getValue();
            free.remove(before.getKey());
        }
        var after = free.ceilingEntry(first);
        if (after != null && first + nodes == after.getKey()) {
            nodes += after.getValue();
            free.remove(after.getKey());
        }
        if (first + nodes == end) end = first;
        else free.put(first, nodes);
    }

    int requiredNodes() { return end; }
    long liveBytes() { return liveNodes * 32; }
    int livePages() { return live.size(); }
}
