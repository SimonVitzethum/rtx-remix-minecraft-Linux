/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasSpriteRegionTest {
    @Test
    void paddedSpriteCopiesFirstFrameAtItsUvOriginUsingImageRowStride() {
        Identifier id = Identifier.withDefaultNamespace("test/padded");
        NativeImage image = new NativeImage(4, 2, false);
        try (SpriteContents contents = new SpriteContents(id, new FrameSize(2, 2), image)) {
            image.setPixelABGR(0, 0, 0xff030201);
            image.setPixelABGR(1, 0, 0xff060504);
            image.setPixelABGR(2, 0, 0xffeeeeee);
            image.setPixelABGR(3, 0, 0xffdddddd);
            image.setPixelABGR(0, 1, 0xff090807);
            image.setPixelABGR(1, 1, 0xff0c0b0a);
            TextureAtlasSprite sprite = new TextureAtlasSprite(id, contents, 64, 32, 14, 6, 2) {};
            AtlasSpriteRegion region = AtlasSpriteRegion.from(sprite, 64, 32);
            assertEquals(14, sprite.getX());
            assertEquals(6, sprite.getY());
            assertEquals(new AtlasSpriteRegion(16, 8, 2, 2), region);
            byte[] atlas = new byte[64 * 32 * 4];
            region.copyRgba(atlas, 64, image);
            assertEquals(1, atlas[(8 * 64 + 16) * 4]);
            assertEquals(7, atlas[(9 * 64 + 16) * 4]);
            assertEquals(10, atlas[(9 * 64 + 17) * 4]);
            assertEquals(0, atlas[(6 * 64 + 14) * 4 + 3]);
        }
    }
}
