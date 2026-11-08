/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanBackendSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Adds the external memory and semaphore (opaque file descriptor) extensions to the optional feature sets of
 * Minecraft's Vulkan backend, so the VkDevice it creates can import the images and timeline semaphores Remix
 * exports. An optional set is enabled only when the physical device supports every extension in it, so a GPU
 * without them still starts Minecraft; the Vulkan interop then reports the missing extensions.
 */
@Mixin(VulkanFeatureSets.class)
public abstract class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"))
    private static void rtxremix$addInteropExtensions(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        cir.getReturnValue().add(new FeatureSet(
                "RTX Remix interop", VulkanBackendSupport.INTEROP_EXTENSIONS, Set.of()));
    }
}
