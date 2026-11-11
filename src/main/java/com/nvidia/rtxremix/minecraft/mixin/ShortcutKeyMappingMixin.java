/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Plain shortcuts and vanilla's corresponding debug chords occupy different input contexts. */
@Mixin(KeyMapping.class)
public abstract class ShortcutKeyMappingMixin {
    @Shadow public abstract String getName();

    @Inject(method = "same", at = @At("HEAD"), cancellable = true)
    private void rtxremix$debugChordConflict(KeyMapping other, CallbackInfoReturnable<Boolean> cir) {
        String a = getName();
        String b = other.getName();
        if (rtxremix$separateContexts(a, b) || rtxremix$separateContexts(b, a)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean rtxremix$separateContexts(String plain, String chord) {
        return (plain.equals("key.rtxremix.freecam") && chord.equals("key.debug.switchGameMode"))
                || (plain.equals("key.rtxremix.fullbright") && chord.equals("key.debug.showHitboxes"));
    }
}
