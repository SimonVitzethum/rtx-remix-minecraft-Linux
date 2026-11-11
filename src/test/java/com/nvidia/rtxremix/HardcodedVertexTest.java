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
