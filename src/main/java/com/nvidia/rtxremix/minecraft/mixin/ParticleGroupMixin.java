/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/** Coarse per-group tick marker, one zone per particle group rather than per particle. */
@Mixin(ParticleGroup.class)
public abstract class ParticleGroupMixin {
    @Shadow @Final protected Queue<Particle> particles;

    private static final ThreadLocal<ProfileScope> rtxremix$groupScope = new ThreadLocal<>();

    @Inject(method = "tickParticles", at = @At("HEAD"), require = 0)
    private void rtxremix$tickParticlesHead(CallbackInfo ci) {
        rtxremix$closeScope();
        rtxremix$groupScope.set(ProfileScope.beginJfrOnly("ParticleManager.tickParticles")
                .captureCount(particles.size())
                .plotOnClose("Java.ParticleManager.tickParticles.ms"));
    }

    @Inject(method = "tickParticles", at = @At("RETURN"), require = 0)
    private void rtxremix$tickParticlesReturn(CallbackInfo ci) {
        rtxremix$closeScope();
    }

    private static void rtxremix$closeScope() {
        ProfileScope scope = rtxremix$groupScope.get();
        if (scope != null) {
            rtxremix$groupScope.remove();
            scope.close();
        }
    }
}
