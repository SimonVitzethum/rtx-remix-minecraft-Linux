/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FarFieldCoverageTest {
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static FarFieldCoverage.Source source(long revision, boolean geometry) {
        return new FarFieldCoverage.Source(revision, geometry, new FarFieldCoverage.Sources(1, 2, 3, 4));
    }

    @Test
    void missingStaleAndUnwantedAreDistinctFromAcceptedEmptyGeometry() {
        long empty = key(0, 0), stale = key(1, 0), missing = key(2, 0), unwanted = key(3, 0);
        FarFieldCoverage coverage = new FarFieldCoverage(Map.of(empty, source(1, false), stale, source(2, true), unwanted, source(3, true)));
        var summary = coverage.summarize(new long[]{empty, stale, missing}, (key, revision) -> key != stale);
        assertEquals(3, summary.covered());
        assertEquals(2, summary.geometry());
        assertEquals(1, summary.missingWanted());
        assertEquals(1, summary.staleWanted());
        assertEquals(1, summary.unwanted());
        assertEquals("[0:2..2]", summary.missingRanges());
        assertEquals("[0:1..1]", summary.staleRanges());
        assertEquals(new FarFieldCoverage.Sources(3, 6, 9, 12), summary.sources());
    }

    @Test
    void publishedCoverageIsImmutableWhenTheCacheOrWantedSetChanges() {
        HashMap<Long, FarFieldCoverage.Source> cache = new HashMap<>();
        long original = key(-2, -3), replacement = key(4, 5);
        cache.put(original, source(10, true));
        FarFieldCoverage published = new FarFieldCoverage(cache);
        cache.clear();
        cache.put(replacement, source(20, true));
        var summary = published.summarize(new long[]{replacement}, (key, revision) -> true);
        assertEquals("[-3:-2..-2]", summary.subtreeRanges());
        assertEquals(1, summary.missingWanted());
        assertEquals(1, summary.unwanted());
        assertEquals(1, summary.geometry());
    }

    @Test
    void exactRangesPreserveHolesNegativeCoordinatesAndIntegerEdges() {
        assertEquals("[-1:-2..0,-1:2..2,0:2147483647..2147483647]", FarFieldCoverage.ranges(new long[]{
                key(2, -1), key(-2, -1), key(-1, -1), key(0, -1), key(Integer.MAX_VALUE, 0)}));
        assertEquals("[]", FarFieldCoverage.ranges(new long[0]));
    }

    @Test
    void sourceRevisionMatchTracksCurrentRequestRatherThanRetainedFallback() {
        FarBuildCache<String> cache = new FarBuildCache<>(1);
        long key = key(0, 0);
        cache.observe(key, 1);
        var ticket = cache.begin(key, 1, () -> true);
        cache.finish(ticket, "old");
        cache.drain();
        assertTrue(cache.matches(key, 1));
        cache.observe(key, 2);
        assertFalse(cache.matches(key, cache.get(key).signature()));
        assertEquals("old", cache.get(key).data());
        cache.reset();
        assertFalse(cache.matches(key, 2));
    }
}
