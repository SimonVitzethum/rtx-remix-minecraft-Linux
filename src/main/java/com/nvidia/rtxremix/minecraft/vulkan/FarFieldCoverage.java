/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;

/** Immutable provenance of the subtrees selected into one DAG, including valid empty subtrees. */
final class FarFieldCoverage {
    /** Native-region chunks may also have a surface backdrop; these counts are not disjoint. */
    record Sources(int nativeRegionChunks, int liveExactChunks, int bobbyExactChunks, int surfaceFallbackChunks) {
        Sources plus(Sources other) {
            return new Sources(nativeRegionChunks + other.nativeRegionChunks, liveExactChunks + other.liveExactChunks,
                    bobbyExactChunks + other.bobbyExactChunks, surfaceFallbackChunks + other.surfaceFallbackChunks);
        }
    }

    record Source(long signature, boolean geometry, Sources columns) {}
    record Summary(int covered, int geometry, int missingWanted, int staleWanted, int unwanted,
                   String subtreeRanges, String missingRanges, String staleRanges, Sources sources) {}

    private final Map<Long, Source> entries;

    FarFieldCoverage(Map<Long, Source> entries) {
        this.entries = Map.copyOf(entries);
    }

    Summary summarize(long[] wanted, BiPredicate<Long, Long> revisionMatches) {
        Set<Long> missing = new HashSet<>();
        Set<Long> stale = new HashSet<>();
        Set<Long> desired = new HashSet<>();
        for (long key : wanted) {
            desired.add(key);
            Source source = entries.get(key);
            if (source == null) missing.add(key);
            else if (!revisionMatches.test(key, source.signature)) stale.add(key);
        }
        int geometry = 0, unwanted = 0;
        Sources sources = new Sources(0, 0, 0, 0);
        for (var entry : entries.entrySet()) {
            geometry += entry.getValue().geometry ? 1 : 0;
            unwanted += desired.contains(entry.getKey()) ? 0 : 1;
            sources = sources.plus(entry.getValue().columns);
        }
        return new Summary(entries.size(), geometry, missing.size(), stale.size(), unwanted,
                ranges(entries.keySet()), ranges(missing), ranges(stale), sources);
    }

    static String ranges(long[] keys) {
        return ranges(Arrays.stream(keys).boxed().toList());
    }

    /** Inclusive X spans for each Z row, in 256-block subtree coordinates. */
    private static String ranges(Iterable<Long> keys) {
        TreeMap<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        for (long key : keys) {
            rows.computeIfAbsent((int) key, ignored -> new TreeSet<>()).add((int) (key >> 32));
        }
        StringBuilder text = new StringBuilder("[");
        for (var row : rows.entrySet()) {
            int first = 0, last = 0;
            boolean have = false;
            for (int x : row.getValue()) {
                if (have && (long) x != (long) last + 1) {
                    if (text.length() > 1) text.append(',');
                    text.append(row.getKey()).append(':').append(first).append("..").append(last);
                    have = false;
                }
                if (!have) first = x;
                last = x;
                have = true;
            }
            if (have) {
                if (text.length() > 1) text.append(',');
                text.append(row.getKey()).append(':').append(first).append("..").append(last);
            }
        }
        return text.append(']').toString();
    }
}
