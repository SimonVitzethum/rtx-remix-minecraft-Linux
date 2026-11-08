/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's terrain draw. Remix owns terrain geometry, so while it renders the
 * world the section layers are not drawn into vanilla's frame. Only the per-layer draw
 * loop is cancelled: the render passes that wrap it, and so the frame graph's targets and
 * the order-independent transparency stages around them, run as vanilla sets them up, and
 * a pass left without draws has no net state change to replay.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainLayers(CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            ci.cancel();
        }
    }
}
