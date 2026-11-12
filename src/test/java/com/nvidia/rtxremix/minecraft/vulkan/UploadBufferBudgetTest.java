/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadBufferBudgetTest {
    @Test void growsGeometricallyAndKeepsCapacityAcrossMovementBursts() {
        UploadBufferBudget policy = new UploadBufferBudget(64, 4096, 8192);
        long capacity = 0;
        int growth = 0;
        for (int bytes = 1; bytes <= 4096; bytes++) {
            long next = policy.capacity(capacity, bytes, 0);
            if (capacity != next) growth++;
            capacity = next;
            assertEquals(capacity, policy.capacity(capacity, 1, 0));
        }
        assertEquals(7, growth);
        assertEquals(4096, capacity);
    }

    @Test void countsOldNewAndOtherInflightBytesBeforeAdmittingGrowth() {
        UploadBufferBudget policy = new UploadBufferBudget(64, 4096, 6144);
        assertEquals(4096, policy.capacity(2048, 2049, 0));
        assertThrows(IllegalStateException.class, () -> policy.capacity(2048, 2049, 1));
        assertThrows(IllegalStateException.class, () -> policy.capacity(0, 4097, 0));
    }
}
