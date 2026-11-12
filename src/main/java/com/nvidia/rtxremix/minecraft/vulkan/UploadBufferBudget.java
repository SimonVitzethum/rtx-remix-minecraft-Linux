/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/** Geometric capacity policy accounting for the old allocation and every other live allocation during replacement. */
record UploadBufferBudget(long minimum, long maximum, long totalBytes) {
    UploadBufferBudget {
        if (minimum <= 0 || maximum < minimum || totalBytes < maximum) throw new IllegalArgumentException("Invalid upload budget");
    }

    long capacity(long current, long required, long otherLive) {
        if (current < 0 || required < 0 || otherLive < 0) throw new IllegalArgumentException("Negative allocation size");
        if (required <= current) return current;
        if (required > maximum) throw new IllegalStateException("Upload allocation exceeds its fixed maximum: " + required + "/" + maximum);
        long next = Math.max(minimum, current);
        while (next < required) next = Math.min(maximum, next * 2);
        if (current > totalBytes - otherLive || next > totalBytes - otherLive - current) {
            throw new IllegalStateException("Upload replacement exceeds live-memory budget: old=" + current
                    + ", new=" + next + ", other=" + otherLive + ", budget=" + totalBytes);
        }
        return next;
    }
}
