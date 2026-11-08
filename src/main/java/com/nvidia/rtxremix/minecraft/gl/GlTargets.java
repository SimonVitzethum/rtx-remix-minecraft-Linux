/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45.glNamedFramebufferTexture;

/**
 * Reaches the OpenGL objects behind Minecraft's render targets. Since the renderpearl backends, a
 * {@link RenderTarget} no longer owns a framebuffer id: it owns a {@link GpuTexture}, and the OpenGL
 * backend creates framebuffers on demand and caches them privately. The interop therefore reads and
 * writes the color texture through framebuffers of its own, attached with direct state access so that
 * Minecraft's cached framebuffer bindings are never disturbed.
 */
public final class GlTargets {
    private GlTargets() {
    }

    /** Returns the GL texture name of a render target's color attachment, or 0 when it has none or is not OpenGL. */
    public static int colorTextureId(RenderTarget target) {
        GpuTexture color = target == null ? null : target.getColorTexture();
        return color instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }

    /** A framebuffer of the interop's own whose single color attachment is re-pointed at a texture on demand. */
    public static final class Attachment {
        private int fbo;

        /**
         * Returns the framebuffer with {@code texture} attached. The attachment is rewritten on every call
         * because a deleted texture's name can be reused by the next one Minecraft creates, for example on resize.
         */
        public int fboFor(int texture) {
            if (fbo == 0) {
                fbo = glCreateFramebuffers();
            }
            glNamedFramebufferTexture(fbo, GL_COLOR_ATTACHMENT0, texture, 0);
            return fbo;
        }

        public void destroy() {
            if (fbo != 0) {
                glDeleteFramebuffers(fbo);
                fbo = 0;
            }
        }
    }
}
