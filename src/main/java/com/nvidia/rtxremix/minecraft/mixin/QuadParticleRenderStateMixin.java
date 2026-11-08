/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's quad particle draw. Particle extraction still runs, but the
 * group is never submitted to the feature renderer while Remix owns the world
 * render, because {@code RemixParticleRenderer} captures the live particles
 * itself. Item pickup and elder guardian groups are other state types and still draw.
 */
@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaParticleDraw(
            SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            ci.cancel();
        }
    }
}
