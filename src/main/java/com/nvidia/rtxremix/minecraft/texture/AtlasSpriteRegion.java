/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** The content rectangle of a sprite, excluding the stitcher's outer padding. */
public record AtlasSpriteRegion(int x, int y, int width, int height) {
    /** Resolves the same content origin used by vanilla's UVs and animated uploads. */
    public static AtlasSpriteRegion from(TextureAtlasSprite sprite, int atlasWidth, int atlasHeight) {
        return new AtlasSpriteRegion(Math.round(sprite.getU0() * atlasWidth),
                Math.round(sprite.getV0() * atlasHeight), sprite.contents().width(), sprite.contents().height());
    }

    /** Copies the first frame from a potentially multi-column image sheet into an RGBA atlas. */
    void copyRgba(byte[] atlas, int atlasWidth, NativeImage image) {
        int[] source = image.getPixelsABGR();
        int sourceWidth = image.getWidth();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int abgr = source[row * sourceWidth + column];
                int target = ((y + row) * atlasWidth + x + column) * 4;
                atlas[target] = (byte) abgr;
                atlas[target + 1] = (byte) (abgr >>> 8);
                atlas[target + 2] = (byte) (abgr >>> 16);
                atlas[target + 3] = (byte) (abgr >>> 24);
            }
        }
    }
}
