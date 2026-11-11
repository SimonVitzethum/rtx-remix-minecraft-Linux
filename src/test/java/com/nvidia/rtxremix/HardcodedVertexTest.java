/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardcodedVertexTest {
    @Test
    void atlasTexelBoundariesSurviveUnormPackingOnLargeRectangularAtlases() {
        for (int dimension : new int[] {2048, 8192, 12288, 16384, 32768}) {
            for (int texel = 0; texel <= dimension; texel++) {
                float uv = texel / (float) dimension;
                int packed = HardcodedVertex.packUnorm2x16(uv, uv);
                assertEquals(texel, Math.round((packed & 0xffff) / 65535.0f * dimension));
                assertEquals(texel, Math.round((packed >>> 16) / 65535.0f * dimension));
            }
        }
    }

    @Test
    void legacyHalfPackingPreservesSubnormalBoundsAndNanEncoding() {
        assertEquals(0x7e000200, HardcodedVertex.packHalf2x16(0x1.0p-15f, Float.NaN));
        assertEquals(0x7c000001, HardcodedVertex.packHalf2x16(0x1.0p-24f, Float.POSITIVE_INFINITY));
    }

    @Test
    void legacyHalfPackingMatchesTheJdkReferenceIncludingRoundingBoundaries() {
        for (int half = 0; half <= 0xffff; half++) {
            float value = Float.float16ToFloat((short) half);
            if (!Float.isNaN(value)) {
                assertEquals(half, HardcodedVertex.packHalf2x16(value, 0) & 0xffff);
            }
        }
        java.util.Random random = new java.util.Random(20);
        for (int i = 0; i < 10000; i++) {
            float value = Float.intBitsToFloat(random.nextInt());
            if (!Float.isNaN(value)) {
                assertEquals(Short.toUnsignedInt(Float.floatToFloat16(value)),
                        HardcodedVertex.packHalf2x16(value, 0) & 0xffff);
            }
        }
        assertEquals(0x3c00, HardcodedVertex.packHalf2x16(1 + 0x1.0p-11f, 0));
        assertEquals(0x3c02, HardcodedVertex.packHalf2x16(1 + 3 * 0x1.0p-11f, 0));
        assertEquals(0, HardcodedVertex.packHalf2x16(0x1.0p-25f, 0));
        assertEquals(2, HardcodedVertex.packHalf2x16(3 * 0x1.0p-25f, 0));
    }

    @Test
    void nativeLayoutKeepsBoundsAtBytes36And40AndStride64() {
        HardcodedVertex vertex = new HardcodedVertex();
        vertex.setTexcoordBounds(0.25f, 0.5f, 0.75f, 1.0f);
        ByteBuffer buffer = ByteBuffer.allocateDirect(HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
        vertex.writeTo(buffer);
        assertEquals(64, buffer.position());
        assertEquals(0x80004000, buffer.getInt(36));
        assertEquals(0xffffbfff, buffer.getInt(40));
        assertEquals(1, MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
        assertEquals(2, MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS_UNORM16);
    }
}
