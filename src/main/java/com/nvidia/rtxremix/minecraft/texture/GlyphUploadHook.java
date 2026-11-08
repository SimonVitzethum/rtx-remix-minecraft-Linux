/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Feeds {@link GlyphPageMirror} from the 26.3 upload paths. A glyph reaches its page
 * through the frontend command encoder, either as bytes ({@code writeToTexture}: TrueType,
 * unihex and special glyphs) or as a GPU-to-texture copy out of a staging buffer
 * ({@code copyBufferToTexture}: bitmap fonts, which include the default font). For the copy
 * the bytes only exist on the GPU, so the bitmap provider registers the CPU image behind each
 * staging buffer here. Render thread only, like every glyph upload.
 */
public final class GlyphUploadHook {
    private static final Map<GpuBuffer, NativeImage> STAGING_SOURCES = new WeakHashMap<>();

    private GlyphUploadHook() {
    }

    /** Records the CPU image a bitmap font's staging buffer was filled from. */
    public static void registerStagingSource(GpuBuffer buffer, NativeImage image) {
        STAGING_SOURCES.put(buffer, image);
    }

    /** A byte upload into {@code destination}: mirrored when a glyph upload is in progress. */
    public static void onWriteToTexture(GpuTexture destination, ByteBuffer source, int mipLevel,
                                        int destX, int destY, int width, int height) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        GpuFormat format = destination.getFormat();
        int channels = format.blockSize();
        page.blit(destX, destY, width, height, channels, source.slice(), width * channels);
    }

    /** A staging-buffer copy into {@code destination}: mirrored from the registered CPU image. */
    public static void onCopyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY,
                                             int sourceWidth, GpuTexture destination,
                                             int destX, int destY, int copyWidth, int copyHeight,
                                             int mipLevel) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        NativeImage image = STAGING_SOURCES.get(source.buffer());
        if (image == null || image.isClosed()) {
            return;
        }
        int channels = image.format().components();
        int rowPitch = sourceWidth * channels;
        long first = image.getPointer() + ((long) sourceY * sourceWidth + sourceX) * channels;
        int span = (copyHeight - 1) * rowPitch + copyWidth * channels;
        page.blit(destX, destY, copyWidth, copyHeight, channels,
                MemoryUtil.memByteBuffer(first, span), rowPitch);
    }
}
