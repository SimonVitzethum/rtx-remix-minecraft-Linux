/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

/** Content revision of a section, including packet replacement and biome changes. Read on its owning thread. */
public interface FarSectionRevision {
    long rtxremix$farRevision();
}
