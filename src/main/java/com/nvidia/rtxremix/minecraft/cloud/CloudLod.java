/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

/**
 * Distance rings for the cloud palette's coarser meshes. The first ring boundary is
 * user-configurable; each further boundary is a fixed multiple of it. A cloud keeps its
 * level until it crosses a boundary by {@link #HYSTERESIS}, so levels never flicker while
 * wind or the camera moves a cloud across a ring edge.
 */
public final class CloudLod {
    public static final int DEFAULT_DISTANCE = 256;
    public static final int MAX_DISTANCE = 1152;
    static final double HYSTERESIS = 0.08;
    private static final double[] RING_MULTIPLIER = {1.0, 2.2};

    private static volatile int distance = readDefault();

    private CloudLod() {
    }

    private static int readDefault() {
        return Math.max(0, Math.min(MAX_DISTANCE,
                com.nvidia.rtxremix.minecraft.util.LodOverrides.get(
                        "rtxremix.cloud.lodDistance", DEFAULT_DISTANCE)));
    }

    /** First ring boundary in blocks; 0 disables the coarser levels. */
    public static int getDistance() {
        return distance;
    }

    public static void setDistance(int blocks) {
        distance = Math.max(0, Math.min(MAX_DISTANCE, blocks));
    }

    /** Number of levels the palette must provide (1 when disabled). */
    static int levelCount() {
        return distance == 0 ? 1 : CloudMeshGenerator.LOD_COUNT;
    }

    /** Level for {@code horizontalDistance}, given the cloud's previous level. */
    static int select(double horizontalDistance, int previousLevel, int baseDistance, int maxLevel) {
        if (baseDistance <= 0) {
            return 0;
        }
        int level = previousLevel;
        int top = Math.min(maxLevel, RING_MULTIPLIER.length);
        while (level < top
                && horizontalDistance > baseDistance * RING_MULTIPLIER[level] * (1.0 + HYSTERESIS)) {
            level++;
        }
        while (level > 0
                && horizontalDistance < baseDistance * RING_MULTIPLIER[level - 1] * (1.0 - HYSTERESIS)) {
            level--;
        }
        return level;
    }
}
