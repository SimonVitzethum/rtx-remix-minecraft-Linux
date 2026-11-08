/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * Where an {@code ItemStackRenderState} came from. 26.3 resolves an item into layers of
 * quads and special renderers and forgets the stack and who holds it, but the capture
 * needs both: the stack classifies a held block's layer and decides whether a torch
 * burns, and the owner is the holder whose state decides it.
 */
public interface RemixItemSource {

    void rtxremix$setSource(ItemStack stack, ItemOwner owner);

    ItemStack rtxremix$stack();

    ItemOwner rtxremix$owner();
}
