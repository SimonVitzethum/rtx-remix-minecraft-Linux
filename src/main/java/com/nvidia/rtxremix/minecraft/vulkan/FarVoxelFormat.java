/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/** Shared colour and spatial encoding for the native voxel service and GPU address directory. */
final class FarVoxelFormat {
    static final int COLUMN_LEVEL = 8;
    private FarVoxelFormat() {}

    static int scale(int rgb, float factor) {
        int red = Math.min(255, (int) (((rgb >> 16) & 255) * factor));
        int green = Math.min(255, (int) (((rgb >> 8) & 255) * factor));
        int blue = Math.min(255, (int) ((rgb & 255) * factor));
        return (red << 16) | (green << 8) | blue;
    }

    /** Zero remains the exact-voxel air marker. */
    static short rgb565(int rgb) {
        int red = (rgb >> 19) & 31, green = (rgb >> 10) & 63, blue = (rgb >> 3) & 31;
        return (short) Math.max((red << 11) | (green << 5) | blue, 1);
    }
}
