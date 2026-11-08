/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.nvidia.rtxremix.minecraft.texture.GlyphUploadHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * The 26.3 replacement for the 1.21 {@code GlStateManager._upload} and
 * {@code NativeImage._upload} glyph hooks. Every texture write now goes through the frontend
 * command encoder, whatever the backend; while a glyph upload is in progress
 * ({@code GlyphAtlasTextureMixin}), the bytes are mirrored into the glyph page. The
 * {@code NativeImage} overloads of {@code writeToTexture} funnel into the byte overload, so
 * hooking it covers them.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class GlyphUploadEncoderMixin {

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorGlyphBytes(GpuTexture destination, ByteBuffer source, int mipLevel,
                                           int depthOrLayer, int destX, int destY, int width,
                                           int height, CallbackInfo ci) {
        GlyphUploadHook.onWriteToTexture(destination, source, mipLevel, destX, destY, width, height);
    }

    @Inject(method = "copyBufferToTexture", at = @At("HEAD"))
    private void rtxremix$mirrorGlyphCopy(GpuBufferSlice source, int sourceX, int sourceY,
                                          int sourceWidth, int sourceHeight, GpuTexture destination,
                                          int destinationX, int destinationY, int copyWidth,
                                          int copyHeight, int mipLevel, int arrayLayer, CallbackInfo ci) {
        GlyphUploadHook.onCopyBufferToTexture(source, sourceX, sourceY, sourceWidth, destination,
                destinationX, destinationY, copyWidth, copyHeight, mipLevel);
    }
}
