/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable flat DAG view over shared, immutable subtree arrays. Only the small top tree is owned by a generation;
 * subtree references are relocated while copying a bounded slice into mapped GPU memory. Keeping several publication
 * generations therefore retains changed subtrees, rather than several full heap copies of the entire scene.
 */
final class FarDagUpload {
    private record Segment(int start, int[] nodes, int words, int referenceOffset) {}

    static final class Builder {
        private final List<Segment> segments = new ArrayList<>();
        private int words = 8;

        /** Returns the node-reference offset for this subtree; its reserved zero node is omitted. */
        int append(int[] nodes, int nodeCount) {
            int offset = words / 8 - 1;
            int count = Math.multiplyExact(nodeCount - 1, 8);
            if (count > 0) {
                segments.add(new Segment(words, nodes, count, offset));
                words = Math.addExact(words, count);
            }
            return offset;
        }

        int nodeCount() { return words / 8; }

        /** The top tree's references must already address the final flat layout, including its own nodes. */
        FarDagUpload finish(ReferenceDag top) {
            int count = (top.nodeCount() - 1) * 8;
            if (count > 0) segments.add(new Segment(words, top.nodes(), count, 0));
            return new FarDagUpload(List.copyOf(segments), Math.addExact(words, count));
        }
    }

    private final List<Segment> segments;
    private final int words;

    private FarDagUpload(List<Segment> segments, int words) {
        this.segments = segments;
        this.words = words;
    }

    int words() { return words; }

    /** Copies exactly destination.remaining() words beginning at firstWord, including slices through node boundaries. */
    void copy(int firstWord, IntBuffer destination) {
        int count = destination.remaining();
        java.util.Objects.checkFromIndexSize(firstWord, count, words);
        int end = firstWord + count;
        while (firstWord < Math.min(8, end)) {
            destination.put(0);
            firstWord++;
        }
        // Slices are large and the number of column segments is small relative to their payloads.
        for (Segment segment : segments) {
            int from = Math.max(firstWord, segment.start);
            int to = Math.min(end, segment.start + segment.words);
            if (from >= to) continue;
            int source = 8 + from - segment.start;
            if (segment.referenceOffset == 0) {
                destination.put(segment.nodes, source, to - from);
            } else {
                for (int i = source; i < source + to - from; i++) {
                    int reference = segment.nodes[i];
                    destination.put(reference == 0 || (reference & ReferenceDag.LEAF) != 0
                            ? reference : reference + segment.referenceOffset);
                }
            }
            if (to == end) break;
        }
    }
}
