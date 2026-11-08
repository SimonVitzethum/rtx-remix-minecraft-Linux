/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.cloud.WorldRendererCloudAccess;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import com.nvidia.rtxremix.minecraft.entity.RemixExtractedEntities;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hosts the Remix capture inside vanilla's level render while a Remix world is active.
 *
 * <p>26.3 splits the level render into extraction, a submit phase and a frame graph that
 * rasterizes what was submitted. Remix is hosted in that traversal and captures its
 * sub-passes in place; vanilla's traversal itself is never cancelled, only replaced draws:
 * <ul>
 *   <li>{@code submitFeatures} is the submit phase. It opens the entity frame, and the
 *       entity and block-entity submits that reach the Remix capture are intercepted one
 *       level down, in {@code EntityRenderDispatcherMixin} and
 *       {@code BlockEntityRenderDispatcherMixin}, because vanilla submits each of them
 *       through a dispatcher.</li>
 *   <li>The block-breaking crack is submitted here, from
 *       {@code submitBlockDestroyAnimation}, as the capture's own decal.</li>
 *   <li>Vanilla's terrain draw is cancelled in {@code ChunkSectionsToRenderMixin}, the
 *       particle draw in {@code QuadParticleRenderStateMixin}; clouds, weather and sky
 *       have their own mixins in their packages.</li>
 *   <li>Section rebuild requests from the terrain pipeline are served here.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin
        implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess, WorldRendererTerrainAccess {

    /**
     * Terrain hooks of the 1.21.1 mixin that have no 26.3 counterpart here yet: the water
     * occlusion rebuild margin ({@code setBlocksDirty}) and the chunk-load neighbour re-flag
     * filter ({@code setSectionDirtyWithNeighbors}) lived on {@code WorldRenderer}, and dirty
     * tracking is now {@code SectionUpdateTracker}'s. TODO(WORLD): port them onto it.
     * Likewise the per-phase Tracy/JFR labels of vanilla's render and the block-entity chunk
     * narrowing, which 26.3's extraction already does per section.
     */
    private static final boolean rtxremix$TERRAIN_DIRTY_HOOKS_PORTED = false;

    @Shadow
    @Nullable
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    @Nullable
    private ViewArea viewArea;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Override
    public int rtxremix$getCloudTicks() {
        return (int) levelRenderState.gameTime;
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        // 26.3 builds the star buffer inside SkyRenderer, not here; the sky package
        // reads it from there (TODO(SKY) if it still asks this interface).
        return new float[0];
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        // Vanilla's phase labels are not mirrored on 26.3; see rtxremix$TERRAIN_DIRTY_HOOKS_PORTED.
    }

    // ---- Entity frame and block-breaking crack ----

    /**
     * Opens the entity capture window for the submit phase that is starting. Extraction
     * has already produced this frame's entity and block-entity states, which the
     * dispatcher mixins hand to the capture as vanilla submits them.
     */
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void rtxremix$beginSubmitPhase(CallbackInfo ci) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    /** Drops the frame's state-to-owner pairs; every submit that could use them has run. */
    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void rtxremix$endSubmitPhase(CallbackInfo ci) {
        RemixExtractedEntities.endFrame();
    }

    /**
     * Captures the block-breaking crack where vanilla submits it, instead of letting it
     * fall into the OpenGL frame that Remix's image replaces.
     *
     * <p>Vanilla's pose stack is deliberately not forwarded: it is camera-relative, while
     * the capture supplies an identity stack and its own crack projection so the geometry
     * stays block-local. When Remix declines, vanilla submits as usual.
     */
    @WrapOperation(
            method = "submitBlockDestroyAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            List<BlockStateModelPart> parts,
            int progress,
            boolean isBlockTranslucent,
            Operation<Void> original,
            @Local BlockBreakingRenderState state) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        BlockPos pos = state.blockPos();
        Vec3 offset = state.blockState().getOffset(pos);
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(pos, progress,
                        (captureMatrices, captureConsumer) ->
                                RemixBlockDamagePass.putParts(parts, offset, captureMatrices, captureConsumer));
        if (!captured) {
            original.call(collector, poseStack, parts, progress, isBlockTranslucent);
        }
    }

    // ---- Terrain pipeline services ----

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        // Vanilla builds a section when its occlusion walk reaches it, so the visible
        // list is the built set.
        for (SectionRenderDispatcher.RenderSection section : ((LevelRenderer) (Object) this).visibleSections()) {
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        }
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        ClientLevel level = Minecraft.getInstance().level;
        if (viewArea == null || sectionRenderDispatcher == null || level == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }

        // Same gate as vanilla's compile: a section built before its light is ready
        // would bake bad light, and nothing re-flags it afterwards.
        LevelLightEngine lightingProvider = level.getLightEngine();

        List<SectionRenderDispatcher.RenderSection> loadedSections = new ArrayList<>();
        for (LongIterator it = sections.iterator(); it.hasNext();) {
            long node = it.nextLong();
            SectionRenderDispatcher.RenderSection section = viewArea.getRenderSection(node);
            if (section == null) {
                continue;
            }
            int cx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            LevelChunk worldChunk = level.getChunkSource().getChunk(cx, cz, false);
            if (worldChunk != null && !worldChunk.isSectionEmpty(sy)
                    && lightingProvider.lightOnInSection(SectionPos.of(cx, sy, cz))) {
                loadedSections.add(section);
            }
        }

        // The compile queue is FIFO. Queue nearby sections first so visible holes close
        // promptly while the Remix scene repopulates.
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        loadedSections.sort(Comparator.comparingDouble(section -> {
            BlockPos origin = section.getRenderOrigin();
            double dx = origin.getX() + 8.0 - cameraPos.x;
            double dy = origin.getY() + 8.0 - cameraPos.y;
            double dz = origin.getZ() + 8.0 - cameraPos.z;
            return dx * dx + dy * dy + dz * dz;
        }));

        RenderRegionCache regionCache = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        try {
            for (SectionRenderDispatcher.RenderSection section : loadedSections) {
                section.compileAsync(regionCache.createRegion(level, section.getSectionNode()));
                queued.add(section.getSectionNode());
            }
        } finally {
            ((RegionBuilderRelease) regionCache).rtxremix$releaseSnapshots();
        }
        return queued;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$scheduleRebuildIfResident(cx, sy, cz);
    }

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        if (viewArea != null) {
            ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$clearNeighbourSnapshotBits(cx, cz);
        }
    }
}
