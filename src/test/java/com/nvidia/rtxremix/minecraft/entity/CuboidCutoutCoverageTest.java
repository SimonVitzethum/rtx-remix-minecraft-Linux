/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.shulker.ShulkerModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidCutoutCoverageTest {
    private static final Identifier ATLAS = Identifier.withDefaultNamespace("textures/atlas/test.png");

    private static TextureAtlasSprite sprite(NativeImage image) {
        SpriteContents contents = new SpriteContents(Identifier.withDefaultNamespace("test/cutout"),
                new FrameSize(image.getWidth(), image.getHeight()), image);
        return new TextureAtlasSprite(ATLAS, contents, 256, 256, 64, 128, 0) {};
    }

    /** The same immutable polygons captured by CuboidMixin, from Minecraft's real box model. */
    private static ModelPart.Polygon[] shulkerSides(String part) throws Exception {
        var root = ShulkerModel.createBoxLayer().bakeRoot();
        var cube = root.getChild(part).getRandomCube(RandomSource.create(1));
        var field = ModelPart.Cube.class.getDeclaredField("polygons");
        field.setAccessible(true);
        return (ModelPart.Polygon[]) field.get(cube);
    }

    private static NativeImage whiteShulkerImage() throws Exception {
        try (var input = CuboidCutoutCoverageTest.class.getResourceAsStream(
                "/assets/minecraft/textures/entity/shulker/shulker_white.png")) {
            return NativeImage.read(input);
        }
    }

    @Test
    void realShulkerLidAndBaseCoverExactlyTheirVisibleTexelsAndKeepFaceBounds() throws Exception {
        TextureAtlasSprite sprite = sprite(whiteShulkerImage());
        try (var contents = sprite.contents()) {
            var consumer = new RemixCapturingConsumer(null, false);
            int removedTexels = 0;
            for (String part : new String[] {"lid", "base"}) {
                for (var face : shulkerSides(part)) {
                    consumer.clear();
                    consumer.emitCuboidGeometry(new ModelPart.Polygon[] {face}, 0xFFAABBCC, 7, sprite, 0f, true);
                    consumer.finishOpenVertex();
                    float minU = 1, minV = 1, maxU = 0, maxV = 0;
                    for (var vertex : face.vertices()) {
                        minU = Math.min(minU, vertex.u()); minV = Math.min(minV, vertex.v());
                        maxU = Math.max(maxU, vertex.u()); maxV = Math.max(maxV, vertex.v());
                    }
                    // POM must wrap over the original model face, not over each mask rectangle.
                    int boundsMin = HardcodedVertex.packUnorm2x16(sprite.getU(minU), sprite.getV(minV));
                    int boundsMax = HardcodedVertex.packUnorm2x16(sprite.getU(maxU), sprite.getV(maxV));
                    for (int i = 0; i < consumer.getVertexCount(); i++) {
                        assertEquals(boundsMin, consumer.getTexcoordBoundsMin()[i]);
                        assertEquals(boundsMax, consumer.getTexcoordBoundsMax()[i]);
                        assertEquals(7, consumer.getBoneIndices()[i]);
                        assertEquals(0xFFAABBCC, consumer.getColors()[i]);
                        assertEquals(face.normal().x(), consumer.getNx()[i]);
                        assertEquals(face.normal().y(), consumer.getNy()[i]);
                        assertEquals(face.normal().z(), consumer.getNz()[i]);
                    }
                    for (int y = 0; y < contents.height(); y++) {
                        for (int x = 0; x < contents.width(); x++) {
                            float u = (x + 0.5f) / contents.width(), v = (y + 0.5f) / contents.height();
                            if (u <= minU || u >= maxU || v <= minV || v >= maxV) continue;
                            boolean visible = !contents.isTransparent(0, x, y);
                            assertEquals(visible ? 1 : 0, coverage(consumer, sprite.getU(u), sprite.getV(v)),
                                    part + " texel " + x + "," + y);
                            if (!visible) removedTexels++;
                        }
                    }
                }
            }
            // Includes the authored 8x4 lid holes where a closed box's coplanar base must remain hittable.
            assertTrue(removedTexels >= 4 * 32);
        }
    }

    private static int coverage(RemixCapturingConsumer consumer, float u, float v) {
        int hits = 0;
        for (int start = 0; start < consumer.getVertexCount(); start += 4) {
            float minU = 1, minV = 1, maxU = 0, maxV = 0;
            for (int i = start; i < start + 4; i++) {
                minU = Math.min(minU, consumer.getTu()[i]); minV = Math.min(minV, consumer.getTv()[i]);
                maxU = Math.max(maxU, consumer.getTu()[i]); maxV = Math.max(maxV, consumer.getTv()[i]);
            }
            if (u > minU && u < maxU && v > minV && v < maxV) hits++;
        }
        return hits;
    }

    @Test
    void opaqueCuboidsRetainTheirOriginalPolygonsDespiteTransparentTextureTexels() throws Exception {
        TextureAtlasSprite sprite = sprite(whiteShulkerImage());
        try (var contents = sprite.contents()) {
            var sides = shulkerSides("lid");
            var consumer = new RemixCapturingConsumer(null, false);
            consumer.emitCuboidGeometry(sides, -1, 0, sprite, 0f, false);
            consumer.finishOpenVertex();
            assertEquals(sides.length * 4, consumer.getVertexCount());
            int i = 0;
            for (var face : sides) for (var vertex : face.vertices()) {
                assertEquals(vertex.x() / 16f, consumer.getPx()[i]);
                assertEquals(vertex.y() / 16f, consumer.getPy()[i]);
                assertEquals(vertex.z() / 16f, consumer.getPz()[i]);
                assertEquals(sprite.getU(vertex.u()), consumer.getTu()[i]);
                assertEquals(sprite.getV(vertex.v()), consumer.getTv()[i]);
                i++;
            }
        }
    }

    @Test
    void busyAlphaMasksKeepTheBoundedOriginalGeometry() throws Exception {
        NativeImage image = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            image.setPixel(x, y, ((x + y) & 1) == 0 ? -1 : 0);
        }
        TextureAtlasSprite sprite = sprite(image);
        try (var contents = sprite.contents()) {
            var sides = shulkerSides("lid");
            var consumer = new RemixCapturingConsumer(null, false);
            consumer.emitCuboidGeometry(sides, -1, 0, sprite, 0f, true);
            consumer.finishOpenVertex();
            assertEquals(sides.length * 4, consumer.getVertexCount());
        }
    }
}
