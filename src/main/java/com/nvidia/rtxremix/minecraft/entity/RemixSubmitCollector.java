/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The submit target of every Remix capture pass.
 *
 * <p>26.3 renderers no longer draw: they submit models, parts, items, text and custom
 * geometry to a {@link SubmitNodeCollector}, and a feature renderer rasterizes the
 * collected nodes later. This collector stands where vanilla's storage would and
 * evaluates each submit at once, as the feature renderers eventually would, but into
 * the {@link RemixVertexConsumerProvider}'s capturing consumers instead of a GPU
 * buffer. A model is therefore evaluated exactly once, for Remix, with the pose
 * stack the renderer passed in; {@code ModelPartMixin} and {@code CuboidMixin} then
 * turn its cuboids into rigid parts keyed by identity, as before.
 *
 * <p>Submits that have no Remix counterpart (blob shadows, shape
 * outlines, gizmos, particle groups) are dropped here: Remix traces real shadows, the
 * block-breaking crack of a block and block outline have their own passes (a block entity's
 * crumbling overlay is captured here as a destroy-stage layer), and particles are
 * captured from the live particle engine.
 */
public final class RemixSubmitCollector implements SubmitNodeCollector {

    private static final int LEASH_RENDER_STEPS = 24;
    private static final float LEASH_WIDTH = 0.05F;

    private final RemixVertexConsumerProvider provider;
    private final QuadInstance quadInstance = new QuadInstance();
    private final RemixBlockDamagePass.CrackProjector crackProjector = new RemixBlockDamagePass.CrackProjector();
    private final Matrix4f scratchPose = new Matrix4f();

    public RemixSubmitCollector(RemixVertexConsumerProvider provider) {
        this.provider = provider;
    }

    public RemixVertexConsumerProvider provider() {
        return provider;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        // Draw order is a rasterizer concern; Remix orders by depth and primitive index.
        return this;
    }

    // ---- Dropped submits ----

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
                                           RenderType renderType, int lightCoords, int overlayCoords,
                                           int tintedColor,
                                           ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        // The block-entity crack: the model is evaluated a second time into the destroy-stage
        // layer through the same projection the block-breaking crack uses. The pose stack is
        // the pass's block-local one, so the projected coordinates are block-relative as in
        // vanilla's camera-pose projection. The layer is a later one of the capture and so
        // stands one shell off the surface beneath it.
        List<RenderType> destroyTypes = ModelBakery.DESTROY_TYPES;
        int progress = crumblingOverlay.progress();
        if (!renderType.affectsCrumbling() || progress < 0 || progress >= destroyTypes.size()) {
            return;
        }
        VertexConsumer crack = crackProjector.target(provider.getBuffer(destroyTypes.get(progress)));
        model.setupAnim(state);
        model.renderToBuffer(poseStack, crack, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera,
                                      boolean onTop) {
    }

