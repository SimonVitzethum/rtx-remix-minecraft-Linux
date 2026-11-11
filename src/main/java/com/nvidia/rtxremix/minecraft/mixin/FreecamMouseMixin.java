/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.camera.Freecam;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look and the scroll wheel drive the freecam instead of the player. */
@Mixin(MouseHandler.class)
public abstract class FreecamMouseMixin {
    @Redirect(method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void rtxremix$freecamLook(LocalPlayer player, double dx, double dy) {
        if (Freecam.isActive()) {
            Freecam.turn(dx, dy);
        } else {
            player.turn(dx, dy);
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void rtxremix$freecamScroll(long handle, double xo, double yo, CallbackInfo ci) {
        if (Freecam.isActive() && net.minecraft.client.Minecraft.getInstance().gui.screen() == null) {
            Freecam.scroll(Math.signum(yo != 0.0 ? yo : xo));
            ci.cancel();
        }
    }
}
