/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vulkan counterpart of {@link RenderSystemMixin}: {@code Minecraft.renderFrame} submits the frame and then calls
 * {@code VulkanGpuSurface.present}. The Remix present runs at the head of it, after the frame's submission,
 * so the render target already holds the finished frame. Minecraft's own swapchain present still follows.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Inject(method = "present", at = @At("HEAD"))
    private void rtxremix$presentThroughRemix(CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized() && renderer.usesVulkanInterop()) {
            renderer.presentFrameVk();
        }
    }
}
