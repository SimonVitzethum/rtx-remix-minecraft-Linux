/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.sky.SkyGenerator;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * Vulkan port of the procedural sky ({@code AtmosphericSkyGenerator}): the same three compute passes (transmittance
 * LUT once, single scattering of the upper hemisphere, reduction and composition) on Minecraft's own VkDevice, queue
 * and VMA allocator, followed by an asynchronous copy of the final 256x129 RGBA16F image into a ring of three
 * host-visible buffers that are polled through fences. No OpenGL context is involved. Render thread only.
 *
 * <p>The row below the panorama (y = 128) carries the mean radiance of the upper hemisphere, like the GL version.
 */
public final class VulkanSkySource implements SkyGenerator {
    private static final String SHADER = "/assets/rtx-remix-renderer/shaders/vk/sky_atmosphere.spv";
    private static final int PASS_BUILD_LUT = 0;
    private static final int PASS_SINGLE_SCATTERING = 1;
    private static final int PASS_REDUCE_AND_COMPOSE = 2;
    private static final int RING = 3;
    private static final int LUT_W = 1024;
    private static final int LUT_H = 256;
    private static final int UPPER_H = HEIGHT / 2;
    private static final int READBACK_BYTES = WIDTH * (HEIGHT + 1) * 4 * Short.BYTES;
    private static final int PUSH_BYTES = 96;
    private static final int FMT_F32 = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    private static final int FMT_F16 = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long vma;

