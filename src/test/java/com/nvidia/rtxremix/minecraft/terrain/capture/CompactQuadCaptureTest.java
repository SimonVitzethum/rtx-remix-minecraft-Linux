/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.HardcodedVertex;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class CompactQuadCaptureTest {
    private static final float[] POSITIONS = {0.5f, 0, 0, 1, 0, 0, 1, 0.5f, 0, 0.5f, 0.5f, 0};
    private static final float[] UV = {0.2f, 0.9f, -0.0f, 0.1f, 0.7f, 0.6f, 0.4f, 0.3f};

    @AfterEach void disableCapture() { CompactQuadCapture.configure(false); }

    @Test void affineStairFacePreservesAllVertexWordsAndArbitraryUvCorners() {
        ByteBuffer descriptor = buffer(96);
        float[] uv = UV.clone();
        uv[7] = Float.intBitsToFloat(0x7fc12345);
        assertTrue(CompactQuadCapture.tryWrite(descriptor, POSITIONS, 0, uv, 0,
                -0.0f, 0, 1, 0x43ab7f11, 0x12345678, 0x7fff00ff, -64));
        assertEquals(96, descriptor.position());
        ByteBuffer output = buffer(256);
        CompactQuadCapture.expandForLod(descriptor, 1, output);
        for (int corner = 0; corner < 4; corner++) {
            int offset = corner * HardcodedVertex.STRIDE;
            assertRaw(POSITIONS[corner * 3], output.getFloat(offset));
            assertRaw(POSITIONS[corner * 3 + 1] - 64, output.getFloat(offset + 4));
            assertRaw(POSITIONS[corner * 3 + 2], output.getFloat(offset + 8));
            assertEquals(0x80000000, output.getInt(offset + 12));
            assertEquals(0, output.getInt(offset + 16));
            assertRaw(1, output.getFloat(offset + 20));
            assertRaw(uv[corner * 2], output.getFloat(offset + 24));
            assertRaw(uv[corner * 2 + 1], output.getFloat(offset + 28));
            assertEquals(0x43ab7f11, output.getInt(offset + 32));
            assertEquals(0x12345678, output.getInt(offset + 36));
            assertEquals(0x7fff00ff, output.getInt(offset + 40));
            for (int word = 44; word < 64; word += 4) assertEquals(0, output.getInt(offset + word));
        }
    }

    @Test void rejectsLossOfShapeSignedZeroSubnormalAndNonfiniteWithoutWriting() {
        for (float rejected : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -0.0f, Float.MIN_VALUE}) {
            float[] positions = POSITIONS.clone();
            positions[0] = rejected;
            ByteBuffer target = buffer(96);
            assertFalse(CompactQuadCapture.tryWrite(target, positions, 0, UV, 0, 0, 0, 1, -1, 0, -1, 0));
            assertEquals(0, target.position());
        }
        float[] warped = POSITIONS.clone();
        warped[8] = 0.125f;
        ByteBuffer target = buffer(96);
        assertFalse(CompactQuadCapture.tryWrite(target, warped, 0, UV, 0, 0, 0, 1, -1, 0, -1, 0));
        assertEquals(0, target.position());
    }

    @Test void captureStoresDescriptorsWithoutBuildingExpandedVerticesAndKeepsWarpedFallback() {
        CompactQuadCapture.configure(true);
        DirectQuadWriter writer = new DirectQuadWriter();
        writer.beginSection(0, -64, 0);
        stage(writer, POSITIONS);
        float[] warped = POSITIONS.clone();
        warped[8] = 0.125f;
        stage(writer, warped);
        writer.flushBlock(null);
        SectionMeshData data = writer.buildMeshData();
        try {
            assertEquals(2, data.surfaces().size());
            SectionMeshData.Surface gpu = data.surfaces().get(0);
            SectionMeshData.Surface cpu = data.surfaces().get(1);
            assertTrue(gpu.compactQuads());
            assertFalse(cpu.compactQuads());
            assertEquals(96, gpu.vertexBuffer().remaining());
            assertEquals(256, cpu.vertexBuffer().remaining());
            assertEquals(4, gpu.vertexCount());
            assertEquals(6, gpu.indexCount());
            assertEquals(new SectionMeshData(data.surfaces()).contentHash(), data.contentHash());
        } finally {
            data.release();
            writer.releaseBuffers();
        }
        assertThrows(IllegalStateException.class, data::surfaces);
    }

    private static void stage(DirectQuadWriter writer, float[] positions) {
        writer.stageQuad(positions, UV, 0, 0, 1, -1, Identifier.withDefaultNamespace("test/compact"),
                0, 0, 1, 1, "solid");
    }
    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static void assertRaw(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
