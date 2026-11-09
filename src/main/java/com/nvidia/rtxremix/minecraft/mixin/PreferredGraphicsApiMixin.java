/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Vulkan the default graphics backend. Vanilla tries OpenGL first for the "Default" setting and Vulkan second;
 * with the mod installed "Default" tries Vulkan first and falls back to OpenGL when Vulkan cannot be created (no loader,
 * missing features, device creation failure), which is vanilla's own fallback loop in {@code Minecraft.<init>}. An
 * explicit choice in the video settings ("OpenGL" or "Vulkan") or the launch argument is honoured unchanged, and
 * vanilla's reset to OpenGL after an unclean start still applies. Set {@code -Drtxremix.defaultBackend=opengl} to keep
 * vanilla's order for "Default".
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void rtxremix$vulkanFirstForDefault(CallbackInfoReturnable<GpuBackend[]> cir) {
        if ((Object) this == PreferredGraphicsApi.DEFAULT
                && !"opengl".equalsIgnoreCase(System.getProperty("rtxremix.defaultBackend"))) {
            cir.setReturnValue(new GpuBackend[]{new VulkanBackend(), new GlBackend()});
        }
    }
}
