/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixExtractedEntities;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Captures entities where vanilla submits them, instead of rendering them twice.
 *
 * <p>26.3 extracts each visible entity into a render state and later submits the state
 * to a collector that a feature renderer rasterizes. {@code extractEntity} is wrapped to
 * remember which entity each state came from, and {@code submit} is wrapped to hand that
 * entity to {@code RemixRenderer.renderEntityInPlace}, which submits the already
 * extracted state to the Remix capture collector instead: Minecraft stays in charge of
 * animation, feature layers and modded entity support, and the model is evaluated once.
 * When Remix declines, vanilla submits as usual. A submit that arrives with the Remix
 * collector is already inside a capture (a spawner's display entity, say) and is left
 * alone, so it joins that capture.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @WrapMethod(method = "extractEntity")
    private <E extends Entity> EntityRenderState rtxremix$recordExtraction(
            E entity, float partialTicks, Operation<EntityRenderState> original) {
        EntityRenderState state = original.call(entity, partialTicks);
        RemixExtractedEntities.record(entity, state);
        return state;
    }

    @WrapMethod(method = "submit")
    private <S extends EntityRenderState> void rtxremix$captureEntityInPlace(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector output, Operation<Void> original) {
        if (output instanceof RemixSubmitCollector) {
            original.call(renderState, camera, x, y, z, poseStack, output);
            return;
        }
        Entity entity = RemixExtractedEntities.entityOf(renderState);
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        Minecraft client = Minecraft.getInstance();
        boolean captured = entity != null
                && renderer != null
                && renderer.renderEntityInPlace(entity,
                        client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (!captured) {
            // Remix is not drawing this entity, so vanilla's frame is what the user
            // sees and it keeps its blob shadow.
            original.call(renderState, camera, x, y, z, poseStack, output);
            return;
        }
        if (!renderState.appearsGlowing()
                || !client.gameRenderer.gameRenderState().levelRenderState.shouldShowEntityOutlines) {
            return;
        }
        // Vanilla draws an outlined entity through the outline post-processor, which
        // reads a framebuffer that capturing for Remix does not feed, so vanilla still
        // has to run for it. Its blob shadow must not: the GL frame is composited
        // wholesale over the traced scene, and Remix already traces the real shadow, so
        // the blob would double it.
        List<EntityRenderState.ShadowPiece> shadows = new ArrayList<>(renderState.shadowPieces);
        renderState.shadowPieces.clear();
        try {
            original.call(renderState, camera, x, y, z, poseStack, output);
        } finally {
            renderState.shadowPieces.addAll(shadows);
        }
    }
}
