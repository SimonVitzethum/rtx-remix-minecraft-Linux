/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.JoinBurstHold;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the level-loading screen up until Remix's world-join burst has settled.
 *
 * <p>Vanilla closes this screen from its tick as soon as the level is ready, which is while
 * Remix pays its first-sight costs behind the screen. The ready check reports false while
 * {@link JoinBurstHold#keepScreen(long)} says so; vanilla retries every tick, so the screen
 * closes on the first tick after the hold ends.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class DownloadingTerrainScreenMixin {

    private boolean rtxremix$holdBegun;

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;isLevelReady()Z"
            ))
    private boolean rtxremix$holdUntilSettled(LevelLoadTracker tracker) {
        boolean ready = tracker.isLevelReady();
        if (!ready) {
            return false;
        }
        JoinBurstHold hold = RemixRenderer.activeJoinBurstHold();
        if (hold == null) {
            return true;
        }
        long now = System.nanoTime();
        if (!rtxremix$holdBegun) {
            rtxremix$holdBegun = true;
            hold.begin(now);
        }
        return !hold.keepScreen(now);
    }
}
