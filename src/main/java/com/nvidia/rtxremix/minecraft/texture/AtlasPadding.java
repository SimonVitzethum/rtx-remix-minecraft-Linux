/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** Replicates sprite edges into unoccupied atlas texels, with identical ownership in every channel. */
final class AtlasPadding {
    record Region(int x, int y, int width, int height) {}

    private AtlasPadding() {}

    /** Channels contain tightly packed texels; their byte stride follows from the atlas dimensions. */
    static void apply(int width, int height, int radius, List<Region> regions, byte[]... channels) {
        int pixels = Math.multiplyExact(width, height);
        int[] strides = new int[channels.length];
        for (int i = 0; i < channels.length; i++) {
            if (channels[i].length == 0 || channels[i].length % pixels != 0) {
                throw new IllegalArgumentException("Atlas channel does not contain whole texels");
            }
            strides[i] = channels[i].length / pixels;
        }
        // Shared gaps have a stable owner even when the sprite map's iteration order changes.
        List<Region> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.comparingInt(Region::y).thenComparingInt(Region::x)
                .thenComparingInt(Region::height).thenComparingInt(Region::width));
        BitSet occupied = new BitSet(pixels);
        for (Region r : ordered) {
            if (r.x < 0 || r.y < 0 || r.width <= 0 || r.height <= 0
                    || r.x + r.width > width || r.y + r.height > height) {
                throw new IllegalArgumentException("Sprite rectangle lies outside the atlas");
            }
            for (int y = r.y; y < r.y + r.height; y++) {
                occupied.set(y * width + r.x, y * width + r.x + r.width);
            }
        }
        for (Region r : ordered) {
            int x0 = Math.max(0, r.x - radius), x1 = Math.min(width, r.x + r.width + radius);
            int y0 = Math.max(0, r.y - radius), y1 = Math.min(height, r.y + r.height + radius);
            for (int y = y0; y < y1; y++) {
                int sourceY = Math.clamp(y, r.y, r.y + r.height - 1);
                for (int x = x0; x < x1; x++) {
                    if (y >= r.y && y < r.y + r.height && x == r.x) {
                        x = r.x + r.width - 1;
                        continue;
                    }
                    int destination = y * width + x;
                    if (occupied.get(destination)) continue;
                    int source = sourceY * width + Math.clamp(x, r.x, r.x + r.width - 1);
                    for (int i = 0; i < channels.length; i++) {
                        System.arraycopy(channels[i], source * strides[i], channels[i], destination * strides[i], strides[i]);
                    }
                    occupied.set(destination);
                }
            }
        }
    }
}