    private long shaderModule, setLayout, pipelineLayout, pipeline, sampler, pool;
    /** 0 LUT, 1 upper single, 2 upper view, 3 final sky. */
    private final long[] image = new long[4];
    private final long[] imageAllocation = new long[4];
    private final long[] imageView = new long[4];
    private final VkCommandBuffer[] cb = new VkCommandBuffer[RING];
    private final long[] fence = new long[RING];
    private final long[] readbackBuffer = new long[RING];
    private final long[] readbackAllocation = new long[RING];
    private final ByteBuffer[] readbackMapped = new ByteBuffer[RING];
    private int writeIndex, readIndex, pending;

    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES).order(ByteOrder.nativeOrder());
    /** Last parameter block actually dispatched, for skipping byte-identical regenerations. */
    private final ByteBuffer published = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
    private boolean hasPublished;

    public VulkanSkySource() {
        device = VulkanBackendSupport.device();
        vk = device.vkDevice();
        vma = device.vma();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createPipeline(stack);
            image[0] = createImage(stack, 0, LUT_W, LUT_H, FMT_F32,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT);
            image[1] = createImage(stack, 1, WIDTH, UPPER_H, FMT_F32, VK12.VK_IMAGE_USAGE_STORAGE_BIT);
            image[2] = createImage(stack, 2, WIDTH, UPPER_H, FMT_F32, VK12.VK_IMAGE_USAGE_STORAGE_BIT);
            image[3] = createImage(stack, 3, WIDTH, HEIGHT + 1, FMT_F16,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            createCommands(stack);
            for (int i = 0; i < RING; i++) {
                createReadback(stack, i);
            }
            buildLut(stack);
        }
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

    private void createPipeline(MemoryStack stack) {
        byte[] spv;
        try (InputStream in = VulkanSkySource.class.getResourceAsStream(SHADER)) {
            if (in == null) throw new IllegalStateException("missing " + SHADER);
            spv = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer code = stack.malloc(spv.length);
        code.put(spv).flip();
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                "vkCreateShaderModule");
        shaderModule = out.get(0);

        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(5, stack);
        for (int i = 0; i < 5; i++) {
            bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .descriptorType(i == 4 ? VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        check(VK12.vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .flags(0x1 /* PUSH_DESCRIPTOR_BIT_KHR */).pBindings(bindings), null, out), "vkCreateDescriptorSetLayout");
        setLayout = out.get(0);
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
        check(VK12.vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, out), "vkCreatePipelineLayout");
        pipelineLayout = out.get(0);
        VkComputePipelineCreateInfo.Buffer pci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .layout(pipelineLayout);
        pci.stage().sType$Default().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
        check(VK12.vkCreateComputePipelines(vk, 0L, pci, null, out), "vkCreateComputePipelines");
        pipeline = out.get(0);
        check(VK12.vkCreateSampler(vk, VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK12.VK_FILTER_LINEAR).minFilter(VK12.VK_FILTER_LINEAR)
                .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "vkCreateSampler");
        sampler = out.get(0);
    }

    private long createImage(MemoryStack stack, int slot, int w, int h, int format, int usage) {
        VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK12.VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK12.VK_SAMPLE_COUNT_1_BIT).tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(usage).sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
        ici.extent().set(w, h, 1);
        VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
        LongBuffer img = stack.mallocLong(1);
        PointerBuffer alloc = stack.mallocPointer(1);
        check(Vma.vmaCreateImage(vma, ici, aci, img, alloc, null), "vmaCreateImage");
        imageAllocation[slot] = alloc.get(0);
        VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(img.get(0))
                .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(format);
        vci.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        LongBuffer view = stack.mallocLong(1);
        check(VK12.vkCreateImageView(vk, vci, null, view), "vkCreateImageView");
        imageView[slot] = view.get(0);
        return img.get(0);
    }

    private void createCommands(MemoryStack stack) {
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateCommandPool(vk, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(device.graphicsQueue().queueFamilyIndex()), null, out), "vkCreateCommandPool");
        pool = out.get(0);
        PointerBuffer pb = stack.mallocPointer(RING);
        check(VK12.vkAllocateCommandBuffers(vk, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(RING), pb), "vkAllocateCommandBuffers");
        for (int i = 0; i < RING; i++) {
            cb[i] = new VkCommandBuffer(pb.get(i), vk);
            check(VK12.vkCreateFence(vk, VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "vkCreateFence");
            fence[i] = out.get(0);
        }
    }

    private void createReadback(MemoryStack stack, int i) {
        org.lwjgl.vulkan.VkBufferCreateInfo bci = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(READBACK_BYTES).usage(VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
        LongBuffer b = stack.mallocLong(1);
        PointerBuffer a = stack.mallocPointer(1);
        org.lwjgl.util.vma.VmaAllocationInfo info = org.lwjgl.util.vma.VmaAllocationInfo.calloc(stack);
        check(Vma.vmaCreateBuffer(vma, bci, aci, b, a, info), "vmaCreateBuffer");
        readbackBuffer[i] = b.get(0);
        readbackAllocation[i] = a.get(0);
        readbackMapped[i] = org.lwjgl.system.MemoryUtil.memByteBuffer(info.pMappedData(), READBACK_BYTES);
    }

    /** Transitions the images to GENERAL and builds the transmittance LUT once, waiting for it (initialisation only). */
    private void buildLut(MemoryStack stack) {
        VkCommandBuffer c = cb[0];
        VK12.vkResetFences(vk, fence[0]);
        VK12.vkResetCommandBuffer(c, 0);
        check(VK12.vkBeginCommandBuffer(c, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
        VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(4, stack);
        for (int i = 0; i < 4; i++) {
            toGeneral.get(i).sType$Default().srcAccessMask(0)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image[i]).subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        }
        VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, toGeneral);
        bindAndPush(c, stack);
        push.clear();
        for (int i = 0; i < PUSH_BYTES; i += 4) push.putInt(i, 0);
        push.putInt(80, PASS_BUILD_LUT);
        VK12.vkCmdPushConstants(c, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK12.vkCmdDispatch(c, LUT_W / 8, LUT_H / 8, 1);
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
        VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, mb, null, null);
        check(VK12.vkEndCommandBuffer(c), "vkEndCommandBuffer");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(c));
        check(VK12.vkQueueSubmit(device.graphicsQueue().vkQueue(), submit, fence[0]), "vkQueueSubmit");
        check(VK12.vkWaitForFences(vk, fence[0], true, 10_000_000_000L), "vkWaitForFences");
    }

    private void bindAndPush(VkCommandBuffer c, MemoryStack stack) {
        VK12.vkCmdBindPipeline(c, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
        for (int i = 0; i < 5; i++) {
            int slot = i == 4 ? 0 : i;
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView[slot]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            if (i == 4) info.sampler(sampler);
            writes.get(i).sType$Default().dstBinding(i).descriptorCount(1)
                    .descriptorType(i == 4 ? VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
        }
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(c, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, writes);
    }

    @Override
    public ByteBuffer generate(SkyLightState lightState, float[] upperMean, float mieScatteringAnisotropy,
            boolean publishedOutputValid) {
        if (upperMean.length < 3) {
            throw new IllegalArgumentException("Upper-mean output requires three floats");
        }
        if (!Float.isFinite(mieScatteringAnisotropy) || mieScatteringAnisotropy < 0.0f
                || mieScatteringAnisotropy >= 1.0f) {
            throw new IllegalArgumentException("Mie scattering anisotropy must be in [0, 1)");
        }
        boolean parametersChanged;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Parameters")) {
            setSkyTextureDirection(sunDirection, lightState.sunPosition());
            setSkyTextureDirection(moonDirection, lightState.moonPosition());
            normalize(sunDirection);
            normalize(moonDirection);
            parametersChanged = fillParameters(lightState, mieScatteringAnisotropy);
        }
        // Same policy as the GL generator: byte-identical parameters skip the dispatch, but a readback still in flight is drained.
        if (!parametersChanged && publishedOutputValid) {
            try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Readback")) {
                return consumeCompleted(upperMean);
            }
        }
        ByteBuffer completed;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Readback")) {
            completed = consumeCompleted(upperMean);
        }
        if (pending == RING) {
            // Every ring slot is still in flight: try again next frame (the parameters stay unpublished).
            return completed;
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Dispatch")) {
            record();
        }
        published.clear();
        published.put(push.duplicate().clear().limit(80));
        published.clear();
        hasPublished = true;
        return completed;
    }

    /** Records and submits one generation with its readback into the next ring slot. */
    private void record() {
        int slot = writeIndex;
        VkCommandBuffer c = cb[slot];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkResetFences(vk, fence[slot]);
            VK12.vkResetCommandBuffer(c, 0);
            check(VK12.vkBeginCommandBuffer(c, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            // The previous generation's final-image copy must be done before this one overwrites the images.
            VkMemoryBarrier.Buffer start = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, start, null, null);
            bindAndPush(c, stack);
            push.putInt(80, PASS_SINGLE_SCATTERING);
            VK12.vkCmdPushConstants(c, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK12.vkCmdDispatch(c, WIDTH / 8, UPPER_H / 8, 1);
            VkMemoryBarrier.Buffer between = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
            VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, between, null, null);
            push.putInt(80, PASS_REDUCE_AND_COMPOSE);
            VK12.vkCmdPushConstants(c, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK12.vkCmdDispatch(c, 1, 1, 1);
            VkMemoryBarrier.Buffer toTransfer = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, toTransfer, null, null);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(WIDTH, HEIGHT + 1, 1);
            VK12.vkCmdCopyImageToBuffer(c, image[3], VK12.VK_IMAGE_LAYOUT_GENERAL, readbackBuffer[slot], region);
            VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(c, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0, toHost, null, null);
            check(VK12.vkEndCommandBuffer(c), "vkEndCommandBuffer");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(c));
            check(VK12.vkQueueSubmit(device.graphicsQueue().vkQueue(), submit, fence[slot]), "vkQueueSubmit");
        }
        writeIndex = (writeIndex + 1) % RING;
        pending++;
    }

    private ByteBuffer consumeCompleted(float[] upperMean) {
        if (pending == 0) {
            return null;
        }
        int slot = readIndex;
        if (VK12.vkGetFenceStatus(vk, fence[slot]) != VK12.VK_SUCCESS) {
            return null;
        }
        Vma.vmaInvalidateAllocation(vma, readbackAllocation[slot], 0L, VK12.VK_WHOLE_SIZE);
        ByteBuffer mapped = readbackMapped[slot];
        mapped.clear();
        upperMean[0] = halfToFloat(mapped.getShort(OUTPUT_BYTES));
        upperMean[1] = halfToFloat(mapped.getShort(OUTPUT_BYTES + Short.BYTES));
        upperMean[2] = halfToFloat(mapped.getShort(OUTPUT_BYTES + 2 * Short.BYTES));
        mapped.position(0);
        mapped.limit(OUTPUT_BYTES);
        readIndex = (readIndex + 1) % RING;
        pending--;
        return mapped;
    }

    /** Fills the push block (without Pass) and reports whether it differs from the block the last dispatch ran on. */
    private boolean fillParameters(SkyLightState s, float mie) {
        putVec(0, sunDirection, s.sunAngularRadius());
        putVec(16, moonDirection, s.moonAngularRadius());
        putVec(32, s.sunSourceRadiance(), atmosphereScatteringScale(s.sunPosition()[1]));
        putVec(48, s.moonSourceRadiance(), 0.0f);
        push.putFloat(64, mie);
        push.putFloat(68, 0.0f);
        push.putFloat(72, 0.0f);
        push.putFloat(76, 0.0f);
        push.putInt(80, 0);
        push.putInt(84, 0);
        push.putInt(88, 0);
        push.putInt(92, 0);
        return !hasPublished || push.duplicate().clear().limit(80).mismatch(published.duplicate().clear()) >= 0;
    }

    private void putVec(int offset, float[] v, float w) {
        push.putFloat(offset, v[0]);
        push.putFloat(offset + 4, v[1]);
        push.putFloat(offset + 8, v[2]);
        push.putFloat(offset + 12, w);
    }

    private static void setSkyTextureDirection(float[] out, float[] d) {
        out[0] = -d[0];
        out[1] = d[1];
        out[2] = d[2];
    }

    private static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len > 0.0f) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }

    private static float atmosphereScatteringScale(float sunHeight) {
        float nightFactor = 1.0f - smoothstep(-0.15f, 0.05f, sunHeight);
        return 16.0f * (1.0f - nightFactor) + 70.0f * nightFactor;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float a = Math.max(0.0f, Math.min(1.0f, (v - e0) / (e1 - e0)));
        return a * a * (3.0f - 2.0f * a);
    }

    private static float halfToFloat(short value) {
        return Float.float16ToFloat(value);
    }

    @Override
    public void destroy() {
        VK12.vkDeviceWaitIdle(vk);
        for (int i = 0; i < RING; i++) {
            if (readbackBuffer[i] != 0L) {
                Vma.vmaDestroyBuffer(vma, readbackBuffer[i], readbackAllocation[i]);
                readbackBuffer[i] = 0L;
            }
            if (fence[i] != 0L) {
                VK12.vkDestroyFence(vk, fence[i], null);
                fence[i] = 0L;
            }
        }
        if (pool != 0L) {
            VK12.vkDestroyCommandPool(vk, pool, null);
            pool = 0L;
        }
        for (int i = 0; i < 4; i++) {
            if (image[i] != 0L) {
                VK12.vkDestroyImageView(vk, imageView[i], null);
                Vma.vmaDestroyImage(vma, image[i], imageAllocation[i]);
                image[i] = 0L;
            }
        }
        if (pipeline != 0L) {
            VK12.vkDestroyPipeline(vk, pipeline, null);
            VK12.vkDestroyPipelineLayout(vk, pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(vk, setLayout, null);
            VK12.vkDestroyShaderModule(vk, shaderModule, null);
            VK12.vkDestroySampler(vk, sampler, null);
            pipeline = 0L;
        }
        hasPublished = false;
        pending = 0;
    }
}
