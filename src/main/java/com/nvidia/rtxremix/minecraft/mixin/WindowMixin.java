/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.vulkan.SideGlContext;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs the SDL window handle once the window exists. The OpenGL context version the window is
 * created with is raised by {@link GlBackendMixin}, which owns the attribute calls.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    /** Logs the SDL window handle once the window exists. */
    @Inject(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V",
        at = @At("TAIL")
    )
    private void rtxremix$onWindowCreated(CallbackInfo ci) {
        RtxRemixMod.LOGGER.info("Window created with SDL handle: {}", ((Window) (Object) this).handle());
    }

    /**
     * Window events (SDL 514..536) of the mod's hidden side GL window (Vulkan backend) must not resize or
     * unfocus Minecraft's window: the handler does not check the window id.
     */
    @Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
    private void rtxremix$ignoreSideWindowEvents(SDL_Event event, CallbackInfo ci) {
        int type = event.type();
        if (type >= 514 && type <= 536 && SideGlContext.isSideWindow(event.window().windowID())) {
            ci.cancel();
        }
    }
}
