/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Hooks into vanilla's {@code RenderSection} lifecycle.
 *
 * <ul>
 *   <li><b>setSectionNode:</b> Resets per-slot block-entity and snapshot state when
 *       vanilla reuses a section slot for a new section.
 *   <li><b>createCompileTask:</b> Records which neighbour columns the region
 *       snapshot contained, so a later chunk-load re-flag from a column the
 *       capture already read can be dropped.
 *   <li><b>setSectionMesh:</b> Tracks whether the section contains block
 *       entities and flags empty render data as a removal candidate. The
 *       render thread validates the live world section before removing
 *       Remix terrain because vanilla can publish temporary empty data.
 * </ul>
 *
 * <p>26.3: sections no longer own per-layer GL buffers (meshes live in uber buffers keyed by
 * the mesh), so the 1.21 hook that gave constructed sections an empty buffer map is gone,
 * and the slot-move log that fed the frustum index is not fed any more.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess, BuiltChunkNeighbourhoodAccess {

    @Shadow
    @Final
    private BlockPos.MutableBlockPos renderOrigin;

    @Shadow
    @Final
    public AtomicReference<SectionMesh> sectionMesh;

    /**
     * Shared by every slot: createCompileTask runs only on the render thread
     * (level extraction, the compile-sync path, the Remix recapture queue), so one
     * scratch position serves all of them.
     */
    private static final BlockPos.MutableBlockPos rtxremix$neighbourProbe = new BlockPos.MutableBlockPos();

    private boolean rtxremix$hasBlockEntities;

    /** Bit k set iff neighbour column (DX[k], DZ[k]) was loaded when the last region snapshot was taken. */
    private int rtxremix$snapshotMask;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        return rtxremix$hasBlockEntities;
    }

    @Override
    public int rtxremix$snapshotMask() {
        return rtxremix$snapshotMask;
    }

    @Override
    public void rtxremix$clearSnapshotBit(int bit) {
        rtxremix$snapshotMask &= ~bit;
    }

    @Inject(method = "setSectionNode(J)V", at = @At("HEAD"))
    private void rtxremix$onSetSectionNode(long sectionNode, CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }

    /**
     * The probe sees exactly the columns the snapshot holds: RenderRegionCache.createRegion
     * reads {@code level.getChunk(x, z)}, which returns the shared empty chunk precisely when
     * the client chunk source does not have the column, and nothing interleaves on the render
     * thread between the two. An empty own section is recorded too: it has no geometry that a
     * neighbour could make wrong.
     */
    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void rtxremix$recordSnapshotNeighbourhood(RenderSectionRegion region, CallbackInfoReturnable<?> cir) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // RETURN precedes the task's run, so a first build still reads UNCOMPILED here.
        boolean firstBuild = sectionMesh.get() == CompiledSectionMesh.UNCOMPILED;
        int originX = renderOrigin.getX();
        int originZ = renderOrigin.getZ();
        int mask = 0;
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int chunkX = (originX >> 4) + NeighbourReflagFilter.DX[k];
            int chunkZ = (originZ >> 4) + NeighbourReflagFilter.DZ[k];
            if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                mask |= NeighbourReflagFilter.bit(k);
            }
        }
        rtxremix$snapshotMask = mask;
        if (NeighbourReflagFilter.isIncompleteFirstBuild(firstBuild, rtxremix$squaredCameraDistance(), mask)) {
            NeighbourReflagFilter.recordIncompleteFirstBuild();
        }
    }

    private double rtxremix$squaredCameraDistance() {
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) {
            return 0.0;
        }
        Vec3 pos = camera.position();
        double dx = renderOrigin.getX() + 8.0 - pos.x;
        double dy = renderOrigin.getY() + 8.0 - pos.y;
        double dz = renderOrigin.getZ() + 8.0 - pos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        rtxremix$hasBlockEntities = !mesh.getRenderableBlockEntities().isEmpty();

        if (mesh.hasRenderableLayers()) {
            return;
        }
        int x = renderOrigin.getX();
        int y = renderOrigin.getY();
        int z = renderOrigin.getZ();
        if (x == -1 && y == -1 && z == -1) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(x >> 4, y >> 4, z >> 4);
    }

    @Inject(method = "reset()V", at = @At("HEAD"), require = 0)
    private void rtxremix$onClear(CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }
}
