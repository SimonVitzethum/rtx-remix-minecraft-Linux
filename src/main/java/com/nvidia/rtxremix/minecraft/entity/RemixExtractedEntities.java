/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.IdentityHashMap;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Pairs the render states vanilla extracts for the frame with the entities they came from.
 *
 * <p>26.3 extracts every visible entity into an {@link EntityRenderState} on the render
 * thread and submits the states later; a state does not name its entity, and the capture
 * keys retained instances on the entity itself. {@code LevelExtractorMixin} records each
 * pair as it is extracted, {@code WorldRendererMixin} resolves a state back to its entity
 * where vanilla submits it, and {@link RemixEntityPass} takes the state so the entity is
 * extracted once rather than once for vanilla and again for Remix. Render thread only.
 */
public final class RemixExtractedEntities {

    private static final IdentityHashMap<EntityRenderState, Entity> ENTITY_OF_STATE = new IdentityHashMap<>();
    private static final IdentityHashMap<Entity, EntityRenderState> STATE_OF_ENTITY = new IdentityHashMap<>();

    private RemixExtractedEntities() {}

    /** Starts a frame's extraction; the previous frame's pairs are dropped. */
    public static void beginFrame() {
        ENTITY_OF_STATE.clear();
        STATE_OF_ENTITY.clear();
    }

    public static void record(Entity entity, EntityRenderState state) {
        ENTITY_OF_STATE.put(state, entity);
        STATE_OF_ENTITY.put(entity, state);
    }

    /** The entity a state was extracted from this frame, or null. */
    public static Entity entityOf(EntityRenderState state) {
        return ENTITY_OF_STATE.get(state);
    }

    /** Claims the state extracted for {@code entity} this frame, or null when there is none. */
    static EntityRenderState take(Entity entity) {
        return STATE_OF_ENTITY.remove(entity);
    }
}
