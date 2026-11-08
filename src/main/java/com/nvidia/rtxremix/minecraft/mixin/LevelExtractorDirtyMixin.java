/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain dirty-tracking hooks. 26.3 funnels every section rebuild request through
 * {@link LevelExtractor#setSectionDirty}; the two Remix rules of the 1.21 {@code WorldRenderer}
 * mixin sit on its callers:
 * <ul>
 *   <li>the water-top occlusion rebuild margin: hidden water-top culling reads farther than
 *       vanilla's one-block margin, so block-change invalidation reaches
 *       {@link BlockQuadEmitter#getWaterTopOcclusionRadius()} blocks sideways;</li>
 *   <li>the chunk-load neighbour re-flag filter ({@link NeighbourReflagFilter}): a column load
 *       ({@code enableChunkLight}'s 3x3 column range) and light data
 *       ({@code setSectionDirtyWithNeighbors}) flag a neighbour only if its last region
 *       snapshot lacked the loading column. Biome packets and single-section light updates
 *       call {@code setSectionDirty} directly and stay vanilla.</li>
 * </ul>
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorDirtyMixin {

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    @Shadow
    private void setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
    }

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.LevelRenderer levelRenderer;

    /** Dirty-marks every section of the block box grown by the water occlusion margin. */
    private void rtxremix$markBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean playerChanged) {
        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int z = minSectionZ; z <= maxSectionZ; z++) {
            for (int x = minSectionX; x <= maxSectionX; x++) {
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    setSectionDirty(x, y, z, playerChanged);
                }
            }
        }
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionMargin(
            int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        rtxremix$markBox(x0, y0, z0, x1, y1, z1, false);
        ci.cancel();
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionMarginForBlockChange(
            BlockPos pos, boolean playerChanged, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        rtxremix$markBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), playerChanged);
        ci.cancel();
    }

    /** Light data re-flag of a section and its 3x3x3 neighbourhood. */
    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterLightReflags(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        BuiltChunkStorageSectionAccess storage = rtxremix$filterTarget();
        if (storage == null) {
            return;
        }
        storage.rtxremix$flagLoadedSection(sectionX, sectionY, sectionZ);
        ci.cancel();
    }

    /**
     * Chunk load: {@code enableChunkLight} flags the loading column and its eight neighbour
     * columns over the full height as one range. Only that 3x3 column shape is filtered.
     */
    @Inject(method = "setSectionRangeDirty", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterChunkLoadReflags(
            int minSectionX, int minSectionY, int minSectionZ,
            int maxSectionX, int maxSectionY, int maxSectionZ, CallbackInfo ci) {
        if (maxSectionX - minSectionX != 2 || maxSectionZ - minSectionZ != 2) {
            return;
        }
        BuiltChunkStorageSectionAccess storage = rtxremix$filterTarget();
        if (storage == null) {
            return;
        }
        int cx = minSectionX + 1;
        int cz = minSectionZ + 1;
        for (int y = minSectionY; y <= maxSectionY; y++) {
            storage.rtxremix$flagLoadedSection(cx, y, cz);
        }
        ci.cancel();
    }

    private BuiltChunkStorageSectionAccess rtxremix$filterTarget() {
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED) {
            return null;
        }
        ViewArea viewArea = levelRenderer.viewArea();
        return viewArea == null ? null : (BuiltChunkStorageSectionAccess) viewArea;
    }
}
