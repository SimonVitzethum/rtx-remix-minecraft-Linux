/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hooks the atlas animation tick.
 *
 * <p>26.3: animated sprites are no longer re-uploaded from the CPU every time their frame
 * changes. Each animated sprite owns a {@code SpriteContents.AnimationState} holding one GPU
 * texture per unique frame, and {@code uploadAnimationFrames} draws the current frames into
 * the atlas with a render pass. So the 1.21 hook on {@code SpriteContents.upload} has nothing
 * to intercept; the equivalent point is the end of {@code cycleAnimationFrames}, where every
 * state that just changed frame is dirty. For each of those, the sprite's current frame is
 * forwarded as a patch read from the sprite's retained CPU image, exactly the data the old
 * upload carried. A sprite that interpolates between frames is blended on the GPU only; its
 * patch is the current frame without the blend.
 */
@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private Identifier location;
    @Shadow private List<TextureAtlasSprite> sprites;
    @Shadow private List<SpriteContents.AnimationState> animatedTexturesStates;
    @Shadow private int width;
    @Shadow private int height;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void rtxremix$onAnimatedSpriteTickStart(CallbackInfo ci) {
        AtlasAnimationForwarder.beginAtlasUpload(location);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        try {
            rtxremix$forwardChangedFrames();
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    /** Walks the animated sprites in the order the atlas created their animation states. */
    private void rtxremix$forwardChangedFrames() {
        List<SpriteContents.AnimationState> states = animatedTexturesStates;
        if (states.isEmpty()) {
            return;
        }
        int stateIndex = 0;
        for (int i = 0, n = sprites.size(); i < n && stateIndex < states.size(); i++) {
            TextureAtlasSprite sprite = sprites.get(i);
            if (!sprite.isAnimated()) {
                continue;
            }
            SpriteContents.AnimationState state = states.get(stateIndex++);
            if (!state.isDirty) {
                continue;
            }
            SpriteContents contents = sprite.contents();
            SpriteContents.AnimatedTexture animation = state.animationInfo;
            int frameIndex = animation.frames.get(state.frame).index();
            int frameRowSize = animation.frameRowSize;
            int srcX = (frameIndex % frameRowSize) * contents.width();
            int srcY = (frameIndex / frameRowSize) * contents.height();
            NativeImage image = ((SpriteContentsAccessor) contents).getImage();
            if (image == null || image.isClosed()) {
                continue;
            }
            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();
            long pixelPtr = image.getPointer() + ((long) srcY * imgWidth + srcX) * 4;
            // The sprite's content starts after its padding, which the UVs already include.
            int texX = Math.round(sprite.getU0() * width);
            int texY = Math.round(sprite.getV0() * height);
            AtlasAnimationForwarder.addPatch(contents.name(), texX, texY, srcX, srcY,
                    contents.width(), contents.height(), imgWidth, imgHeight, pixelPtr, imgWidth * 4);
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }
}
