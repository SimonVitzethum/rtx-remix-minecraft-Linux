/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Optional Bobby integration (reflection only, Bobby is a "suggests" and never a compile dependency). Bobby keeps the chunks
 * the server sent on disk and shows them beyond the server view distance as "fake chunks" ({@code FakeChunkManager}); they are
 * real {@link LevelChunk} objects, so the far field voxelises them exactly like loaded chunks. Bobby's mixin also answers
 * {@code ClientChunkCache.getChunk} with them inside the render distance, so those arrive through the normal path as well;
 * this class adds the ones beyond it (Bobby's own max render distance can exceed the vanilla option).
 */
public final class BobbyCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger(BobbyCompat.class);
    private static final String FAKE_PACKAGE = "de.johni0702.minecraft.bobby.";
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("bobby");
    private static boolean resolved, broken;
    private static Class<?> extClass;
    private static Method getManager, getFakeChunks;

    private BobbyCompat() {}

    public static boolean isPresent() {
        return PRESENT;
    }

    /** True for Bobby's {@code FakeChunk} (a chunk replayed from its disk cache, not a live one). */
    public static boolean isFake(LevelChunk chunk) {
        return PRESENT && chunk.getClass().getName().startsWith(FAKE_PACKAGE);
    }

    /** Snapshot of Bobby's fake chunks of this level; empty without Bobby or when its internals are not as expected. */
    public static List<LevelChunk> fakeChunks(ClientLevel level) {
        if (!PRESENT || broken) return List.of();
        try {
            if (!resolved) {
                resolved = true;
                extClass = Class.forName(FAKE_PACKAGE + "ext.ClientChunkCacheExt");
                getManager = extClass.getMethod("bobby_getFakeChunkManager");
                getFakeChunks = Class.forName(FAKE_PACKAGE + "FakeChunkManager").getMethod("getFakeChunks");
                LOGGER.info("Bobby detected: far field reads its fake chunks");
            }
            Object cache = level.getChunkSource();
            if (!extClass.isInstance(cache)) return List.of();
            Object manager = getManager.invoke(cache);
            if (manager == null) return List.of();
            @SuppressWarnings("unchecked")
            Collection<LevelChunk> c = (Collection<LevelChunk>) getFakeChunks.invoke(manager);
            return new ArrayList<>(c);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            LOGGER.warn("Bobby compat disabled (unexpected internals)", e);
            return List.of();
        }
    }
}
