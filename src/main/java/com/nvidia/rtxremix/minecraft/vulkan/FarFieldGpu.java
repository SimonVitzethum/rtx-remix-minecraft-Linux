/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Vulkan side of the far-field sky: a compute pipeline that ray marches the voxel DAG into a private RGBA16F storage
 * image, and a host readback of that image. Runs on Minecraft's own VkDevice and graphics queue (no OpenGL).
 * Buffers and the image come from Minecraft's VMA allocator; the image is private (not an interop export).
 * Render thread only.
 */
final class FarFieldGpu implements AutoCloseable {
    private static final String SHADER = "/assets/rtx-remix-renderer/shaders/vk/farfield_march.spv";
    static final int PALETTE_WORDS = 256;
    static final int PUSH_BYTES = 6 * 16;

    /** A VMA buffer that stays mapped. */
    static final class Buf {
        long buffer;
        long allocation;
        long size;
        ByteBuffer mapped;
    }

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long vma;
    private final int width;
    private final int height;
    private final float timestampPeriod;

    private long setLayout, pipelineLayout, pipeline, shaderModule;
    private long pool;
    private VkCommandBuffer cb;
    private long fence;
    private long queryPool;
    private long image, imageAllocation, imageView;
    private Buf dag;
    private Buf aux;
    private Buf readback;
    private boolean inFlight;
    private long lastFlightNanos;
    private long submitNanos;

