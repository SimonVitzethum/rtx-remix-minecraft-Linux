/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Raises the OpenGL context the window is created with to what the Remix GL interop needs.
 * Core-profile 4.4: the GL interop imports Remix's images and semaphores through
 * {@code EXT_memory_object} and {@code EXT_semaphore}. The backend asks SDL for 3.3 core.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {
    /** {@code SDL_GL_CONTEXT_MAJOR_VERSION}. */
    private static final int rtxremix$CONTEXT_MAJOR = 17;
    /** {@code SDL_GL_CONTEXT_MINOR_VERSION}. */
    private static final int rtxremix$CONTEXT_MINOR = 18;

    @WrapOperation(
        method = "createWindow",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z", remap = false)
    )
    private boolean rtxremix$requireOpenGl44(int attribute, int value, Operation<Boolean> original) {
        if (attribute == rtxremix$CONTEXT_MAJOR) {
            value = Math.max(value, 4);
        } else if (attribute == rtxremix$CONTEXT_MINOR) {
            value = Math.max(value, 4);
        }
        return original.call(attribute, value);
    }
}
