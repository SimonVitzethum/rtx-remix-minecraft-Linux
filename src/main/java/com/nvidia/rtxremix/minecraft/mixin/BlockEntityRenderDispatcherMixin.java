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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Captures block entities where vanilla submits them, instead of rendering them twice.
 *
 * <p>Vanilla extracts a block entity into a render state and later submits the state to a
 * collector that a feature renderer rasterizes. {@code tryExtractRenderState} is wrapped to
 * remember which block entity each state came from, and each {@code submit} is offered to
 * {@code RemixRenderer.renderBlockEntityInPlace}, which runs the same renderer against the
 * Remix capture stack and collector and submits the result; the rasterizer never sees it.
 * Vanilla's own pose stack and collector are deliberately not forwarded: the stack is
 * camera-relative, and the capture supplies an identity one so the geometry stays
 * block-local. When Remix declines, vanilla renders as usual.
 *
 * <p>A submit that arrives with the Remix collector, or for a state that was not extracted
 * from a placed block entity (a held block entity item), is left alone: it joins the
 * enclosing capture as that capture's parts.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @WrapMethod(method = "tryExtractRenderState")
    private <E extends BlockEntity, S extends BlockEntityRenderState> S rtxremix$recordExtraction(
            E blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay crumbling,
            boolean isGloballyRendered, Operation<S> original) {
        S state = original.call(blockEntity, partialTicks, crumbling, isGloballyRendered);
        if (state != null) {
            RemixExtractedEntities.recordBlockEntity(blockEntity, state);
        }
        return state;
    }

    @WrapMethod(method = "submit")
    private <S extends BlockEntityRenderState> void rtxremix$captureBlockEntityInPlace(
            S state, PoseStack poseStack, SubmitNodeCollector output, CameraRenderState camera,
            Operation<Void> original) {
        if (output instanceof RemixSubmitCollector) {
            original.call(state, poseStack, output, camera);
            return;
        }
        BlockEntity be = RemixExtractedEntities.blockEntityOf(state);
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = be != null
                && renderer != null
                && be.hasLevel()
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureCollector) ->
                                original.call(state, captureMatrices, captureCollector, camera));
        if (!captured) {
            original.call(state, poseStack, output, camera);
        }
    }
}
