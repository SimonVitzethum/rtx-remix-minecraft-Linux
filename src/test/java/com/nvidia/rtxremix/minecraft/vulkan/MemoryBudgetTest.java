/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MemoryBudgetTest {
    @Test
    void replacementIncludesOldNewAndOtherInflightAllocationsUntilActualRetirement() {
        var sample = new AtomicReference<>(new MemoryBudget.Snapshot(1000, 400, 2000, 800, 100, 200, "EXT+scope"));
        MemoryBudget budget = new MemoryBudget(sample::get);
        var old = budget.reserve(200, 300);
        assertNotNull(old);
        old.commit();
        var replacement = budget.reserve(250, 350);
        assertNotNull(replacement);
        assertNull(budget.reserve(51, 0));
        replacement.commit();
        assertEquals(450, budget.state().deviceCommitted());
        assertEquals(650, budget.state().hostCommitted());
        old.close();
        assertEquals(250, budget.state().deviceAvailable());
        replacement.close();
        assertEquals(0, budget.state().deviceCommitted());
        assertEquals(450, budget.state().devicePeak());
    }

    @Test
    void staleDriverUsageCannotSpendACommittedAllocationAgain() {
        var sample = new AtomicReference<>(new MemoryBudget.Snapshot(1000, 400, 1000, 100, 0, 0, "EXT"));
        AtomicLong clock = new AtomicLong();
        MemoryBudget budget = new MemoryBudget(sample::get, clock::get, 30);
        var allocation = budget.reserve(500, 0);
        assertNotNull(allocation);
        allocation.commit();
        assertNull(budget.reserve(101, 0));
        sample.set(new MemoryBudget.Snapshot(1000, 900, 1000, 100, 0, 0, "EXT"));
        assertEquals(100, budget.state().deviceAvailable());
        allocation.close();
        sample.set(new MemoryBudget.Snapshot(1000, 400, 1000, 100, 0, 0, "EXT"));
        assertEquals(600, budget.state().deviceAvailable());
    }

    @Test
    void aShrinkingPhysicalBudgetDefersNewWorkAndRecoveryDoesNotRequireDroppingLiveGeometry() {
        var sample = new AtomicReference<>(new MemoryBudget.Snapshot(1000, 200, 1000, 200, 100, 100, "EXT+scope"));
        MemoryBudget budget = new MemoryBudget(sample::get);
        var live = budget.reserve(400, 400);
        assertNotNull(live);
        live.commit();
        sample.set(new MemoryBudget.Snapshot(500, 600, 500, 600, 100, 100, "EXT+scope pressure"));
        assertNull(budget.reserve(1, 1));
        assertEquals(400, budget.state().deviceCommitted());
        sample.set(new MemoryBudget.Snapshot(2000, 600, 2000, 600, 100, 100, "EXT+scope recovered"));
        var next = budget.reserve(900, 900);
        assertNotNull(next);
        next.close();
        live.close();
        assertThrows(IllegalStateException.class, live::close);
    }

    @Test
    void concurrentOwnersCannotReserveTheSameAvailableMemory() throws Exception {
        var budget = new MemoryBudget(() -> new MemoryBudget.Snapshot(1000, 0, 1000, 0, 0, 0, "shared"));
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return budget.reserve(600, 600); });
            var second = workers.submit(() -> { start.await(); return budget.reserve(600, 600); });
            start.countDown();
            var a = first.get(3, TimeUnit.SECONDS);
            var b = second.get(3, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null));
            assertEquals(600, budget.state().deviceReserved());
            (a != null ? a : b).close();
            assertEquals(0, budget.state().hostReserved());
        }
    }

    @Test
    void geometricGrowthRespectsIntegerAndHardwareLimitsWithoutASubsystemMegabyteCeiling() {
        assertEquals(1L << 35, MemoryBudget.capacity(1L << 34, (1L << 34) + 1, 64, 1L << 40));
        assertEquals(Long.MAX_VALUE, MemoryBudget.capacity(1L << 62, Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> MemoryBudget.capacity(0, 1025, 64, 1024));
        var budget = new MemoryBudget(() -> new MemoryBudget.Snapshot(Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, 0, 0, "wide"));
        var reservation = budget.reserve(Long.MAX_VALUE, Long.MAX_VALUE);
        assertNotNull(reservation);
        reservation.commit();
        assertNull(budget.reserve(1, 1));
        reservation.close();
    }
}