    // ---- Models ----

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable UvMapping uvMapping, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (uvMapping != null) {
            buffer = uvMapping.wrap(buffer);
        }
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        customGeometryRenderer.render(poseStack.last(), provider.getBuffer(renderType));
    }

    // ---- Items and blocks ----

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           ItemStackRenderState.FoilType foilType) {
        if (outlineColor != 0 && quads.isEmpty()) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        EntityRenderContext context = EntityRenderContext.get();
        ItemStack stack = context.getItemStack();
        if (stack != null && context.claimHeldEffects()) {
            // The item's lit element goes in once its pose is on the stack, so first
            // person, the hidden shadow model and every holder share one hook.
            HeldItemEffectsRenderer.render(stack, displayContext, poseStack, provider);
        }
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            // The enchantment glint is a screen-space shimmer over the surface that Remix
            // cannot reproduce, so a glinting item captures as the plain one: foilType is
            // ignored and the quad's own render type is used (26.3 draws the glint as a
            // second sampler of a glint variant of that layer, not as an extra pass).
            RenderType renderType = material.itemRenderType();
            int tintIndex = material.tintIndex();
            quadInstance.setColor(material.isTinted() && tintIndex >= 0 && tintIndex < tintLayers.length
                    ? tintLayers[tintIndex]
                    : -1);
            provider.getBuffer(renderType).putBakedQuad(pose, quad, quadInstance);
        }
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        VertexConsumer buffer = provider.getBuffer(renderType);
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        PoseStack.Pose pose = poseStack.last();
        for (BlockStateModelPart part : parts) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                putPartQuads(part.getQuads(direction), pose, tintLayers, buffer);
            }
            putPartQuads(part.getQuads(null), pose, tintLayers, buffer);
        }
    }

    private void putPartQuads(List<BakedQuad> quads, PoseStack.Pose pose, int[] tintLayers,
                              VertexConsumer buffer) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            boolean useTintLayer = tintIndex != -1 && tintIndex < tintLayers.length;
            quadInstance.setColor(useTintLayer ? tintLayers[tintIndex] : -1);
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
                                  int outlineColor) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockState blockState = movingBlockRenderState.blockState;
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                minecraft.options.ambientOcclusion().get(), false, minecraft.getBlockColors());
        boolean forceOpaque = ModelBlockRenderer.forceOpaque(
                minecraft.options.cutoutLeaves().get(), blockState);
        long seed = blockState.getSeed(movingBlockRenderState.randomSeedPos);
        blockRenderer.tesselateBlock((x, y, z, quad, instance) -> {
            ChunkSectionLayer layer = forceOpaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
            RenderType renderType = switch (layer) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            provider.getBuffer(renderType).putBakedQuad(poseStack.last(), quad, instance);
            poseStack.popPose();
        }, 0.0F, 0.0F, 0.0F, movingBlockRenderState, movingBlockRenderState.blockPos, blockState, model, seed);
    }

    // ---- Entity extras ----

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        Minecraft minecraft = Minecraft.getInstance();
        VertexConsumer buffer = provider.getBuffer(RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS));
        TextureAtlasSprite fire1 = minecraft.getAtlasManager().get(net.minecraft.client.resources.model.ModelBakery.FIRE_0);
        TextureAtlasSprite fire2 = minecraft.getAtlasManager().get(net.minecraft.client.resources.model.ModelBakery.FIRE_1);
        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        float s = renderState.boundingBoxWidth * 1.4F;
        pose.scale(s, s, s);
        float r = 0.5F;
        float h = renderState.boundingBoxHeight / s;
        float yo = 0.0F;
        pose.rotate(rotation);
        pose.translate(0.0F, 0.0F, 0.3F - (int) h * 0.02F);
        float zo = 0.0F;
        int ss = 0;
        int lightCoords = LightCoordsUtil.withBlock(renderState.lightCoords, 15);
        while (h > 0.0F) {
            TextureAtlasSprite tex = ss % 2 == 0 ? fire1 : fire2;
            float u0 = tex.getU0();
            float v0 = tex.getV0();
            float u1 = tex.getU1();
            float v1 = tex.getV1();
            if (ss / 2 % 2 == 0) {
                float tmp = u1;
                u1 = u0;
                u0 = tmp;
            }
            fireVertex(pose, buffer, -r, 0.0F - yo, zo, u1, v1, lightCoords);
            fireVertex(pose, buffer, r, 0.0F - yo, zo, u0, v1, lightCoords);
            fireVertex(pose, buffer, r, 1.4F - yo, zo, u0, v0, lightCoords);
            fireVertex(pose, buffer, -r, 1.4F - yo, zo, u1, v0, lightCoords);
            h -= 0.45F;
            yo -= 0.45F;
            r *= 0.9F;
            zo -= 0.03F;
            ss++;
        }
        poseStack.popPose();
    }

    private static void fireVertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
                                   float u, float v, int lightCoords) {
        buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setUv1(0, 10).setLight(lightCoords)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        VertexConsumer builder = provider.getBuffer(RenderTypes.leash());
        Matrix4f pose = scratchPose.set(poseStack.last().pose());
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * LEASH_WIDTH / 2.0F;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        pose.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);
        for (int k = 0; k <= LEASH_RENDER_STEPS; k++) {
            addLeashVertexPair(builder, pose, dx, dy, dz, LEASH_WIDTH, dxOff, dzOff, k, false, leashState);
        }
        for (int k = LEASH_RENDER_STEPS; k >= 0; k--) {
            addLeashVertexPair(builder, pose, dx, dy, dz, 0.0F, dxOff, dzOff, k, true, leashState);
        }
    }

    private static void addLeashVertexPair(VertexConsumer builder, Matrix4fc pose, float dx, float dy, float dz,
                                           float fudge, float dxOff, float dzOff, int k, boolean backwards,
                                           EntityRenderState.LeashState state) {
        float progress = k / (float) LEASH_RENDER_STEPS;
        int block = (int) Mth.lerp(progress, state.startBlockLight, state.endBlockLight);
        int sky = (int) Mth.lerp(progress, state.startSkyLight, state.endSkyLight);
        int lightCoords = LightCoordsUtil.pack(block, sky);
        float colorModifier = k % 2 == (backwards ? 1 : 0) ? 0.7F : 1.0F;
        float r = 0.5F * colorModifier;
        float g = 0.4F * colorModifier;
        float b = 0.3F * colorModifier;
        float x = dx * progress;
        float y;
        if (state.slack) {
            y = dy > 0.0F ? dy * progress * progress : dy - dy * (1.0F - progress) * (1.0F - progress);
        } else {
            y = dy * progress;
        }
        float z = dz * progress;
        builder.addVertex(pose, x - dxOff, y + fudge, z + dzOff).setColor(r, g, b, 1.0F).setLight(lightCoords);
        builder.addVertex(pose, x + dxOff, y + LEASH_WIDTH - fudge, z - dzOff).setColor(r, g, b, 1.0F)
                .setLight(lightCoords);
    }

    // ---- Text ----

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
        if (nameTagAttachment == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);
        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        poseStack.popPose();
        float x = -minecraft.font.width(name) / 2.0F;
        float backgroundAlpha =
                minecraft.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25F);
        int backgroundColor = ARGB.color(backgroundAlpha, -16777216);
        float textAlpha = Math.max((backgroundAlpha + 0.75F) * 0.5F, 0.5F);
        int textColor = ARGB.color(textAlpha, -1);
        FormattedCharSequence visualOrderName = name.getVisualOrderText();
        // The see-through copy is drawn over the whole scene in vanilla and has no place
        // in a ray-traced one (RenderLayerUtils skips its layer); the depth-tested copy
        // beside it is the one captured.
        if (seeThrough) {
            drawText(minecraft.font, pose, x, offset, visualOrderName, false, -1, 0, 0,
                    Font.DisplayMode.NORMAL, LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2));
        } else {
            drawText(minecraft.font, pose, x, offset, visualOrderName, false, textColor, backgroundColor, 0,
                    Font.DisplayMode.NORMAL, lightCoords);
        }
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string,
                           boolean dropShadow, Font.DisplayMode displayMode, int lightCoords, int color,
                           int backgroundColor, int outlineColor) {
        drawText(Minecraft.getInstance().font, new Matrix4f(poseStack.last().pose()), x, y, string, dropShadow,
                color, backgroundColor, outlineColor, displayMode, lightCoords);
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
        Font font = Minecraft.getInstance().font;
        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        net.minecraft.client.gui.font.TextRenderable background = font.prepareBackground(x0, y0, x1, y1, color);
        background.render(pose, provider.getBuffer(background.renderType(displayMode)), lightCoords, false);
    }

    /** Mirrors {@code TextFeatureRenderer}: glyphs as renderables on the capture's buffers. */
    private void drawText(Font font, Matrix4f pose, float x, float y, FormattedCharSequence string,
                          boolean dropShadow, int color, int backgroundColor, int outlineColor,
                          Font.DisplayMode displayMode, int lightCoords) {
        if (outlineColor == 0) {
            Font.PreparedText text = font.prepareText(string, x, y, color, dropShadow, false, backgroundColor);
            text.visit(new GlyphRenderer(pose, lightCoords, displayMode));
        } else {
            Font.PreparedText outline = font.prepare8xTextOutline(string, x, y, outlineColor);
            Font.PreparedText text = font.prepareText(string, x, y, color, false, false, 0);
            outline.visit(new GlyphRenderer(pose, lightCoords, Font.DisplayMode.NORMAL));
            text.visit(new GlyphRenderer(pose, lightCoords, Font.DisplayMode.POLYGON_OFFSET));
        }
    }

    private final class GlyphRenderer implements Font.GlyphVisitor {
        private final Matrix4f pose;
        private final int lightCoords;
        private final Font.DisplayMode displayMode;

        GlyphRenderer(Matrix4f pose, int lightCoords, Font.DisplayMode displayMode) {
            this.pose = pose;
            this.lightCoords = lightCoords;
            this.displayMode = displayMode;
        }

        @Override
        public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable renderable) {
            renderable.render(pose, provider.getBuffer(renderable.renderType(displayMode)), lightCoords, false);
        }
    }
}
