/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Section-slot lookups for the chunk-load re-flag rules in {@link BuiltChunkStorageSectionAccess}.
 *
 * <p>26.3: the storage is a {@code RotatingSectionStorage} whose camera move only re-keys
 * slots whose section changed, so the 1.21 replacement of {@code updateCameraPosition}
 * ({@code ChunkStorageTorus}) is not needed and is not hooked.
 */
@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin implements BuiltChunkStorageSectionAccess {

    @Shadow
    protected abstract SectionRenderDispatcher.RenderSection getRenderSection(long sectionNode);

    @Shadow
    public abstract int minSectionY();

    @Shadow
    public abstract int sectionCount();

    /** The slot holding the section, or null unless that slot currently holds exactly that section. */
    private SectionRenderDispatcher.RenderSection rtxremix$residentSlot(int cx, int sy, int cz) {
        long node = SectionPos.asLong(cx, sy, cz);
        SectionRenderDispatcher.RenderSection slot = getRenderSection(node);
        return slot != null && slot.getSectionNode() == node ? slot : null;
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(cx, sy, cz);
        if (slot != null) {
            rtxremix$flag(slot);
        }
    }

    /**
     * Marks a slot for a rebuild the way vanilla's block-change path does: through
     * {@code LevelExtractor}'s dirty tracker, which the next extraction turns into a compile.
     */
    private static void rtxremix$flag(SectionRenderDispatcher.RenderSection slot) {
        long node = slot.getSectionNode();
        Minecraft.getInstance().levelExtractor.setSectionDirty(
                SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        if (!NeighbourReflagFilter.sectionInRange(sy, minSectionY(), sectionCount())) {
            return;
        }
        rtxremix$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            boolean awaitingBuild = resident && rtxremix$awaitingBuild(slot);
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    rtxremix$flag(slot);
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    private static boolean rtxremix$awaitingBuild(SectionRenderDispatcher.RenderSection slot) {
        SectionUpdateTracker tracker = Minecraft.getInstance().levelExtractor.sectionUpdateTracker;
        if (tracker == null) {
            return false;
        }
        SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(slot.getSectionNode());
        return state != null && state.isDirty();
    }

    @Override
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = minSectionY();
        int rows = sectionCount();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < rows; row++) {
                SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
