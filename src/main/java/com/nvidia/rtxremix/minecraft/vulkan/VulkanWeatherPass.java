/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * Rain and snow on the Vulkan backend (replaces the OpenGL {@code RemixWeatherRenderer} there). Vanilla's
 * {@code WeatherEffectRenderer} builds the column quads and the textures; {@code WeatherEffectRendererMixin} hands them
 * over and cancels vanilla's draw, which would only land in the render target the Remix composite overwrites. The quads
 * are drawn here, recorded into the composite's command buffer right after the Remix image was copied over the main
 * render target, with dynamic rendering into that target. Occlusion is a manual depth test against the exported Remix
 * primary depth image in the fragment shader (no depth attachment); the rain shader also samples the Remix color image
 * for refraction and reflection. Render thread only.
 */
public final class VulkanWeatherPass implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanWeatherPass.class);
    private static final String VERT = "/assets/rtx-remix-renderer/shaders/vk/weather.vert.spv";
    private static final String FRAG = "/assets/rtx-remix-renderer/shaders/vk/weather.frag.spv";
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final int VERTEX_STRIDE = 28;
    /** Room for (2 * 32 + 1)^2 columns, the largest weather radius the game offers. */
    private static final int MAX_QUADS = 65 * 65;
    private static final int PUSH_BYTES = 96;
    /** vkCmdUpdateBuffer limit (65536) rounded down to a multiple of the vertex stride and of 4. */
    private static final int UPDATE_CHUNK = 65520 / VERTEX_STRIDE * VERTEX_STRIDE;

    private static VulkanWeatherPass instance;

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long vma;
    private long vertexShader, fragmentShader, setLayout, pipelineLayout, pipeline, linearSampler, nearestSampler;
    private int pipelineFormat = -1;
    private long vertexBuffer, vertexAllocation, indexBuffer, indexAllocation;

    /** Latest vertex data of vanilla's prepare (rain columns first, then snow columns). */
    private final ByteBuffer cpuVertices = ByteBuffer.allocateDirect(MAX_QUADS * 4 * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private int vertexBytes;
    private boolean pending;
    private int rainQuads, snowQuads;
    private AbstractTexture rainTexture, snowTexture;

    private final Matrix4f modelView = new Matrix4f();
    private float projX = 1f, projY = 1f, projZ, projW;
    private boolean cameraValid;
    private int lastDrawCount;
    private int lastQuadCount;

    private VulkanWeatherPass(VulkanDevice device) {
        this.device = device;
        vk = device.vkDevice();
        vma = device.vma();
    }

    /** Creates the pass when Minecraft runs on Vulkan, else null. The Vulkan objects are created on first use. */
    static VulkanWeatherPass create() {
        if (!VulkanBackendSupport.isActive()) {
            return null;
        }
        instance = new VulkanWeatherPass(VulkanBackendSupport.device());
        return instance;
    }

    /** The active pass, or null (OpenGL backend, or the Vulkan interop does not exist yet). */
    public static VulkanWeatherPass get() {
        return instance;
    }

    // ---- hand-over from the mixin / renderer ---------------------------------------------------------

    /** Remix camera for the next frames: rotation only (the quads are camera relative), perspective like Remix's camera. */
    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        Quaternionf rotation = camera.rotation().conjugate(new Quaternionf());
        modelView.rotation(rotation);
        Matrix4f p = new Matrix4f().perspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, FAR_PLANE, true);
        projX = p.m00();
        projY = p.m11();
        projZ = p.m22();
        projW = p.m32();
        cameraValid = true;
    }

    /** Vanilla uploaded this frame's column vertices (rain columns first). The bytes are copied. */
    public void offerVertices(ByteBuffer vertices) {
        int n = Math.min(vertices.remaining(), cpuVertices.capacity());
        if (n < vertices.remaining()) {
            LOGGER.warn("Weather: {} vertex bytes exceed the {} bytes reserved, truncating", vertices.remaining(), n);
        }
        n -= n % (VERTEX_STRIDE * 4);
        cpuVertices.clear();
        cpuVertices.put(0, vertices, vertices.position(), n);
        vertexBytes = n;
    }

    /** Vanilla's draw call for this frame (cancelled by the mixin): remembers the split and the textures. */
    public void offerDraw(int rainColumns, int snowColumns, AbstractTexture rain, AbstractTexture snow) {
        rainQuads = rainColumns;
        snowQuads = snowColumns;
        rainTexture = rain;
        snowTexture = snow;
        pending = rainColumns + snowColumns > 0;
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    // ---- recording -----------------------------------------------------------------------------------

    /** Records this frame's weather into {@code cb} (outside a render pass), after the Remix image was copied into the target. */
    void record(VkCommandBuffer cb, MemoryStack stack, RenderTarget target, VulkanInterop interop, long colorImage,
            long depthImage) {
        lastDrawCount = 0;
        lastQuadCount = 0;
        if (!pending || !cameraValid || vertexBytes == 0) {
            return;
        }
        pending = false;
        long colorView = interop.colorView();
        long depthView = interop.depthView();
        if (colorView == 0L || depthView == 0L || rainTexture == null || snowTexture == null) {
            return;
        }
        GpuTextureView targetView = target.getColorTextureView();
        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (!(targetView instanceof VulkanGpuTextureView vkTarget) || !(lightmapView instanceof VulkanGpuTextureView vkLight)) {
            return;
        }
        ensureObjects(stack, VulkanConst.toVk(target.getColorTexture().getFormat()));

        int quads = Math.min(vertexBytes / (VERTEX_STRIDE * 4), rainQuads + snowQuads);
        int rain = Math.min(rainQuads, quads);
        int snow = quads - rain;

        // The Remix depth belongs to the other device: take it over for the draw and hand it back afterwards.
        interop.externalOwnershipTransfer(cb, stack, depthImage, false);
        uploadVertices(cb, stack);
        com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder.memoryBarrier(cb, stack);

        int w = target.width;
        int h = target.height;
        VkRenderingAttachmentInfoKHR.Buffer color = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                .imageView(vkTarget.vkImageView()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingInfoKHR rendering = VkRenderingInfoKHR.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color);
        rendering.renderArea().offset().set(0, 0);
        rendering.renderArea().extent().set(w, h);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, rendering);
        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0).y(0).width(w).height(h).minDepth(0f).maxDepth(1f);
        VK12.vkCmdSetViewport(cb, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(w, h);
        VK12.vkCmdSetScissor(cb, 0, scissor);
        VK12.vkCmdBindVertexBuffers(cb, 0, stack.longs(vertexBuffer), stack.longs(0L));
        VK12.vkCmdBindIndexBuffer(cb, indexBuffer, 0L, VK12.VK_INDEX_TYPE_UINT16);

        if (rain > 0) {
            draw(cb, stack, true, rainTexture, vkLight, colorView, depthView, 0, rain);
            lastDrawCount++;
        }
        if (snow > 0) {
            draw(cb, stack, false, snowTexture, vkLight, colorView, depthView, rain, snow);
            lastDrawCount++;
        }
        lastQuadCount = quads;
        KHRDynamicRendering.vkCmdEndRenderingKHR(cb);

        com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder.memoryBarrier(cb, stack);
        interop.externalOwnershipTransfer(cb, stack, depthImage, true);
    }

    private void draw(VkCommandBuffer cb, MemoryStack stack, boolean rain, AbstractTexture texture,
            VulkanGpuTextureView light, long colorView, long depthView, int firstQuad, int quadCount) {
        GpuTextureView textureView = texture.getTextureView();
        if (!(textureView instanceof VulkanGpuTextureView vkTexture)) {
            return;
        }
        GpuSampler sampler = texture.getSampler();
        long textureSampler = sampler instanceof VulkanGpuSampler vkSampler ? vkSampler.vkSampler() : linearSampler;
        long[] views = {vkTexture.vkImageView(), light.vkImageView(), colorView, depthView};
        long[] samplers = {textureSampler, linearSampler, linearSampler, nearestSampler};
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
        for (int i = 0; i < 4; i++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack).sampler(samplers[i])
                    .imageView(views[i]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstBinding(i).descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
        }
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, writes);
        ByteBuffer push = stack.malloc(PUSH_BYTES);
        modelView.get(0, push);
        push.putFloat(64, projX).putFloat(68, projY).putFloat(72, projZ).putFloat(76, projW);
        push.putFloat(80, rain ? 1f : 0f).putFloat(84, 0f).putFloat(88, 0f).putFloat(92, 0f);
        VK12.vkCmdPushConstants(cb, pipelineLayout, VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
        VK12.vkCmdDrawIndexed(cb, quadCount * 6, 1, firstQuad * 6, 0, 0);
    }

    private void uploadVertices(VkCommandBuffer cb, MemoryStack stack) {
        for (int offset = 0; offset < vertexBytes; offset += UPDATE_CHUNK) {
            int n = Math.min(UPDATE_CHUNK, vertexBytes - offset);
            VK12.vkCmdUpdateBuffer(cb, vertexBuffer, offset, cpuVertices.slice(offset, n));
        }
    }

    // ---- objects -------------------------------------------------------------------------------------

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

    private void ensureObjects(MemoryStack stack, int colorFormat) {
        if (setLayout == 0L) {
            createStatic(stack);
        }
        if (pipelineFormat != colorFormat) {
            if (pipeline != 0L) {
                device.graphicsQueue().waitIdle();
                VK12.vkDestroyPipeline(vk, pipeline, null);
            }
            createPipeline(stack, colorFormat);
            pipelineFormat = colorFormat;
        }
    }

    private long shaderModule(MemoryStack stack, String resource) {
        byte[] spv;
        try (InputStream in = VulkanWeatherPass.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            spv = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer code = stack.malloc(spv.length);
        code.put(spv).flip();
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                "vkCreateShaderModule");
        return out.get(0);
    }

    private long sampler(MemoryStack stack, int filter) {
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateSampler(vk, VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter).mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "vkCreateSampler");
        return out.get(0);
    }

    private void createStatic(MemoryStack stack) {
        vertexShader = shaderModule(stack, VERT);
        fragmentShader = shaderModule(stack, FRAG);
        linearSampler = sampler(stack, VK12.VK_FILTER_LINEAR);
        nearestSampler = sampler(stack, VK12.VK_FILTER_NEAREST);
        LongBuffer out = stack.mallocLong(1);
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
        for (int i = 0; i < 4; i++) {
            bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        }
        check(VK12.vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .flags(0x1 /* PUSH_DESCRIPTOR_BIT_KHR */).pBindings(bindings), null, out), "vkCreateDescriptorSetLayout");
        setLayout = out.get(0);
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES);
        check(VK12.vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, out), "vkCreatePipelineLayout");
        pipelineLayout = out.get(0);

        // Vertex buffer (device local, filled with vkCmdUpdateBuffer inside the frame's command buffer)
        long vbBytes = (long) MAX_QUADS * 4 * VERTEX_STRIDE;
        LongBuffer b = stack.mallocLong(1);
        PointerBuffer a = stack.mallocPointer(1);
        check(Vma.vmaCreateBuffer(vma, VkBufferCreateInfo.calloc(stack).sType$Default().size(vbBytes)
                        .usage(VK12.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE),
                VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO), b, a, null), "vmaCreateBuffer(vertex)");
        vertexBuffer = b.get(0);
        vertexAllocation = a.get(0);
        // Index buffer: quads as two triangles, written once (host visible)
        long ibBytes = (long) MAX_QUADS * 6 * Short.BYTES;
        VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
        check(Vma.vmaCreateBuffer(vma, VkBufferCreateInfo.calloc(stack).sType$Default().size(ibBytes)
                        .usage(VK12.VK_BUFFER_USAGE_INDEX_BUFFER_BIT).sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE),
                VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT),
                b, a, info), "vmaCreateBuffer(index)");
        indexBuffer = b.get(0);
        indexAllocation = a.get(0);
        ByteBuffer indices = MemoryUtil.memByteBuffer(info.pMappedData(), (int) ibBytes).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < MAX_QUADS; quad++) {
            int base = quad * 4;
            int o = quad * 12;
            indices.putShort(o, (short) base).putShort(o + 2, (short) (base + 1)).putShort(o + 4, (short) (base + 2))
                    .putShort(o + 6, (short) (base + 2)).putShort(o + 8, (short) (base + 3)).putShort(o + 10, (short) base);
        }
        Vma.vmaFlushAllocation(vma, indexAllocation, 0L, VK12.VK_WHOLE_SIZE);
    }

    private void createPipeline(MemoryStack stack, int colorFormat) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK12.VK_SHADER_STAGE_VERTEX_BIT).module(vertexShader).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentShader).pName(stack.UTF8("main"));
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0).stride(VERTEX_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
        attributes.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attributes.get(1).location(1).binding(0).format(VK12.VK_FORMAT_R32G32_SFLOAT).offset(12);
        attributes.get(2).location(2).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
        attributes.get(3).location(3).binding(0).format(VK12.VK_FORMAT_R16G16_SINT).offset(24);
        VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .blendEnable(true)
                .srcColorBlendFactor(VK12.VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK12.VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ZERO)
                .alphaBlendOp(VK12.VK_BLEND_OP_ADD).colorWriteMask(0xF);
        VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                .pColorAttachmentFormats(stack.ints(colorFormat));
        VkGraphicsPipelineCreateInfo.Buffer pci = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                .pNext(rendering)
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                        .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes))
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                        .topology(VK12.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                        .polygonMode(VK12.VK_POLYGON_MODE_FILL).cullMode(VK12.VK_CULL_MODE_NONE)
                        .frontFace(VK12.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                        .rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttachment))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                        .pDynamicStates(stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR)))
                .layout(pipelineLayout);
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateGraphicsPipelines(vk, 0L, pci, null, out), "vkCreateGraphicsPipelines(weather)");
        pipeline = out.get(0);
        LOGGER.info("Vulkan weather pass: pipeline created (color format {})", colorFormat);
    }

    @Override
    public void close() {
        instance = null;
        if (setLayout == 0L) {
            return;
        }
        device.graphicsQueue().waitIdle();
        if (pipeline != 0L) VK12.vkDestroyPipeline(vk, pipeline, null);
        VK12.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(vk, setLayout, null);
        VK12.vkDestroyShaderModule(vk, vertexShader, null);
        VK12.vkDestroyShaderModule(vk, fragmentShader, null);
        VK12.vkDestroySampler(vk, linearSampler, null);
        VK12.vkDestroySampler(vk, nearestSampler, null);
        Vma.vmaDestroyBuffer(vma, vertexBuffer, vertexAllocation);
        Vma.vmaDestroyBuffer(vma, indexBuffer, indexAllocation);
        setLayout = 0L;
        pipeline = 0L;
    }
}
