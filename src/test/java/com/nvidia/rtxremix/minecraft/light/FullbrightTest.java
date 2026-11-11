/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FullbrightTest {
    @Test
    void transitionTakesEightTenthsAndReversesContinuously() {
        float blend = Fullbright.advanceBlend(0, true, 0.4f);
        assertEquals(0.5f, blend, 0.00001f);
        blend = Fullbright.advanceBlend(blend, false, 0.2f);
        assertEquals(0.25f, blend, 0.00001f);
        assertEquals(0, Fullbright.advanceBlend(blend, false, 0.2f), 0.00001f);
        assertEquals(1, Fullbright.advanceBlend(0, true, 0.8f), 0.00001f);
    }

    @Test
    void transitionIsFrameRateIndependentAndClamped() {
        float blend = 0;
        for (int i = 0; i < 48; i++) blend = Fullbright.advanceBlend(blend, true, 1.0f / 60);
        assertEquals(1, blend, 0.00001f);
        assertEquals(1, Fullbright.advanceBlend(blend, true, 10));
        assertEquals(0, Fullbright.advanceBlend(blend, false, 10));
        assertEquals(0.5f, Fullbright.advanceBlend(0.5f, false, -1));
    }
}
