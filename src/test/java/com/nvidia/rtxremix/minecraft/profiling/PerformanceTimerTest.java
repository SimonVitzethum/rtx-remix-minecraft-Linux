/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceTimerTest {
    @Test void recreatedWorldServicesReuseTheSameCounterSlot() {
        PerformanceTimer timer = new PerformanceTimer();
        int counter = timer.registerCounter("Far.UploadBytes");
        for (int world = 0; world < 200; world++) assertEquals(counter, timer.registerCounter("Far.UploadBytes"));
        assertEquals(1, timer.getCounterCount());
        timer.beginFrame(); timer.count(counter, 64); timer.endFrame();
        assertEquals(64, timer.getCounterValue(counter));
    }
}
