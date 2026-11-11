/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FarBuildCacheTest {
    @Test
    void keepsPublishedGeometryUntilTheLatestMutationFinishes() {
        FarBuildCache<String> cache = new FarBuildCache<>(2);
        cache.observe(7, 1);
        var initial = cache.begin(7, 1, () -> true);
        cache.finish(initial, "original");
        assertEquals(1, cache.drain());
        cache.observe(7, 2); // place
        var placed = cache.begin(7, 2, () -> true);
        cache.observe(7, 3); // break while the place build is running
        cache.finish(placed, "placed");
        assertEquals(0, cache.drain());
        assertEquals("original", cache.get(7).data());
        var broken = cache.begin(7, 3, () -> true);
        cache.finish(broken, "broken");
        assertEquals(1, cache.drain());
        assertEquals("broken", cache.get(7).data());
        cache.observe(7, 3);
        assertFalse(cache.needsBuild(7));
        assertNull(cache.begin(7, 3, () -> true));
    }

    @Test
    void validatesLiveMutationAfterWorkerCompletionOnTheOwnerThread() throws Exception {
        FarBuildCache<String> cache = new FarBuildCache<>(1);
        AtomicBoolean stillCurrent = new AtomicBoolean(true);
        Thread owner = Thread.currentThread();
        cache.observe(0, 1);
        var ticket = cache.begin(0, 1, () -> {
            assertSame(owner, Thread.currentThread());
            return stillCurrent.get();
        });
        Thread worker = new Thread(() -> cache.finish(ticket, "outdated"));
        worker.start();
        worker.join();
        stillCurrent.set(false); // after worker completion, before render-thread publication
        assertEquals(0, cache.drain());
        assertNull(cache.get(0));
        assertTrue(cache.needsBuild(0));
    }

    @Test
    void rejectsAbaAndCapturesPreparedForAnotherSignature() {
        FarBuildCache<String> cache = new FarBuildCache<>(1);
        cache.observe(0, 1);
        var ticket = cache.begin(0, 1, () -> true);
        cache.observe(0, 2);
        cache.observe(0, 1);
        cache.finish(ticket, "old A");
        assertEquals(0, cache.drain());
        cache.observe(0, 3);
        assertNull(cache.begin(0, 1, () -> true));
        assertNotNull(cache.begin(0, 3, () -> true));
    }

    @Test
    void unloadReloadAndSessionResetRejectOldWorkersEvenWithIdenticalSignatures() {
        FarBuildCache<String> cache = new FarBuildCache<>(2);
        cache.observe(0, 1);
        var old = cache.begin(0, 1, () -> fail("old-session validator must not run"));
        cache.retain(Set.of());
        cache.observe(0, 1);
        cache.finish(old, "unloaded");
        assertEquals(0, cache.drain());
        old = cache.begin(0, 1, () -> fail("old-session validator must not run"));
        cache.reset();
        cache.observe(0, 1);
        var current = cache.begin(0, 1, () -> true);
        cache.finish(current, "new world");
        cache.finish(old, "old world");
        assertEquals(1, cache.drain());
        assertEquals("new world", cache.get(0).data());
        assertEquals(0, cache.pending());
    }

    @Test
    void boundsRunningAndCompletedJobsTogetherAndRetriesFailures() {
        FarBuildCache<String> cache = new FarBuildCache<>(1);
        cache.observe(0, 1);
        cache.observe(1, 1);
        var ticket = cache.begin(0, 1, () -> true);
        assertNull(cache.begin(0, 1, () -> true));
        assertNull(cache.begin(1, 1, () -> true));
        cache.finish(ticket, null);
        assertFalse(cache.hasCapacity());
        assertEquals(0, cache.drain());
        assertTrue(cache.hasCapacity());
        assertTrue(cache.needsBuild(0));
    }
}