    FarFieldGpu(int width, int height) {
        this.width = width;
        this.height = height;
        device = VulkanBackendSupport.device();
        vk = device.vkDevice();
        vma = device.vma();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            VK12.vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), props);
            timestampPeriod = props.limits().timestampPeriod();
            createPipeline(stack);
            createImage(stack);
            createCommands(stack);
        }
        readback = createBuffer((long) width * height * 8, VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        aux = createBuffer((PALETTE_WORDS + 256L * 128L * 2L) * 4L, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
    }

    int width() { return width; }
    int height() { return height; }
    boolean inFlight() { return inFlight; }
    Buf aux() { return aux; }
    long dagBytes() { return dag == null ? 0L : dag.size; }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

    private void createPipeline(MemoryStack stack) {
        byte[] spv;
        try (InputStream in = FarFieldGpu.class.getResourceAsStream(SHADER)) {
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

        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
        for (int i = 0; i < 3; i++) {
            bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .descriptorType(i == 2 ? VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
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
    }

    private void createImage(MemoryStack stack) {
        VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK12.VK_IMAGE_TYPE_2D).format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT)
                .mipLevels(1).arrayLayers(1).samples(VK12.VK_SAMPLE_COUNT_1_BIT).tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
        ici.extent().set(width, height, 1);
        VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
        LongBuffer img = stack.mallocLong(1);
        PointerBuffer alloc = stack.mallocPointer(1);
        check(Vma.vmaCreateImage(vma, ici, aci, img, alloc, null), "vmaCreateImage");
        image = img.get(0);
        imageAllocation = alloc.get(0);
        VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT);
        vci.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        LongBuffer view = stack.mallocLong(1);
        check(VK12.vkCreateImageView(vk, vci, null, view), "vkCreateImageView");
        imageView = view.get(0);
    }

    private void createCommands(MemoryStack stack) {
        LongBuffer out = stack.mallocLong(1);
        check(VK12.vkCreateCommandPool(vk, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(device.graphicsQueue().queueFamilyIndex()), null, out), "vkCreateCommandPool");
        pool = out.get(0);
        PointerBuffer pb = stack.mallocPointer(1);
        check(VK12.vkAllocateCommandBuffers(vk, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pb), "vkAllocateCommandBuffers");
        cb = new VkCommandBuffer(pb.get(0), vk);
        check(VK12.vkCreateFence(vk, VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "vkCreateFence");
        fence = out.get(0);
        check(VK12.vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK12.VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, out), "vkCreateQueryPool");
        queryPool = out.get(0);
    }

    /** Creates a mapped VMA buffer; {@code readback} picks cached host memory, otherwise write-combined upload memory. */
    Buf createBuffer(long size, int usage, boolean readback) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | (readback ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                                    : Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT));
            LongBuffer b = stack.mallocLong(1);
            PointerBuffer a = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(vma, bci, aci, b, a, info), "vmaCreateBuffer");
            Buf buf = new Buf();
            buf.buffer = b.get(0);
            buf.allocation = a.get(0);
            buf.size = size;
            buf.mapped = MemoryUtil.memByteBuffer(info.pMappedData(), (int) size);
            return buf;
        }
    }

    long vmaFlush(Buf b) {
        Vma.vmaFlushAllocation(vma, b.allocation, 0L, VK12.VK_WHOLE_SIZE);
        return 0L;
    }

    private void destroyBuffer(Buf b) {
        if (b != null) {
            Vma.vmaDestroyBuffer(vma, b.buffer, b.allocation);
        }
    }

    private Buf dagNext;
    private int upWords, upDone;

    /** Starts an incremental DAG upload into the spare buffer (the live one may still be read by an in-flight dispatch). */
    void beginUpload(int words) {
        long bytes = Math.max(32L, (long) words * 4L);
        if (dagNext == null || dagNext.size < bytes) {
            destroyBuffer(dagNext);
            dagNext = createBuffer(bytes + bytes / 8, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        }
        upWords = words;
        upDone = 0;
    }

    /** Copies up to {@code maxWords} more words of the DAG; returns true when the whole DAG is in the spare buffer. */
    boolean uploadSlice(FarDagUpload nodes, int maxWords) {
        int n = Math.min(maxWords, upWords - upDone);
        dagNext.mapped.clear();
        dagNext.mapped.position(upDone * 4);
        nodes.copy(upDone, dagNext.mapped.asIntBuffer().limit(n));
        dagNext.mapped.clear();
        upDone += n;
        return upDone >= upWords;
    }

    /** Makes the uploaded DAG the live one (only while no dispatch is in flight). */
    void commitUpload() {
        vmaFlush(dagNext);
        Buf t = dag;
        dag = dagNext;
        dagNext = t;
    }

    /** Records and submits one render + readback. {@code push} holds PUSH_BYTES. */
    void dispatch(ByteBuffer push) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkResetFences(vk, fence);
            VK12.vkResetCommandBuffer(cb, 0);
            check(VK12.vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            VK12.vkCmdResetQueryPool(cb, queryPool, 0, 2);
            // host writes of DAG/aux are visible to a submit automatically; image: UNDEFINED -> GENERAL (contents overwritten)
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(0).dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            toGeneral.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, null, toGeneral);
            VK12.vkCmdWriteTimestamp(cb, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
            VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VkDescriptorBufferInfo.Buffer dagInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(dag.buffer).offset(0).range(VK12.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer auxInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(aux.buffer).offset(0).range(VK12.VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writes.get(0).sType$Default().dstBinding(0).descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(dagInfo);
            writes.get(1).sType$Default().dstBinding(1).descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(auxInfo);
            writes.get(2).sType$Default().dstBinding(2).descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, writes);
            push.clear();
            VK12.vkCmdPushConstants(cb, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK12.vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
            VK12.vkCmdWriteTimestamp(cb, VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);

            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, mb, null, null);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(width, height, 1);
            VK12.vkCmdCopyImageToBuffer(cb, image, VK12.VK_IMAGE_LAYOUT_GENERAL, readback.buffer, region);
            VkMemoryBarrier.Buffer hb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0, hb, null, null);
            check(VK12.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb));
            check(VK12.vkQueueSubmit(device.graphicsQueue().vkQueue(), submit, fence), "vkQueueSubmit");
        }
        inFlight = true;
        submitNanos = System.nanoTime();
    }

    /**
     * @return the readback (RGBA16F, rows top first) once the submitted work finished, else null. The buffer stays valid until the
     *     next dispatch.
     */
    ByteBuffer poll() {
        if (!inFlight) return null;
        if (VK12.vkGetFenceStatus(vk, fence) != VK12.VK_SUCCESS) return null;
        inFlight = false;
        lastFlightNanos = System.nanoTime() - submitNanos;
        Vma.vmaInvalidateAllocation(vma, readback.allocation, 0L, VK12.VK_WHOLE_SIZE);
        return readback.mapped.clear();
    }

    /** Wall time from submit to the first poll that saw the fence signaled (upper bound of the GPU time). */
    double lastFlightMs() { return lastFlightNanos / 1e6; }

    /** GPU time of the last finished dispatch from the timestamps, in ms. */
    double lastGpuMs() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer ts = stack.mallocLong(2);
            if (VK12.vkGetQueryPoolResults(vk, queryPool, 0, 2, ts, 8, VK12.VK_QUERY_RESULT_64_BIT) != VK12.VK_SUCCESS) {
                return -1.0;
            }
            return (ts.get(1) - ts.get(0)) * (double) timestampPeriod / 1e6;
        }
    }

    @Override
    public void close() {
        if (inFlight) {
            VK12.vkWaitForFences(vk, fence, true, 5_000_000_000L);
        }
        VK12.vkDeviceWaitIdle(vk);
        destroyBuffer(dag);
        destroyBuffer(dagNext);
        destroyBuffer(aux);
        destroyBuffer(readback);
        VK12.vkDestroyQueryPool(vk, queryPool, null);
        VK12.vkDestroyFence(vk, fence, null);
        VK12.vkDestroyCommandPool(vk, pool, null);
        VK12.vkDestroyImageView(vk, imageView, null);
        Vma.vmaDestroyImage(vma, image, imageAllocation);
        VK12.vkDestroyPipeline(vk, pipeline, null);
        VK12.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(vk, setLayout, null);
        VK12.vkDestroyShaderModule(vk, shaderModule, null);
    }
}
