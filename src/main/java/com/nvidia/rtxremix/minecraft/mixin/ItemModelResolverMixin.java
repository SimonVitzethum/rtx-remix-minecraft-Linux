/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixItemSource;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the stack and owner an item render state was resolved for, so the capture can
 * recover them when the state is submitted; see {@code ItemStackRenderStateMixin}.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void rtxremix$rememberSource(ItemStackRenderState output, ItemStack item,
                                         ItemDisplayContext displayContext, @Nullable Level level,
                                         @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        ((RemixItemSource) output).rtxremix$setSource(item, owner);
    }
}
