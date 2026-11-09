/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanWeatherPass;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Vulkan backend: vanilla's rain and snow draw lands in the render target that the Remix composite overwrites, so it is
 * cancelled and the column quads that {@code prepare} builds are drawn by {@link VulkanWeatherPass} on top of the
 * composited image instead (occluded by Remix's depth). Nothing changes on the OpenGL backend.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Shadow
    private @Nullable AbstractTexture rainTexture;
    @Shadow
    private @Nullable AbstractTexture snowTexture;

    @Inject(method = "uploadVertexBuffer", at = @At("HEAD"))
    private void rtxremix$captureVertices(ByteBuffer buffer, CallbackInfo ci) {
        VulkanWeatherPass pass = VulkanWeatherPass.get();
        if (pass != null && RtxRemixMod.isRemixWorldRendering()) {
            pass.offerVertices(buffer);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("HEAD"), cancellable = true)
    private void rtxremix$drawThroughRemix(WeatherRenderState state, RenderPass renderPass, CallbackInfo ci) {
        rtxremix$handOver(state, ci);
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$drawThroughRemixOit(OitStage stage, WeatherRenderState state, RenderPass renderPass, CallbackInfo ci) {
        rtxremix$handOver(state, ci);
    }

    private void rtxremix$handOver(WeatherRenderState state, CallbackInfo ci) {
        VulkanWeatherPass pass = VulkanWeatherPass.get();
        if (pass != null && RtxRemixMod.isRemixWorldRendering()) {
            pass.offerDraw(state.rainColumns.size(), state.snowColumns.size(), rainTexture, snowTexture);
            ci.cancel();
        }
    }
}
