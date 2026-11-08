/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;

import java.util.Set;

/**
 * Picks the interop by the backend Minecraft actually runs and reaches the Vulkan objects behind the
 * frontend wrappers. Nothing here loads a Vulkan class unless the device reports the Vulkan backend.
 */
public final class VulkanBackendSupport {
    /** {@code DeviceInfo.backendName()} of the Vulkan backend. */
    public static final String BACKEND_NAME = "Vulkan";

    /** Device extensions the interop adds to Minecraft's device (see VulkanFeatureSetsMixin). */
    public static final Set<String> INTEROP_EXTENSIONS = Set.of(
            "VK_KHR_external_memory",
            "VK_KHR_external_memory_fd",
            "VK_KHR_external_semaphore",
            "VK_KHR_external_semaphore_fd");

    private VulkanBackendSupport() {
    }

    /** True when Minecraft's device is running on the Vulkan backend. */
    public static boolean isActive() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device != null && BACKEND_NAME.equals(device.getDeviceInfo().backendName());
    }

    /** True when Minecraft's Vulkan device was created with every extension the interop needs. */
    public static boolean deviceHasInteropExtensions() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }
        Set<String> enabled = device.getDeviceInfo().underlyingExtensions();
        for (String extension : INTEROP_EXTENSIONS) {
            // DeviceInfo tags device extensions with " (D)" and instance extensions with " (I)".
            if (!enabled.contains(extension + " (D)")) {
                return false;
            }
        }
        return true;
    }

    /** The Vulkan device; throws when Minecraft does not run on Vulkan. */
    public static VulkanDevice device() {
        GpuDevice device = RenderSystem.getDevice();
        if (device instanceof FrontendGpuDevice frontend) {
            GpuDeviceBackend backend = frontend.backend;
            if (backend instanceof VulkanDevice vulkan) {
                return vulkan;
            }
        }
        throw new IllegalStateException("Minecraft does not run on the Vulkan backend");
    }

    /** The Vulkan command encoder behind the frontend encoder, the one whose submission carries the frame. */
    public static VulkanCommandEncoder encoder() {
        if (RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend
                && frontend.backend() instanceof VulkanCommandEncoder vulkan) {
            return vulkan;
        }
        throw new IllegalStateException("Minecraft does not run on the Vulkan backend");
    }

    /** The VkImage behind the color attachment of a render target. */
    public static long colorImage(RenderTarget target) {
        GpuTexture color = target == null ? null : target.getColorTexture();
        if (color instanceof VulkanGpuTexture vulkan) {
            return vulkan.vkImage();
        }
        throw new IllegalStateException("The render target has no Vulkan color texture");
    }
}
