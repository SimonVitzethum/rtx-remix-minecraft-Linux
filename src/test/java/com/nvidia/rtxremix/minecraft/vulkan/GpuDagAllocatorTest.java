/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GpuDagAllocatorTest {
    @Test
    void movementReusesRetiredPagesWithoutOverlappingOutstandingReaders() {
        GpuDagAllocator arena = new GpuDagAllocator(2048);
        var current = new ArrayList<GpuDagAllocator.Page>();
        var retiring = new ArrayDeque<GpuDagAllocator.Page>();
        Random random = new Random(0x6DAD);
        for (int frame = 0; frame < 4000; frame++) {
            if (current.size() == 16) retiring.addLast(current.remove(random.nextInt(current.size())));
            if (retiring.size() == 3) arena.release(retiring.removeFirst());
            GpuDagAllocator.Page next = arena.allocate(random.nextInt(1, 33));
            for (var page : current) assertDisjoint(next, page);
            for (var page : retiring) assertDisjoint(next, page);
            current.add(next);
            assertTrue(arena.requiredNodes() <= 2048);
        }
        current.forEach(arena::release);
        retiring.forEach(arena::release);
        assertEquals(1, arena.requiredNodes());
        assertEquals(0, arena.liveBytes());
        assertEquals(0, arena.livePages());
    }

    @Test
    void coalescesBothNeighboursAndRejectsStaleReleaseAfterAddressReuse() {
        GpuDagAllocator arena = new GpuDagAllocator(32);
        var first = arena.allocate(5);
        var middle = arena.allocate(5);
        var last = arena.allocate(5);
        var anchor = arena.allocate(1);
        arena.release(first);
        arena.release(last);
        arena.release(middle);
        var replacement = arena.allocate(15);
        assertEquals(first.firstNode, replacement.firstNode);
        assertThrows(IllegalStateException.class, () -> arena.release(first));
        arena.release(anchor);
        arena.release(replacement);
        assertEquals(1, arena.requiredNodes());
    }

    @Test
    void pressureNeverEvictsOrAliasesLivePages() {
        GpuDagAllocator arena = new GpuDagAllocator(10);
        var page = arena.allocate(9);
        assertEquals(0, page.referenceOffset());
        assertThrows(IllegalStateException.class, () -> arena.allocate(1));
        assertEquals(9 * 32, arena.liveBytes());
        assertEquals(1, arena.livePages());
        arena.release(page);
        assertEquals(1, arena.allocate(9).firstNode);
    }

    private static void assertDisjoint(GpuDagAllocator.Page a, GpuDagAllocator.Page b) {
        assertTrue(a.firstNode >= b.firstNode + b.nodes || b.firstNode >= a.firstNode + a.nodes);
    }
}
