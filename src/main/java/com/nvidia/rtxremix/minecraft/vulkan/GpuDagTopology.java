/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.util.Map;

/** Small address directory above GPU-resident subtrees; no subtree node payload is read or copied. */
final class GpuDagTopology {
    // Device node addresses stay below bit28; bit30 distinguishes local directory references until allocation.
    private static final int LOCAL = 0x40000000;

    record Result(int root, int[] nodes) {
        int rootAt(int offset) { return relocate(root, offset); }
        int[] nodesAt(int offset) {
            int[] result = nodes.clone();
            for (int i = 8; i < result.length; i++) result[i] = relocate(result[i], offset);
            return result;
        }
    }

    static Result build(Map<Long, int[]> roots, int levels, int originX, int originZ, int slabs) {
        FarFieldDag dag = new FarFieldDag();
        int root = top(dag, roots, levels, 0, 0, 0, originX, originZ, slabs);
        return new Result(root, dag.nodes());
    }

    private static int relocate(int reference, int offset) {
        return reference > 0 && (reference & LOCAL) != 0 ? (reference & ~LOCAL) + offset : reference;
    }

    private static int top(FarFieldDag dag, Map<Long, int[]> roots, int level, int x, int y, int z,
                           int originX, int originZ, int slabs) {
        if (level == FarVoxelFormat.COLUMN_LEVEL) {
            int[] column = roots.get(((long) (originX + x) << 32) | ((originZ + z) & 0xFFFFFFFFL));
            return column == null || y >= slabs ? 0 : column[y];
        }
        int half = 1 << (level - 1 - FarVoxelFormat.COLUMN_LEVEL);
        int[] children = new int[8];
        for (int i = 0; i < 8; i++) {
            int childY = y + ((i >> 1) & 1) * half;
            if (childY < slabs) children[i] = top(dag, roots, level - 1, x + (i & 1) * half, childY,
                    z + ((i >> 2) & 1) * half, originX, originZ, slabs);
        }
        int reference = dag.make(children);
        return reference == 0 || (reference & FarFieldDag.LEAF) != 0 ? reference : reference | LOCAL;
    }
}
