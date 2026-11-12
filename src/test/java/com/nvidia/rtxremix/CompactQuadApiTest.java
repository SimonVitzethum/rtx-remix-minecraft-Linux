/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class CompactQuadApiTest {
    @Test void acceptsOnlyDirectExclusiveBoundedDescriptors() {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.compactQuadBuffer = ByteBuffer.allocateDirect(96).order(ByteOrder.LITTLE_ENDIAN);
        surface.compactQuadCount = 1;
        assertDoesNotThrow(() -> RemixAPI.validateCompactSurface(surface));
        surface.compactQuadCount = Integer.MAX_VALUE;
        assertEquals(ErrorCode.INVALID_ARGUMENTS, assertThrows(RemixException.class,
                () -> RemixAPI.validateCompactSurface(surface)).getErrorCode());
        surface.compactQuadCount = 1;
        surface.vertexCount = 4;
        assertThrows(RemixException.class, () -> RemixAPI.validateCompactSurface(surface));
        surface.vertexCount = 0;
        surface.compactQuadBuffer.position(1);
        assertThrows(RemixException.class, () -> RemixAPI.validateCompactSurface(surface));
        surface.compactQuadBuffer = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        assertThrows(RemixException.class, () -> RemixAPI.validateCompactSurface(surface));
    }

    @Test void admissionErrorIsDistinctFromInternalFailure() {
        assertEquals(14, ErrorCode.RESOURCE_BUSY);
        assertEquals("RESOURCE_BUSY", ErrorCode.toString(ErrorCode.RESOURCE_BUSY));
        assertNotEquals(ErrorCode.GENERAL_FAILURE, ErrorCode.RESOURCE_BUSY);
        assertEquals(32, StructType.MESH_INFO_COMPACT_QUADS_EXT);
        assertEquals(3, RemixAPI.VERSION_PATCH);
    }
}
