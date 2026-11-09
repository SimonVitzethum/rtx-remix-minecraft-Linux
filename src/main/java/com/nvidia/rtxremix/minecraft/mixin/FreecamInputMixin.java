/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.camera.Freecam;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses the player's own movement input while the freecam is on. */
@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void rtxremix$freecamNoMove(CallbackInfo ci) {
        if (Freecam.isActive()) {
            ((KeyboardInput) (Object) this).keyPresses = Input.EMPTY;
            ((ClientInputAccessor) (Object) this).rtxremix$setMoveVector(Vec2.ZERO);
        }
    }
}
