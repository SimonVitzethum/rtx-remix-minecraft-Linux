/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A private OpenGL 4.4 context for the parts of the mod that are OpenGL compute code (the procedural sky), used when
 * Minecraft itself runs on its Vulkan backend and therefore owns no GL context. It lives on a hidden SDL window and
 * stays current on the render thread for the whole session (Vulkan does not care which GL context is current).
 */
public final class SideGlContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(SideGlContext.class);
    // SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN, the flags Minecraft's GL backend gives its hidden utility window (plus OPENGL, which its createWindow adds).
    private static final long HIDDEN_GL_WINDOW = 2L | 2147614728L;

    private static long window;
    private static long context;
    private static volatile int sideWindowId;

    private SideGlContext() {
    }

    /** Creates the context and makes it current on the calling (render) thread. Idempotent. */
    public static synchronized void ensure() {
        if (context != 0L) {
            return;
        }
        if (!SDLVideo.SDL_GL_LoadLibrary(((SharedLibrary) GL.getFunctionProvider()).getPath())) {
            throw new IllegalStateException("Side GL context: SDL_GL_LoadLibrary failed: " + SDLError.SDL_GetError());
        }
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 4);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
        window = SDLVideo.SDL_CreateWindow("RTX Remix side GL context", 64, 64, HIDDEN_GL_WINDOW);
        if (window == 0L) {
            throw new IllegalStateException("Side GL context: window creation failed: " + SDLError.SDL_GetError());
        }
        sideWindowId = SDLVideo.SDL_GetWindowID(window);
        context = SDLVideo.SDL_GL_CreateContext(window);
        if (context == 0L) {
            String error = SDLError.SDL_GetError();
            SDLVideo.SDL_DestroyWindow(window);
            window = 0L;
            throw new IllegalStateException("Side GL context: context creation failed: " + error);
        }
        if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) {
            throw new IllegalStateException("Side GL context: MakeCurrent failed: " + SDLError.SDL_GetError());
        }
        GL.createCapabilities();
        LOGGER.info("Side GL context created for the Vulkan backend (OpenGL 4.4 core, hidden window)");
    }

    /** True when the SDL window id belongs to the side context's hidden window. */
    public static boolean isSideWindow(int windowId) {
        return sideWindowId != 0 && windowId == sideWindowId;
    }

    public static synchronized void destroy() {
        if (context != 0L) {
            SDLVideo.SDL_GL_MakeCurrent(window, 0L);
            SDLVideo.SDL_GL_DestroyContext(context);
            SDLVideo.SDL_DestroyWindow(window);
            context = 0L;
            window = 0L;
            sideWindowId = 0;
        }
    }
}
