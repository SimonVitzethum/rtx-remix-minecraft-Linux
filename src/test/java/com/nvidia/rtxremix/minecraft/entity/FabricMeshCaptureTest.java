/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nvidia.rtxremix.HardcodedVertex;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricMeshCaptureTest {
    @AfterEach
    void resetContext() {
        EntityRenderContext.get().reset();
    }

    @Test
    void meshAttributesKeepLocalCoordinatesTintNormalsAndSpriteBoundsAcrossPoseReuse() {
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        PoseStack poses = new PoseStack();
        poses.translate(7, 11, 13);
        poses.rotateDegrees(Axis.YP, 30);
        poses.scale(2, 3, 4);
        Matrix4f expected = new Matrix4f(poses.last().pose());
        var sprite = SpriteFixtures.sprite(4, 8, 4, 16);
        int[] colors = {-1, 0xff804020, 0x4080ff40, 0xff102040};
        int tint = 0x80cc8844;
        QuadView quad = quad(colors);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        consumer.putQuad(poses.last(), quad, sprite, tint, OverlayTexture.NO_OVERLAY);
        consumer.finishOpenVertex();

        assertEquals(4, consumer.getVertexCount());
        assertArrayEquals(new float[] {0, 1, 1, 0}, java.util.Arrays.copyOf(consumer.getPx(), 4));
        assertArrayEquals(new float[] {0, 0, 1, 1}, java.util.Arrays.copyOf(consumer.getPy(), 4));
        for (int v = 0; v < 4; v++) {
            assertEquals(0.25f, consumer.getPz()[v]);
            assertEquals(ARGB.multiply(colors[v], tint), consumer.getColors()[v]);
            assertEquals(HardcodedVertex.packUnorm2x16(0.25f, 0.5f), consumer.getTexcoordBoundsMin()[v]);
            assertEquals(HardcodedVertex.packUnorm2x16(0.5f, 0.75f), consumer.getTexcoordBoundsMax()[v]);
        }
        assertEquals(0.6f, consumer.getNx()[0]);
        assertEquals(0.8f, consumer.getNy()[0]);
        assertEquals(0f, consumer.getNz()[0]);
        assertEquals(-1f, consumer.getNz()[1]);
        poses.last().pose().identity();
        assertEquals(expected, context.getBoneTransforms().get(consumer.getBoneIndices()[0]));
    }

    @Test
    void fabricBlockAndItemOverloadsVisitMeshWithoutDuplicatingVanillaQuads() {
        AtomicInteger requestedBuffers = new AtomicInteger();
        AtomicInteger vanillaQuads = new AtomicInteger();
        VertexConsumer discarded = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[] {VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("putBakedQuad")) {
                        vanillaQuads.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("A discarded buffer must receive no vertices");
                });
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider() {
            @Override
            public VertexConsumer getBuffer(RenderType layer) {
                requestedBuffers.incrementAndGet();
                return discarded;
            }
        };
        RemixSubmitCollector collector = new RemixSubmitCollector(provider);
        AtomicInteger visits = new AtomicInteger();
        Mesh mesh = mesh(quad(new int[] {-1, -1, -1, -1}), visits);
        BakedQuad baked = new BakedQuad(new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0), UVPair.pack(0, 0), UVPair.pack(1, 0),
                UVPair.pack(1, 1), UVPair.pack(0, 1), Direction.NORTH,
                new BakedQuad.MaterialInfo(null, ChunkSectionLayer.CUTOUT, null, null, null, 0, null, 0));
        BlockStateModelPart part = (BlockStateModelPart) Proxy.newProxyInstance(
                BlockStateModelPart.class.getClassLoader(), new Class<?>[] {BlockStateModelPart.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getQuads")) return args[0] == null ? List.of(baked) : List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
        PoseStack poses = new PoseStack();
        collector.submitBlockModel(poses, layer -> {
            assertEquals(ChunkSectionLayer.CUTOUT, layer);
            return null;
        }, false, List.of(part), mesh, new int[] {0xff123456}, 17, 29, 0);
        collector.submitItem(poses, ItemDisplayContext.GROUND, 17, 29, 0,
                new int[] {0xff123456}, new ItemQuads(List.of(baked), List.of(baked), List.of()),
                mesh, ItemStackRenderState.FoilType.NONE);
        assertEquals(2, visits.get());
        assertEquals(2, vanillaQuads.get());
        assertEquals(4, requestedBuffers.get());
    }

    @Test
    void fabricVertexOverlayUsesTheSameEntityOverlayAsBakedGeometry() {
        EntityRenderContext.get().begin(true);
        int color = 0x80a05020;
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        int overlay = OverlayTexture.pack(0, OverlayTexture.v(true));
        consumer.putQuad(new PoseStack().last(), quad(new int[] {color, color, color, color}),
                SpriteFixtures.sprite(0, 0, 4, 16), -1, overlay);
        assertEquals(RemixCapturingConsumer.applyEntityOverlay(color, overlay), consumer.getColors()[0]);
    }

    private static QuadView quad(int[] colors) {
        float[] xs = {0, 1, 1, 0};
        float[] ys = {0, 0, 1, 1};
        return (QuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[] {QuadView.class},
                (proxy, method, args) -> {
                    int v = args == null || args.length == 0 ? 0 : (int) args[0];
                    return switch (method.getName()) {
                        case "x" -> xs[v];
                        case "y" -> ys[v];
                        case "z" -> 0.25f;
                        case "u" -> 0.25f + 0.25f * xs[v];
                        case "v" -> 0.5f + 0.25f * ys[v];
                        case "color" -> colors[v];
                        case "hasNormal" -> v == 0;
                        case "normalX" -> 0.6f;
                        case "normalY" -> 0.8f;
                        case "normalZ" -> 0f;
                        case "faceNormal" -> new Vector3f(0, 0, -1);
                        case "itemRenderType" -> null;
                        case "chunkLayer" -> ChunkSectionLayer.CUTOUT;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static Mesh mesh(QuadView quad, AtomicInteger visits) {
        return (Mesh) Proxy.newProxyInstance(Mesh.class.getClassLoader(), new Class<?>[] {Mesh.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("size")) return 1;
                    if (method.getName().equals("forEach")) {
                        visits.incrementAndGet();
                        @SuppressWarnings("unchecked") Consumer<QuadView> sink = (Consumer<QuadView>) args[0];
                        sink.accept(quad);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
