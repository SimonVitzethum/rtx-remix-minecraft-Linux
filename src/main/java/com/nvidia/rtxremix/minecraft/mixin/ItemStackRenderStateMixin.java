/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixItemSource;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the capture which item is being submitted and who holds it.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer, and clear
 * glass through its cutout one, so the render layer alone cannot tell a pane of stained
 * glass from an apple, or clear glass from a torch. The {@code ItemStack} can, and the
 * render state is where it is still known, set by {@code ItemModelResolverMixin} when the
 * state is built and published here for the duration of one {@code submit}. The holder
 * recorded with it is what lets the item hook decide whether a held torch is burning:
 * every hand-held render, first person and every entity's hands alike, resolves its item
 * through the same resolver.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements RemixItemSource {

    private ItemStack rtxremix$stack;
    private ItemOwner rtxremix$owner;

    @Override
    public void rtxremix$setSource(ItemStack stack, ItemOwner owner) {
        rtxremix$stack = stack;
        rtxremix$owner = owner;
    }

    @Override
    public ItemStack rtxremix$stack() {
        return rtxremix$stack;
    }

    @Override
    public ItemOwner rtxremix$owner() {
        return rtxremix$owner;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                    int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass() || rtxremix$stack == null) {
            return;
        }
        context.setItemStack(rtxremix$stack);
        context.setBlockItemLayer(rtxremix$blockLayer(rtxremix$stack));
        context.setHeldItemHolder(rtxremix$owner == null ? null : rtxremix$owner.asLivingEntity());
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                  int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setItemStack(null);
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
            context.clearHeldItemHolder();
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same classifier, so a
     * held block is classified exactly as its placed counterpart is.
     */
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
