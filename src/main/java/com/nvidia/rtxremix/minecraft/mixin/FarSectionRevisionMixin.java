/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.vulkan.FarSectionRevision;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tracks content at its mutation boundary, independently of terrain meshing and light-only dirty flags. */
@Mixin(LevelChunkSection.class)
public abstract class FarSectionRevisionMixin implements FarSectionRevision {
    @Unique private long rtxremix$farRevision;

    @Override
    public long rtxremix$farRevision() { return rtxremix$farRevision; }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void rtxremix$blockChanged(int x, int y, int z, BlockState state, boolean lock,
                                     CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != state) rtxremix$farRevision++;
    }

    @Inject(method = {"read", "readBiomes", "fillBiomesFromNoise"}, at = @At("RETURN"))
    private void rtxremix$contentReplaced(CallbackInfo ci) {
        rtxremix$farRevision++;
    }
}
