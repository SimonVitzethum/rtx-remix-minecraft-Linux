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
import com.nvidia.rtxremix.minecraft.profiling.VanillaPhaseMirror;
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
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
 *   <li>Section rebuild requests from the terrain pipeline are served here; the dirty-tracking
 *       rules (water margin, chunk-load re-flag filter) are in {@code LevelExtractorDirtyMixin}.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin
        implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess, WorldRendererTerrainAccess {

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
        // 26.3 builds the star quads inside SkyRenderer straight into a GPU buffer, so the same
        // deterministic generator (seed 10842, 1500 candidates) is replayed here for the positions.
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.createThreadLocalInstance(10842L);
        float[] out = new float[1500 * 4 * 3];
        int count = 0;
        for (int i = 0; i < 1500; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float starSize = 0.15F + random.nextFloat() * 0.1F;
            float lengthSq = net.minecraft.util.Mth.lengthSquared(x, y, z);
            if (!(lengthSq <= 0.010000001F) && !(lengthSq >= 1.0F)) {
                org.joml.Vector3f center = new org.joml.Vector3f(x, y, z).normalize(100.0F);
                float zRot = (float) (random.nextDouble() * (float) Math.PI * 2.0);
                org.joml.Matrix3f rotation = new org.joml.Matrix3f()
                        .rotateTowards(new org.joml.Vector3f(center).negate(), new org.joml.Vector3f(0.0F, 1.0F, 0.0F))
                        .rotateZ(-zRot);
                float[][] corners = {{starSize, -starSize}, {starSize, starSize}, {-starSize, starSize}, {-starSize, -starSize}};
                for (float[] corner : corners) {
                    org.joml.Vector3f v = new org.joml.Vector3f(corner[0], corner[1], 0.0F).mul(rotation).add(center);
                    out[count++] = v.x;
                    out[count++] = v.y;
                    out[count++] = v.z;
                }
            }
        }
        return java.util.Arrays.copyOf(out, count);
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        // Nothing is held open across Fabric events: the mirrored phases below are
        // balanced push/pop pairs inside LevelRenderer.render.
    }

    // ---- Profiler phase labels ----

    /** Mirrors vanilla's profiler phases of the level render into Tracy/JFR; see {@link VanillaPhaseMirror}. */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
    private void rtxremix$mirrorPush(ProfilerFiller profiler, String name, Operation<Void> original) {
        original.call(profiler, name);
        VanillaPhaseMirror.push(name);
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void rtxremix$mirrorPopPush(ProfilerFiller profiler, String name, Operation<Void> original) {
        original.call(profiler, name);
        VanillaPhaseMirror.popPush(name);
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"))
    private void rtxremix$mirrorPop(ProfilerFiller profiler, Operation<Void> original) {
        original.call(profiler);
        VanillaPhaseMirror.pop();
    }

    // ---- Entity frame and block-breaking crack ----

    /**
     * Opens the entity capture window for the submit phase that is starting. Extraction
     * has already produced this frame's entity and block-entity states, which the
     * dispatcher mixins hand to the capture as vanilla submits them.
     */
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void rtxremix$beginSubmitPhase(
            LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    /** Drops the frame's state-to-owner pairs; every submit that could use them has run. */
    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void rtxremix$endSubmitPhase(
            LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
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
            SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(
                    new BlockPos(SectionPos.sectionToBlockCoord(SectionPos.x(node)),
                            SectionPos.sectionToBlockCoord(SectionPos.y(node)),
                            SectionPos.sectionToBlockCoord(SectionPos.z(node))));
            if (section == null) {
                continue;
            }
            int cx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            LevelChunk worldChunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (worldChunk != null
                    && !worldChunk.getSection(worldChunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()
                    && lightingProvider.lightOnInColumn(SectionPos.asLong(cx, 0, cz))) {
                loadedSections.add(section);
            }
        }

        // The compile queue is FIFO. Queue nearby sections first so visible holes close
        // promptly while the Remix scene repopulates.
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
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
