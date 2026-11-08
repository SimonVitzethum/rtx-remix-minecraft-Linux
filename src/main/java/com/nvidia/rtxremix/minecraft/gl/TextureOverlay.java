/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;

/**
 * Replaces Minecraft's main render target color with the Remix output texture, fully opaque,
 * after the world and first-person model have been submitted and before the screen overlays and HUD.
 *
 * <p>Render thread only. The renderpearl OpenGL backend has no immediate-mode shader state to borrow
 * for a textured quad, and the composite is an opaque copy anyway, so it is one direct-state-access
 * blit between two framebuffers owned by this class. Neither touches Minecraft's cached bindings.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private final Minecraft client;
    private final GlTargets.Attachment source = new GlTargets.Attachment();
    private final GlTargets.Attachment destination = new GlTargets.Attachment();

    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Copies a texture over the whole main render target.
     *
     * @param textureId The GL texture ID of the Remix color image
     */
    public void render(int textureId) {
        if (textureId <= 0 || client == null || client.getWindow() == null) {
            return;
        }
        try {
            blit(textureId);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void blit(int texId) {
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        int targetTexture = GlTargets.colorTextureId(target);
        if (targetTexture == 0) {
            return;
        }
        int width = target.width;
        int height = target.height;
        int read = source.fboFor(texId);
        int draw = destination.fboFor(targetTexture);
        // A blit honours the scissor box that GUI clipping leaves enabled.
        GlStateManager._disableScissorTest();
        // The Remix image has Vulkan orientation (row 0 at the top), so the copy flips it into
        // GL's bottom-up framebuffer rows.
        glBlitNamedFramebuffer(read, draw, 0, 0, width, height, 0, height, width, 0,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    /**
     * Cleans up resources.
     */
    public void shutdown() {
        source.destroy();
        destination.destroy();
        LOGGER.info("TextureOverlay shutdown");
    }
}
