/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Releases a compile task's region snapshot once the section build that read it has
 * returned. The worker running {@code doTask} is the region's only reader: {@code cancel} on
 * the render thread merely flags the task, so the region is owned by this worker whichever
 * branch vanilla takes, including the early returns that never read it. A task cancelled
 * before it ever runs leaves its region to the collector, which only forgoes the reuse.
 *
 * <p>26.3: vanilla's {@code RebuildTask} is now {@code RenderSection.CompileTask} and its
 * entry point is {@code doTask}.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class RebuildTaskMixin {

    @Shadow
    @Final
    private RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("RETURN"))
    private void rtxremix$releaseRegion(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> cir) {
        ((RegionSnapshotRelease) region).rtxremix$release();
    }

    /**
     * While capturing, vanilla builds no layer meshes, so a section without block entities
     * would be published as the shared {@code EMPTY} mesh, which reports every face pair
     * as mutually visible and loses the section's real visibility set. Publishing the
     * compiled mesh instead (no draws, so no GPU cost) keeps the occlusion graph vanilla's.
     */
    @ModifyExpressionValue(method = "doTask",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean rtxremix$keepVisibilityOfEmptySection(boolean blockEntitiesEmpty) {
        return blockEntitiesEmpty && !RtxRemixMod.isRtxEnabled();
    }
}
