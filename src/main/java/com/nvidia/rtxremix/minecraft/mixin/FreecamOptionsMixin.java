/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.camera.Freecam;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The freecam reports third-person view so the player body is drawn and the first-person hands are not. */
@Mixin(Options.class)
public abstract class FreecamOptionsMixin {
    @Inject(method = "getCameraType", at = @At("HEAD"), cancellable = true)
    private void rtxremix$freecamView(CallbackInfoReturnable<CameraType> cir) {
        if (Freecam.isActive()) {
            cir.setReturnValue(Freecam.overrideCameraType());
        }
    }
}
