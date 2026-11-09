/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;

import java.nio.ByteBuffer;

/**
 * The procedural sky panorama generator behind {@link SkyRenderer}. Two implementations exist: the Vulkan compute
 * generator ({@code vulkan.VulkanSkySource}, used whenever Minecraft runs on its Vulkan backend, no OpenGL involved)
 * and the OpenGL compute generator ({@link AtmosphericSkyGenerator}, only loaded on the OpenGL backend).
 * Render thread only.
 */
public interface SkyGenerator {
    int WIDTH = 256;
    int HEIGHT = 128;
    /** Bytes of the published RGBA16F panorama. */
    int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * Short.BYTES;

    /**
     * Regenerates the atmospheric sky, or skips the work when nothing that feeds it changed.
     *
     * @param publishedOutputValid whether the caller still holds a live texture and dome light from a previous call
     * @return the completed readback (RGBA16F, rows top first, position 0, limit {@link #OUTPUT_BYTES}), or null when
     *     there is nothing new to publish
     */
    ByteBuffer generate(SkyLightState lightState, float[] upperMean, float mieScatteringAnisotropy,
            boolean publishedOutputValid);

    void destroy();

    /** Creates the generator that matches Minecraft's graphics backend. */
    static SkyGenerator create() {
        if (com.nvidia.rtxremix.minecraft.vulkan.VulkanBackendSupport.isActive()) {
            return new com.nvidia.rtxremix.minecraft.vulkan.VulkanSkySource();
        }
        return new AtmosphericSkyGenerator();
    }
}
