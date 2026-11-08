/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.nvidia.rtxremix.minecraft.texture.GlyphUploadHook;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A bitmap font (the default one included) copies glyphs into its page from a GPU staging
 * buffer, so the CPU image the buffer was filled from is the only place the pixels exist for
 * the glyph page mirror. Links each staging buffer to that image; see {@link GlyphUploadHook}.
 */
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder")
public abstract class BitmapFontImageMixin {

    @Shadow
    @Final
    private NativeImage image;

    @Inject(method = "gpuData", at = @At("RETURN"))
    private void rtxremix$registerStagingSource(CallbackInfoReturnable<GpuBuffer> cir) {
        GlyphUploadHook.registerStagingSource(cir.getReturnValue(), image);
    }
}
