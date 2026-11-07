/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the GLFW window platform on Linux before Minecraft initializes GLFW.
 *
 * <p>Remix presents into the game window with its own Vulkan swapchain, so it needs the native handle of whichever
 * platform GLFW runs on: a {@code wl_surface} on Wayland, an X11 window under X11 or XWayland. Setting
 * {@code -Drtxremix.wayland=true} asks GLFW for the Wayland platform, which needs a GLFW build with Wayland support
 * (a system GLFW 3.4 through {@code -Dorg.lwjgl.glfw.libname=/usr/lib/libglfw.so}, or LWJGL's {@code glfw_wayland}).
 * {@code -Drtxremix.wayland=false} forces X11. Without the property GLFW's own choice stands.
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
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, wayland ? GLFW.GLFW_PLATFORM_WAYLAND : GLFW.GLFW_PLATFORM_X11);
        LOGGER.info("GLFW window platform requested: {}", wayland ? "Wayland" : "X11");
    }
}
