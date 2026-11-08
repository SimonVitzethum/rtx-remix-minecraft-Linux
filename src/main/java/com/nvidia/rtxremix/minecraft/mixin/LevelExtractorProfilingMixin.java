/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.profiling.VanillaPhaseMirror;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mirrors the profiler phases of vanilla's level extraction ({@code level},
 * {@code prepareDispatchers}, {@code sectionUpdates}, {@code entities}, ...) into
 * Tracy/JFR scopes. The level render's own phases are mirrored in {@code WorldRendererMixin}.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorProfilingMixin {

    @WrapOperation(
            method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
    private void rtxremix$mirrorPush(ProfilerFiller profiler, String name, Operation<Void> original) {
        original.call(profiler, name);
        VanillaPhaseMirror.push("extract." + name);
    }

    @WrapOperation(
            method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void rtxremix$mirrorPopPush(ProfilerFiller profiler, String name, Operation<Void> original) {
        original.call(profiler, name);
        VanillaPhaseMirror.popPush("extract." + name);
    }

    @WrapOperation(
            method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"))
    private void rtxremix$mirrorPop(ProfilerFiller profiler, Operation<Void> original) {
        original.call(profiler);
        VanillaPhaseMirror.pop();
    }
}
