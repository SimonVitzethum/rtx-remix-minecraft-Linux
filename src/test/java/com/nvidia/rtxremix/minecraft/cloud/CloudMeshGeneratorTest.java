/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudMeshGeneratorTest {
    @Test
    void paletteSeedsProduceVariedSealedClouds() {
        Set<Long> checksums = new HashSet<>();
        for (int paletteIndex = 0;
                paletteIndex < CloudRenderer.PALETTE_SIZE;
                paletteIndex++) {
            CloudMeshGenerator.GeneratedMesh mesh = generatePaletteMesh(paletteIndex);
            assertTrue(mesh.vertexCount() > 0);
            checksums.add(checksum(mesh));
        }

        assertEquals(CloudRenderer.PALETTE_SIZE, checksums.size());
    }

    @Test
    void coarserLevelsKeepShapeWithFarFewerTriangles() {
        CloudMeshGenerator.GeneratedMesh fine = CloudMeshGenerator.generate(
                0x77L, CloudMeshGenerator.LOD_COUNT);
        assertEquals(CloudMeshGenerator.LOD_COUNT - 1, fine.coarser().length);
        float[] fineBounds = bounds(fine);
        int previousTriangles = fine.triangleCount();
        for (CloudMeshGenerator.GeneratedMesh coarse : fine.coarser()) {
            assertTrue(coarse.triangleCount() > 0);
            assertTrue(coarse.triangleCount() < previousTriangles / 2);
            previousTriangles = coarse.triangleCount();
            for (int index : coarse.indices()) {
                assertTrue(index >= 0 && index < coarse.vertexCount());
            }
            float[] coarseBounds = bounds(coarse);
            for (int i = 0; i < 6; i++) {
                // The silhouette moves by at most a coarse cell or two.
                assertEquals(fineBounds[i], coarseBounds[i], 8.0f);
            }
        }
    }

    @Test
    void lodSelectionHasHysteresis() {
        assertEquals(0, CloudLod.select(100, 0, 256, 2));
        assertEquals(0, CloudLod.select(270, 0, 256, 2));
        assertEquals(1, CloudLod.select(290, 0, 256, 2));
        assertEquals(1, CloudLod.select(250, 1, 256, 2));
        assertEquals(0, CloudLod.select(230, 1, 256, 2));
        assertEquals(2, CloudLod.select(700, 0, 256, 2));
        assertEquals(1, CloudLod.select(700, 0, 256, 1));
        assertEquals(0, CloudLod.select(5000, 0, 0, 2));
    }

    private static float[] bounds(CloudMeshGenerator.GeneratedMesh mesh) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], v[i * 6 + a]);
                b[a + 3] = Math.max(b[a + 3], v[i * 6 + a]);
            }
        }
        return b;
    }

    @Test
    void sameSeedProducesIdenticalMesh() {
        CloudMeshGenerator.GeneratedMesh first = CloudMeshGenerator.generate(0x1234_5678L);
        CloudMeshGenerator.GeneratedMesh second = CloudMeshGenerator.generate(0x1234_5678L);
        CloudMeshGenerator.GeneratedMesh different = CloudMeshGenerator.generate(0x1234_5679L);

        assertTrue(first.vertexCount() > 0);
        assertTrue(first.triangleCount() > 0);
        assertEquals(checksum(first), checksum(second));
        assertNotEquals(checksum(first), checksum(different));
    }

    @Test
    void indicesAndGradientNormalsAreValid() {
        CloudMeshGenerator.GeneratedMesh mesh = CloudMeshGenerator.generate(42L);

        for (int index : mesh.indices()) {
            assertTrue(index >= 0 && index < mesh.vertexCount());
        }
        float[] vertices = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * 6;
            float nx = vertices[base + 3];
            float ny = vertices[base + 4];
            float nz = vertices[base + 5];
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertTrue(Float.isFinite(length));
            assertEquals(1.0f, length, 1.0e-4f);
        }
    }

    @Test
    void adjacentTrianglesHaveConsistentWinding() {
        CloudMeshGenerator.GeneratedMesh mesh = CloudMeshGenerator.generate(73L);
        Map<Long, Integer> edgeDirections = new HashMap<>();
        int[] indices = mesh.indices();
        for (int i = 0; i < indices.length; i += 3) {
            countDirectedEdge(edgeDirections, indices[i], indices[i + 1]);
            countDirectedEdge(edgeDirections, indices[i + 1], indices[i + 2]);
            countDirectedEdge(edgeDirections, indices[i + 2], indices[i]);
        }
        for (int balance : edgeDirections.values()) {
            assertEquals(0, balance);
        }
    }

    @Test
    void generatedSurfacesAreClosed() {
        for (int paletteIndex = 0;
                paletteIndex < CloudRenderer.PALETTE_SIZE;
                paletteIndex++) {
            CloudMeshGenerator.GeneratedMesh mesh = generatePaletteMesh(paletteIndex);
            Map<Long, Integer> edgeUses = new HashMap<>();
            int[] indices = mesh.indices();
            for (int i = 0; i < indices.length; i += 3) {
                countEdge(edgeUses, indices[i], indices[i + 1]);
                countEdge(edgeUses, indices[i + 1], indices[i + 2]);
                countEdge(edgeUses, indices[i + 2], indices[i]);
            }

            assertTrue(edgeUses.size() > 0);
            for (int uses : edgeUses.values()) {
                assertEquals(2, uses);
            }
        }
    }

    private static CloudMeshGenerator.GeneratedMesh generatePaletteMesh(int index) {
        return CloudMeshGenerator.generate(
                CloudPlacement.mix(CloudRenderer.PALETTE_SEED + index));
    }

    private static long checksum(CloudMeshGenerator.GeneratedMesh mesh) {
        long hash = 0xcbf2_9ce4_8422_2325L;
        for (float value : mesh.vertices()) {
            hash ^= Float.floatToRawIntBits(value);
            hash *= 0x100_0000_01b3L;
        }
        for (int index : mesh.indices()) {
            hash ^= index;
            hash *= 0x100_0000_01b3L;
        }
        return hash;
    }

    private static void countEdge(Map<Long, Integer> uses, int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = ((long) min << 32) | (max & 0xffff_ffffL);
        uses.merge(key, 1, Integer::sum);
    }

    private static void countDirectedEdge(Map<Long, Integer> directions, int from, int to) {
        int min = Math.min(from, to);
        int max = Math.max(from, to);
        long key = ((long) min << 32) | (max & 0xffff_ffffL);
        directions.merge(key, from == min ? 1 : -1, Integer::sum);
    }
}
