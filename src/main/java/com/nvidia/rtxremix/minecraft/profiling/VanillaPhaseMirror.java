/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import java.util.ArrayDeque;

/**
 * Mirrors the push/pop phases of vanilla's {@code ProfilerFiller} in the level extraction
 * and render into {@link ProfileScope}s, so Tracy and JFR show vanilla's phases
 * ({@code WorldRenderer.phase.submitFeatures}, {@code ...executeFrameGraph}, ...) as before.
 * Scopes nest the way the profiler's push/pop do; a pop with nothing open is ignored, and
 * nothing is created unless deep profiling is on. Render thread only.
 */
public final class VanillaPhaseMirror {

    private static final String PREFIX = "WorldRenderer.phase.";
    private static final ArrayDeque<ProfileScope> OPEN = new ArrayDeque<>();

    private VanillaPhaseMirror() {
    }

    public static void push(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        String name = PREFIX + vanillaPhaseName;
        OPEN.push(ProfileScope.begin(name).plotOnClose("Java." + name + ".ms"));
    }

    public static void pop() {
        ProfileScope scope = OPEN.poll();
        if (scope != null) {
            scope.close();
        }
    }

    public static void popPush(String vanillaPhaseName) {
        pop();
        push(vanillaPhaseName);
    }
}
