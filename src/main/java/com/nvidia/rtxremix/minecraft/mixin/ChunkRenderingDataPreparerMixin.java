/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hooks vanilla's chunk visibility preparation: it runs the full visibility walk on a
 * dedicated low-priority executor so long view distances cannot starve terrain capture or
 * the render thread, and labels each phase for the profiler.
 *
 * <p>26.3 port note: vanilla's {@code SectionOcclusionGraph} now owns an {@code Octree}
 * that does its own hierarchical frustum culling, and its storage is a fixed array indexed
 * by section slot. That replaces what the 1.21 hooks added: the coarse frustum index, the
 * pooled walk buffers and the per-frame propagation slicing are therefore not ported, and
 * neither is the allocation-free replacement for the walk's far-section probe.
 * {@link #ALLOCATION_FREE_WALK_PORTED} records that: the walk is vanilla's, which
 * allocates two {@code Vector3d} per probe step and one {@code Node} per section. The
 * pure helpers of the old port ({@code FarChunkProbe}, {@code HierarchicalFrustumCuller},
 * {@code TimeSlicedWorkQueue}) stay in {@code terrain/visibility/} for a later port.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {

    /** TODO: port the allocation-free walk and the time-sliced propagation onto the 26.3 graph. */
    private static final boolean ALLOCATION_FREE_WALK_PORTED = false;

    private static final ExecutorService rtxremix$visibilityExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RTX Remix chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
    private static final TracingExecutor rtxremix$visibilityTracingExecutor =
            new TracingExecutor(rtxremix$visibilityExecutor);

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$walkScope = new ThreadLocal<>();

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void rtxremix$storageHead(ViewArea storage, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void rtxremix$storageReturn(ViewArea storage, CallbackInfo ci) {
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$prepareHead(CameraRenderState camera, int fov,
            ChunkLoadingRenderState chunkLoading, CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare");
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void rtxremix$prepareReturn(CameraRenderState camera, int fov,
            ChunkLoadingRenderState chunkLoading, CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void rtxremix$frustumHead(CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum");
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void rtxremix$frustumReturn(CallbackInfo ci) {
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "runUpdates", at = @At("HEAD"))
    private void rtxremix$walkHead(CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph");
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void rtxremix$walkReturn(CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
    }

    @Redirect(
            method = "scheduleFullUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;backgroundExecutor()Lnet/minecraft/TracingExecutor;"
            ))
    private TracingExecutor rtxremix$visibilityExecutor() {
        return RtxRemixMod.isRtxEnabled()
                ? rtxremix$visibilityTracingExecutor
                : Util.backgroundExecutor();
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(ProfileScope.begin(phaseName));
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
