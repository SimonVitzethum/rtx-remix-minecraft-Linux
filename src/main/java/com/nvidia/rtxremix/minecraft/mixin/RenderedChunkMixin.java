/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A section snapshot for a render region whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and gives it back when the last region holding the snapshot
 * has been consumed. In 26.3 a {@code SectionCopy} is one 16x16x16 section (1.21 copied a
 * whole chunk column), shared between the regions one frame's rebuild scheduling builds
 * through {@code RenderRegionCache}, hence the count. The pool flag is set around the
 * constructor here; vanilla's own snapshot code is untouched. The constructor's reference
 * is the cache's own, dropped by {@link ChunkRendererRegionBuilderMixin}.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    @Mutable
    private @Nullable PalettedContainer<BlockState> section;

    private final AtomicInteger rtxremix$regions = new AtomicInteger();

    @Inject(method = "<init>", at = @At("HEAD"))
    private void rtxremix$beginPooledCopy(CallbackInfo ci) {
        RenderRegionStoragePool.beginCopying();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$endPooledCopy(CallbackInfo ci) {
        RenderRegionStoragePool.endCopying();
        // The cache's reference.
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$retain() {
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$regions.decrementAndGet() != 0) {
            return;
        }
        PalettedContainer<BlockState> container = section;
        if (container == null) {
            return;
        }
        if (container.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        // Nothing may read the snapshot after its last release: a stale read sees air
        // rather than whatever the recycled array holds next.
        section = null;
    }
}
