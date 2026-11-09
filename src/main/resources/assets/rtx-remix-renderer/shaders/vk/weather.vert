// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

#version 450

// Rain and snow columns (Minecraft's PARTICLE vertex format, camera relative) drawn over the composited Remix image.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

layout(push_constant) uniform Push {
    mat4 ModelView;
    // Perspective with zero-to-one depth: x = m00, y = m11, z = m22, w = m32 (clip.w = -view.z)
    vec4 Projection;
    // x = 1 for rain (refraction shader), 0 for snow
    vec4 Params;
};

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 viewPosition;
layout(location = 3) out vec2 lightUv;

void main() {
    viewPosition = (ModelView * vec4(Position, 1.0)).xyz;
    gl_Position = vec4(
        Projection.x * viewPosition.x,
        Projection.y * viewPosition.y,
        Projection.z * viewPosition.z + Projection.w,
        -viewPosition.z);
    texCoord = UV0;
    vertexColor = Color;
    lightUv = vec2(UV2) / 256.0;
}
