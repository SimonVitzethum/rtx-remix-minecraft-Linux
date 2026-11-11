/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasPaddingTest {
    @Test
    void paddingIsDeterministicAndPreservesNeighbourSpritesAndCutoutAlpha() {
        var left = new AtlasPadding.Region(0, 0, 2, 2);
        var right = new AtlasPadding.Region(3, 0, 2, 2);
        byte[] height = {10, 11, 0, 20, 21, 12, 13, 0, 22, 23, 0, 0, 0, 0, 0};
        byte[] rgba = new byte[height.length * 4];
        for (int i = 0; i < height.length; i++) {
            Arrays.fill(rgba, i * 4, i * 4 + 4, height[i]);
        }
        rgba[7] = 0; // A transparent edge texel is still owned by its sprite.
        byte[] expectedHeight = height.clone();
        byte[] expectedRgba = rgba.clone();
        AtlasPadding.apply(5, 3, 1, List.of(right, left), height, rgba);
        AtlasPadding.apply(5, 3, 1, List.of(left, right), expectedHeight, expectedRgba);
        assertArrayEquals(expectedHeight, height);
        assertArrayEquals(expectedRgba, rgba);
        assertEquals(11, height[2]);
        assertEquals(0, rgba[2 * 4 + 3]);
        assertEquals(20, height[3]);
        assertEquals(23, height[14]);
        assertEquals(13, height[12]);
    }
}
