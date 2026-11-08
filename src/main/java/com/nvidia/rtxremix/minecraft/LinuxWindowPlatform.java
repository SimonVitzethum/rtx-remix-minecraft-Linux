/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.sdl.SDLHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the SDL video driver on Linux before Minecraft initializes SDL.
 *
 * <p>Remix presents into the game window with its own Vulkan swapchain, so it needs the native handle of whichever
 * driver SDL runs on: a {@code wl_surface} on Wayland, an X11 window under X11 or XWayland. Setting
 * {@code -Drtxremix.wayland=true} asks SDL for its {@code wayland} video driver, {@code -Drtxremix.wayland=false}
 * forces {@code x11}. Without the property SDL's own choice (or an {@code SDL_VIDEODRIVER} environment variable)
 * stands.
 */
public final class LinuxWindowPlatform implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxWindowPlatform.class);
    static final String PROPERTY = "rtxremix.wayland";

    @Override
    public void onPreLaunch() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        String requested = System.getProperty(PROPERTY);
        if (requested == null) {
            return;
        }
        boolean wayland = Boolean.parseBoolean(requested);
        if (!SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_DRIVER, wayland ? "wayland" : "x11")) {
            LOGGER.warn("SDL refused the video driver hint");
            return;
        }
        LOGGER.info("SDL video driver requested: {}", wayland ? "wayland" : "x11");
    }
}
