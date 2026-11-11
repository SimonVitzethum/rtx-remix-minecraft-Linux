/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nvidia.rtxremix.MaterialInfo;
import org.junit.jupiter.api.Test;

class BlockTextureManagerTranslucentMaterialTest {

    @Test
    void authoredRgbaUsesTheNativeDiffuseTextureSlotAndKeepsRefraction() {
        MaterialInfo material = BlockTextureManager.translucentMaterialInfo(101, 202, 303, 25.0f);

        assertNull(material.opaqueExt);
        assertNotNull(material.translucentExt);
        assertEquals(101, material.albedoTexture);
        assertEquals(101, material.translucentExt.transmittanceTexture);
        assertEquals(1, material.translucentExt.useDiffuseLayer);
        assertEquals(1.52f, material.translucentExt.refractiveIndex);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.x);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.y);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.z);
        assertEquals(202, material.normalTexture);
        assertEquals(303, material.emissiveTexture);
        assertEquals(25.0f, material.emissiveIntensity);
    }

    @Test
    void plainRgbaStillBindsItsDiffuseTextureWithoutOptionalPbrChannels() {
        MaterialInfo material = BlockTextureManager.translucentMaterialInfo(404, 0, 0, 100.0f);

        assertEquals(404, material.translucentExt.transmittanceTexture);
        assertEquals(1, material.translucentExt.useDiffuseLayer);
        assertEquals(0, material.normalTexture);
        assertEquals(0, material.emissiveTexture);
    }
}
