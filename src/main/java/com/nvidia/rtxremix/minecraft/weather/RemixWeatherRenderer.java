/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws rain and snow with GL directly onto the composited Remix colour image,
 * depth-testing against the interop depth texture so weather is occluded by
 * path-traced geometry.
 *
 * <p>Render thread only, and after Remix has presented its image into GL: the
 * renderer owns its GL buffers and reads the interop textures the frame just
 * produced.
 *
 * <p>Minecraft 26.3 renders through renderpearl, and the legacy immediate-mode
 * shader bridge (CoreShaderRegistrationCallback, RenderSystem.setShader,
 * BufferUploader.drawWithShader, the vanilla particle shader) is gone. This
 * renderer therefore compiles its own GL programs from the mod's shader
 * resources and draws the captured meshes directly, exactly as the depth
 * seeding pass already did.
 */
public final class RemixWeatherRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final Identifier RAIN_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/snow.png");
    private static final Identifier RAIN_SHADER_VERTEX =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "shaders/core/rain_refraction.vsh");
    private static final Identifier RAIN_SHADER_FRAGMENT =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "shaders/core/rain_refraction.fsh");

    private static final String SNOW_VERTEX_SHADER = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            in vec4 Color;
            in ivec2 UV2;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform vec4 ColorModulator;

            out vec2 texCoord0;
            out vec2 texCoord2;
            out vec4 vertexColor;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                texCoord0 = UV0;
                texCoord2 = vec2(UV2) / 256.0;
                vertexColor = Color * ColorModulator;
            }
            """;

    private static final String SNOW_FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D Sampler0;
            uniform sampler2D Sampler2;

            in vec2 texCoord0;
            in vec2 texCoord2;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor;
                vec4 lightColor = texture(Sampler2, texCoord2);
                fragColor = color * lightColor;
            }
            """;

    private static GlShaderProgram rainShader;
    private static GlShaderProgram snowShader;
    private static boolean rainShaderFailed;
    private static boolean snowShaderFailed;

    private final List<DeferredDraw> draws = new ArrayList<>(2);
    private Matrix4f projection;
    private Matrix4f modelView;
    private Lightmap lightmap;
    private DepthSeeder depthSeeder;
    private int capturedQuadCount;
    private int lastDrawCount;
    private int lastQuadCount;

    private int vertexArray;
    private int vertexBuffer;
    private int indexBuffer;
    private int maxQuadCount;
    private ByteBuffer indexScratch;
    private int mainFramebuffer;
    private int mainFramebufferColor;
    private int mainFramebufferDepth;
    private int mainFramebufferWidth;
    private int mainFramebufferHeight;

    public static void registerShaders() {
        compileShaders();
    }

    private static void compileShaders() {
        if (rainShader == null && !rainShaderFailed) {
            rainShader = compileRainShader();
            if (rainShader == null) {
                rainShaderFailed = true;
            }
        }
        if (snowShader == null && !snowShaderFailed) {
            snowShader = GlShaderProgram.link(SNOW_VERTEX_SHADER, SNOW_FRAGMENT_SHADER);
            if (snowShader == null) {
                snowShaderFailed = true;
            }
        }
    }

    private static GlShaderProgram compileRainShader() {
        String vertexSource = readShaderSource(RAIN_SHADER_VERTEX);
        String fragmentSource = readShaderSource(RAIN_SHADER_FRAGMENT);
        if (vertexSource == null || fragmentSource == null) {
            return null;
        }
        return GlShaderProgram.link(vertexSource, fragmentSource);
    }

    private static String readShaderSource(Identifier location) {
        try (var reader = Minecraft.getInstance().getResourceManager().openAsReader(location)) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to read shader source {}", location, e);
            return null;
        }
    }

    public void beginCaptureFrame(Lightmap lightmapTextureManager) {
        closePendingDraws();
        lightmap = lightmapTextureManager;
        capturedQuadCount = 0;
        lastDrawCount = 0;
        lastQuadCount = 0;
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        Quaternionf cameraRotation = camera.rotation().conjugate(new Quaternionf());
        modelView = new Matrix4f().rotation(cameraRotation);
        projection = new Matrix4f().perspective(
                (float) Math.toRadians(fovYDegrees),
                aspect,
                NEAR_PLANE,
                FAR_PLANE);
    }

    public void capture(MeshData buffer, Identifier texture) {
        if (texture == null) {
            buffer.close();
            throw new IllegalStateException("Weather draw did not bind a texture");
        }
        capturedQuadCount += buffer.drawState().vertexCount() / 4;
        draws.add(new DeferredDraw(buffer, texture, texture.equals(RAIN_TEXTURE)));
    }

    public void render(int finalColorTexture, int depthTexture) {
        if (draws.isEmpty()) {
            return;
        }
        if (depthTexture == 0) {
            closePendingDraws();
            throw new IllegalStateException("Remix depth texture is unavailable");
        }
        if (finalColorTexture == 0) {
            closePendingDraws();
            throw new IllegalStateException("Remix final-color texture is unavailable");
        }
        if (projection == null || modelView == null || lightmap == null) {
            closePendingDraws();
            throw new IllegalStateException("Weather camera state was not initialized");
        }

        Minecraft client = Minecraft.getInstance();
        try {
            if (depthSeeder == null) {
                depthSeeder = new DepthSeeder();
            }
            depthSeeder.seed(
                    depthTexture,
                    client.getWindow().getWidth(),
                    client.getWindow().getHeight());
            lastDrawCount = draws.size();
            lastQuadCount = capturedQuadCount;
            renderDeferredDraws(finalColorTexture);
        } catch (RuntimeException exception) {
            closePendingDraws();
            throw exception;
        }
    }

    private void renderDeferredDraws(int finalColorTexture) {
        int mainFramebuffer = bindMainRenderTarget();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = readScissorBox();
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean[] colorMask = readColorMask();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int previousTexture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int previousTexture2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        int lightmapTexture = resolveLightmapTexture();
        int totalQuads = 0;
        for (DeferredDraw draw : draws) {
            totalQuads += draw.buffer().drawState().vertexCount() / 4;
        }
        ensureQuadStorage(totalQuads);

        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ZERO);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            renderRain(finalColorTexture, lightmapTexture);
            renderSnow(finalColorTexture, lightmapTexture);
        } finally {
            closePendingDraws();
            restoreCapability(GL11.GL_DEPTH_TEST, depthTest);
            restoreCapability(GL11.GL_BLEND, blend);
            restoreCapability(GL11.GL_CULL_FACE, cull);
            restoreCapability(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            GL11.glDepthFunc(depthFunction);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(
                    colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL14.glBlendFuncSeparate(
                    blendSourceRgb,
                    blendDestinationRgb,
                    blendSourceAlpha,
                    blendDestinationAlpha);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            if (previousProgram != 0) {
                GL20.glUseProgram(previousProgram);
            }
            GL30.glBindVertexArray(previousVertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElementBuffer);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture2);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
            GL13.glActiveTexture(previousActiveTexture);
            if (mainFramebuffer != 0) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }
        }
    }

    private void renderRain(int finalColorTexture, int lightmapTexture) {
        compileShaders();
        if (rainShader == null && hasRainDraw()) {
            throw new IllegalStateException("Rain refraction shader was not loaded");
        }
        renderDraws(true, rainShader, finalColorTexture, lightmapTexture);
    }

    private boolean hasRainDraw() {
        for (DeferredDraw draw : draws) {
            if (draw != null && draw.rain()) {
                return true;
            }
        }
        return false;
    }

    private void renderSnow(int finalColorTexture, int lightmapTexture) {
        compileShaders();
        renderDraws(false, snowShader, finalColorTexture, lightmapTexture);
    }

    private void renderDraws(boolean rain, GlShaderProgram shader, int finalColorTexture, int lightmapTexture) {
        if (shader == null) {
            return;
        }
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        shader.bind();
        shader.setModelView(modelView);
        shader.setProjection(projection);
        shader.setColorModulator(1.0f, 1.0f, 1.0f, 1.0f);
        shader.setSampler("Sampler0", 0);
        if (shader.hasSampler("Sampler1")) {
            shader.setSampler("Sampler1", 1);
        }
        if (shader.hasSampler("Sampler2")) {
            shader.setSampler("Sampler2", 2);
        }
        for (int i = 0; i < draws.size(); i++) {
            DeferredDraw draw = draws.get(i);
            if (draw == null || draw.rain() != rain) {
                continue;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bindTextureId(draw.texture()));
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, finalColorTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            try {
                drawMesh(draw.buffer());
            } finally {
                draws.set(i, null);
            }
        }
    }

    private static int bindTextureId(Identifier texture) {
        GpuTextureView view = Minecraft.getInstance().getTextureManager().getTexture(texture).getTextureView();
        if (view instanceof GlTextureView glView) {
            return glView.glId();
        }
        throw new IllegalStateException("Weather renderer requires the OpenGL backend");
    }

    private int resolveLightmapTexture() {
        GpuTextureView view = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (view instanceof GlTextureView glView) {
            return glView.glId();
        }
        throw new IllegalStateException("Weather renderer requires the OpenGL backend");
    }

    private int bindMainRenderTarget() {
        Minecraft client = Minecraft.getInstance();
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        if (!(colorView instanceof GlTextureView glColor)) {
            return 0;
        }
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        int colorId = glColor.glId();
        int depthId = depthView instanceof GlTextureView glDepth ? glDepth.glId() : 0;
        if (mainFramebuffer == 0
                || colorId != mainFramebufferColor
                || depthId != mainFramebufferDepth
                || mainTarget.width != mainFramebufferWidth
                || mainTarget.height != mainFramebufferHeight) {
            if (mainFramebuffer != 0) {
                GL30.glDeleteFramebuffers(mainFramebuffer);
                mainFramebuffer = 0;
            }
            int framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    colorId,
                    0);
            if (depthId != 0) {
                GL30.glFramebufferTexture2D(
                        GL30.GL_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D,
                        depthId,
                        0);
            }
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                GL30.glDeleteFramebuffers(framebuffer);
                return 0;
            }
            mainFramebuffer = framebuffer;
            mainFramebufferColor = colorId;
            mainFramebufferDepth = depthId;
            mainFramebufferWidth = mainTarget.width;
            mainFramebufferHeight = mainTarget.height;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFramebuffer);
        GL11.glViewport(0, 0, mainTarget.width, mainTarget.height);
        return mainFramebuffer;
    }

    private void ensureQuadStorage(int quadCount) {
        if (quadCount > maxQuadCount) {
            maxQuadCount = quadCount;
            ByteBuffer newScratch = BufferUtils.createByteBuffer(quadCount * 6 * 2);
            newScratch.clear();
            for (int quad = 0; quad < quadCount; quad++) {
                int base = quad * 4;
                newScratch.putShort((short) (base + 0));
                newScratch.putShort((short) (base + 1));
                newScratch.putShort((short) (base + 2));
                newScratch.putShort((short) (base + 2));
                newScratch.putShort((short) (base + 3));
                newScratch.putShort((short) (base + 0));
            }
            newScratch.flip();
            indexScratch = newScratch;
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexScratch, GL15.GL_STATIC_DRAW);
        }
    }

    private void drawMesh(MeshData mesh) {
        MeshData.DrawState state = mesh.drawState();
        int vertexCount = state.vertexCount();
        if (vertexCount <= 0) {
            return;
        }
        int vertexSize = state.format().getVertexSize();
        int vertexBytes = vertexCount * vertexSize;
        ByteBuffer vertices = mesh.vertexBuffer();
        if (vertices.remaining() < vertexBytes) {
            throw new IllegalStateException("Weather mesh is smaller than its draw state");
        }
        ByteBuffer upload = vertices.slice(0, vertexBytes);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
        int quadCount = vertexCount / 4;
        GL11.glDrawElements(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_SHORT, 0);
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    private static int[] readScissorBox() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, values);
            return new int[]{values.get(0), values.get(1), values.get(2), values.get(3)};
        }
    }

    private static boolean[] readColorMask() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer values = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, values);
            return new boolean[]{
                    values.get(0) != 0,
                    values.get(1) != 0,
                    values.get(2) != 0,
                    values.get(3) != 0
            };
        }
    }

    private void closePendingDraws() {
        for (DeferredDraw draw : draws) {
            if (draw != null) {
                draw.buffer().close();
            }
        }
        draws.clear();
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    public void cleanup() {
        closePendingDraws();
        if (depthSeeder != null) {
            depthSeeder.destroy();
            depthSeeder = null;
        }
        if (vertexArray != 0) {
            GL30.glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
        if (vertexBuffer != 0) {
            GL15.glDeleteBuffers(vertexBuffer);
            vertexBuffer = 0;
        }
        if (indexBuffer != 0) {
            GL15.glDeleteBuffers(indexBuffer);
            indexBuffer = 0;
        }
        maxQuadCount = 0;
        indexScratch = null;
        if (mainFramebuffer != 0) {
            GL30.glDeleteFramebuffers(mainFramebuffer);
            mainFramebuffer = 0;
        }
    }

    private void ensureGlObjects() {
        if (vertexArray == 0) {
            vertexArray = GL30.glGenVertexArrays();
            vertexBuffer = GL15.glGenBuffers();
            indexBuffer = GL15.glGenBuffers();
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 28, 12);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 28, 20);
            GL20.glEnableVertexAttribArray(3);
            GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, 28, 24L);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    private record DeferredDraw(MeshData buffer, Identifier texture, boolean rain) {
    }

    /**
     * A minimal GL program for the weather passes: compiled from GLSL text,
     * with attribute locations bound by name and a small set of uniforms that
     * the weather shaders share. The renderpearl pipeline compiler is not used
     * because these passes run outside Minecraft's render passes, directly
     * against the composited Remix image.
     */
    private static final class GlShaderProgram implements AutoCloseable {
        private final int program;
        private final int modelViewUniform;
        private final int projectionUniform;
        private final int colorModulatorUniform;
        private final int sampler0Uniform;
        private final int sampler1Uniform;
        private final int sampler2Uniform;

        private GlShaderProgram(int program) {
            this.program = program;
            modelViewUniform = GL20.glGetUniformLocation(program, "ModelViewMat");
            projectionUniform = GL20.glGetUniformLocation(program, "ProjMat");
            colorModulatorUniform = GL20.glGetUniformLocation(program, "ColorModulator");
            sampler0Uniform = GL20.glGetUniformLocation(program, "Sampler0");
            sampler1Uniform = GL20.glGetUniformLocation(program, "Sampler1");
            sampler2Uniform = GL20.glGetUniformLocation(program, "Sampler2");
        }

        static GlShaderProgram link(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = 0;
            int linkedProgram = 0;
            try {
                fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);
                linkedProgram = GL20.glCreateProgram();
                GL20.glAttachShader(linkedProgram, vertexShader);
                GL20.glAttachShader(linkedProgram, fragmentShader);
                GL20.glBindAttribLocation(linkedProgram, 0, "Position");
                GL20.glBindAttribLocation(linkedProgram, 1, "UV0");
                GL20.glBindAttribLocation(linkedProgram, 2, "Color");
                GL20.glBindAttribLocation(linkedProgram, 3, "UV2");
                GL20.glLinkProgram(linkedProgram);
                if (GL20.glGetProgrami(linkedProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    String log = GL20.glGetProgramInfoLog(linkedProgram);
                    GL20.glDeleteProgram(linkedProgram);
                    linkedProgram = 0;
                    throw new IllegalStateException("Failed to link weather shader: " + log);
                }
            } finally {
                GL20.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL20.glDeleteShader(fragmentShader);
                }
            }
            return new GlShaderProgram(linkedProgram);
        }

        private static int compileShader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile weather shader: " + log);
            }
            return shader;
        }

        void bind() {
            GL20.glUseProgram(program);
        }

        boolean hasSampler(String name) {
            return switch (name) {
                case "Sampler0" -> sampler0Uniform != -1;
                case "Sampler1" -> sampler1Uniform != -1;
                case "Sampler2" -> sampler2Uniform != -1;
                default -> false;
            };
        }

        void setSampler(String name, int unit) {
            int location = switch (name) {
                case "Sampler0" -> sampler0Uniform;
                case "Sampler1" -> sampler1Uniform;
                case "Sampler2" -> sampler2Uniform;
                default -> -1;
            };
            if (location != -1) {
                GL20.glUniform1i(location, unit);
            }
        }

        void setModelView(Matrix4f matrix) {
            if (modelViewUniform != -1) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL20.glUniformMatrix4fv(modelViewUniform, false, matrix.get(stack.mallocFloat(16)));
                }
            }
        }

        void setProjection(Matrix4f matrix) {
            if (projectionUniform != -1) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL20.glUniformMatrix4fv(projectionUniform, false, matrix.get(stack.mallocFloat(16)));
                }
            }
        }

        void setColorModulator(float r, float g, float b, float a) {
            if (colorModulatorUniform != -1) {
                GL20.glUniform4f(colorModulatorUniform, r, g, b, a);
            }
        }

        @Override
        public void close() {
            GL20.glDeleteProgram(program);
        }
    }

    private static final class DepthSeeder {
        private static final String VERTEX_SHADER = """
                #version 150
                void main() {
                    vec2 position = vec2(
                        float((gl_VertexID << 1) & 2),
                        float(gl_VertexID & 2));
                    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                }
                """;

        private static final String FRAGMENT_SHADER = """
                #version 150
                uniform sampler2D RemixDepth;
                uniform vec2 FramebufferSize;
                void main() {
                    vec2 uv = vec2(
                        gl_FragCoord.x / FramebufferSize.x,
                        1.0 - gl_FragCoord.y / FramebufferSize.y);
                    gl_FragDepth = texture(RemixDepth, uv).r;
                }
                """;

        private final int program;
        private final int vertexArray;
        private final int remixDepthLocation;
        private final int framebufferSizeLocation;

        private DepthSeeder() {
            int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = 0;
            int linkedProgram = 0;
            try {
                fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
                linkedProgram = GL20.glCreateProgram();
                GL20.glAttachShader(linkedProgram, vertexShader);
                GL20.glAttachShader(linkedProgram, fragmentShader);
                GL20.glLinkProgram(linkedProgram);
                if (GL20.glGetProgrami(linkedProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    String log = GL20.glGetProgramInfoLog(linkedProgram);
                    GL20.glDeleteProgram(linkedProgram);
                    linkedProgram = 0;
                    throw new IllegalStateException("Failed to link depth seeding shader: " + log);
                }
            } finally {
                GL20.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL20.glDeleteShader(fragmentShader);
                }
            }
            program = linkedProgram;
            remixDepthLocation = GL20.glGetUniformLocation(program, "RemixDepth");
            framebufferSizeLocation = GL20.glGetUniformLocation(program, "FramebufferSize");
            vertexArray = GL30.glGenVertexArrays();
        }

        private void seed(int depthTexture, int width, int height) {
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean previousDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            int previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            int[] previousScissorBox = readScissorBox();
            boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean[] previousColorMask = readColorMask();

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                // OpenGL suppresses depth writes entirely when the depth test is disabled.
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDepthMask(true);
                GL11.glColorMask(false, false, false, false);
                GL20.glUseProgram(program);
                GL20.glUniform1i(remixDepthLocation, 0);
                GL20.glUniform2f(framebufferSizeLocation, width, height);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
                GL30.glBindVertexArray(vertexArray);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GL30.glBindVertexArray(previousVertexArray);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL13.glActiveTexture(previousActiveTexture);
                GL20.glUseProgram(previousProgram);
                GL11.glColorMask(
                        previousColorMask[0],
                        previousColorMask[1],
                        previousColorMask[2],
                        previousColorMask[3]);
                GL11.glDepthMask(previousDepthMask);
                GL11.glDepthFunc(previousDepthFunction);
                if (previousDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
                else GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (previousScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                else GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(
                        previousScissorBox[0],
                        previousScissorBox[1],
                        previousScissorBox[2],
                        previousScissorBox[3]);
                if (previousBlend) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
                if (previousCull) GL11.glEnable(GL11.GL_CULL_FACE);
                else GL11.glDisable(GL11.GL_CULL_FACE);
            }
        }

        private void destroy() {
            GL30.glDeleteVertexArrays(vertexArray);
            GL20.glDeleteProgram(program);
        }

        private static int compileShader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile depth seeding shader: " + log);
            }
            return shader;
        }
    }
}
