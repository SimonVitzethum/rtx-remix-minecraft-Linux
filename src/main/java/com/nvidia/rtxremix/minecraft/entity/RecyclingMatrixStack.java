/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>26.3's {@link PoseStack} already retains its popped entries and copies them back
 * into service on the next push, which is what this class used to add. What remains is
 * the allocation-free model-part transform: {@code ModelPartMixin} routes
 * {@code ModelPart.translateAndRotate} through {@link #applyModelPartTransform} when
 * the stack is one of these, so the capture's per-part rotation never allocates a
 * quaternion. The capture stack belongs to this class and never changes vanilla
 * rendering outside the capture pass.
 */
public final class RecyclingMatrixStack extends PoseStack {

    private final Quaternionf modelPartRotation = new Quaternionf();

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        while (!isEmpty()) {
            popPose();
        }
        setIdentity();
    }

    /** Applies vanilla's {@code ModelPart.translateAndRotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float pivotX, float pivotY, float pivotZ,
                                        float pitch, float yaw, float roll,
                                        float xScale, float yScale, float zScale) {
        translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        if (pitch != 0.0F || yaw != 0.0F || roll != 0.0F) {
            rotate(modelPartRotation.rotationZYX(roll, yaw, pitch));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }
}
