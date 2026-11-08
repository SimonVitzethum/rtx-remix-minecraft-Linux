/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A quad's block tint multiplies into its vertex color as Fabric's renderer does. Capture
 * itself runs on Fabric's real quad emitter in 26.3, so only the multiplication is ours.
 */
class FrapiBlockTintTest {

    @Test
    void theTintMultipliesEachChannelAndKeepsTheVertexAlpha() {
        assertEquals(0xFF7FB238, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0x7FB238));
        // 0x80 * 0x80 / 0xFF rounds down to 0x40.
        assertEquals(0x40404040, BlockQuadEmitter.tintFrapiColor(0x40808080, 0x808080));
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, 0xFFFFFF));
    }

    @Test
    void aBlockWithoutATintSourceKeepsTheVertexColor() {
        // Tint -1 is white: the answer for an index the block has no source for.
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, -1));
    }
}
