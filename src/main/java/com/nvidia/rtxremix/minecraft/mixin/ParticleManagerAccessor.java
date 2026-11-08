/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ParticleEngine.class)
public interface ParticleManagerAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> getParticles();

    @Accessor("resourceManager")
    ParticleResources getParticleResources();

    /**
     * Sprite sets keyed by particle type id. The value class is package
     * private in vanilla, so callers read it through the public
     * {@code SpriteSet} interface it implements.
     */
    default Map<Identifier, ?> getSpriteAwareFactories() {
        return ((ParticleResourcesAccessor) getParticleResources()).getSpriteSets();
    }
}
