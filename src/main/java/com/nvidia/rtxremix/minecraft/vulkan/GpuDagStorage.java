/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.nvidia.rtxremix.VoxelDag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.BooleanSupplier;

/**
 * Device-local DAG arena with one fixed 4 MiB upload buffer and no CPU node mirror. Every operation belongs to the
 * render thread. Uploads, arena growth and retirement are fenced; a page is published only after its last copy completes.
 * All node addresses survive GPU-to-GPU arena growth. The caller owns page visibility and releases pages only after
 * replacing their root references and completing the last image dispatch that could have read them.
 */
final class GpuDagStorage implements AutoCloseable {
    static final int STAGING_BYTES = 4 * 1024 * 1024;
    private static final long INITIAL_BYTES = 8 * 1024 * 1024;
    private record Buffer(long handle, long allocation, long bytes) {}

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long vma, maximumBytes;
    private final BooleanSupplier readerInFlight;
    private final GpuDagAllocator allocator;
    private final Buffer staging;
    private final ByteBuffer mapped;
    private final long commandPool, fence;
    private final VkCommandBuffer commands;
    private Buffer arena, previousArena;
    private boolean copying;
    private long peakDeviceBytes;

    GpuDagStorage(VulkanDevice device, BooleanSupplier readerInFlight) {
        this.device = device;
        this.readerInFlight = readerInFlight;
        vk = device.vkDevice();
        vma = device.vma();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            VK12.vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), properties);
            maximumBytes = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
            allocator = new GpuDagAllocator((int) Math.min(Integer.MAX_VALUE / 8, maximumBytes / 32));
            LongBufferPair memory = createBuffer(STAGING_BYTES, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, stack);
            staging = memory.buffer;
            mapped = MemoryUtil.memByteBuffer(memory.address, STAGING_BYTES).order(java.nio.ByteOrder.nativeOrder());
            var out = stack.mallocLong(1);
            check(VK12.vkCreateCommandPool(vk, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(device.graphicsQueue().queueFamilyIndex()), null, out), "create DAG upload pool");
            commandPool = out.get(0);
            var pointer = stack.mallocPointer(1);
            check(VK12.vkAllocateCommandBuffers(vk, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
                    "allocate DAG upload commands");
            commands = new VkCommandBuffer(pointer.get(0), vk);
            check(VK12.vkCreateFence(vk, VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "create DAG upload fence");
            fence = out.get(0);
        }
    }

    private record LongBufferPair(Buffer buffer, long address) {}

    private LongBufferPair createBuffer(long bytes, int usage, boolean host, MemoryStack stack) {
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes).usage(usage)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
        if (host) allocation.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
        else allocation.requiredFlags(VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                .preferredFlags(VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        var handle = stack.mallocLong(1);
        var memory = stack.mallocPointer(1);
        VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
        check(Vma.vmaCreateBuffer(vma, info, allocation, handle, memory, result), "allocate DAG buffer");
        return new LongBufferPair(new Buffer(handle.get(0), memory.get(0), bytes), result.pMappedData());
    }

    GpuDagAllocator.Page allocate(int payloadNodes) {
        requireIdle();
        return allocator.allocate(payloadNodes);
    }

    void release(GpuDagAllocator.Page page) {
        requireIdle();
        allocator.release(page);
    }

    /** Uploads a slice from a finished native workspace, omitting its reserved zero node. */
    void upload(GpuDagAllocator.Page page, long workspace, int firstWord, int words) {
        requireIdle();
        if (firstWord < 8 || words <= 0 || words > STAGING_BYTES / 4
                || (long) firstWord + words > (long) (page.nodes + 1) * 8) {
            throw new IllegalArgumentException("Invalid DAG page upload slice");
        }
        mapped.clear();
        VoxelDag.copy(workspace, firstWord, words, page.referenceOffset(), mapped);
        submit((long) page.firstNode * 32 + (long) (firstWord - 8) * 4, words * 4);
    }

    /** Uploads a small root-directory tree whose references already use arena addresses. */
    void upload(GpuDagAllocator.Page page, IntBuffer topology) {
        requireIdle();
        int words = topology.remaining();
        if (words != page.nodes * 8 || words > STAGING_BYTES / 4) {
            throw new IllegalArgumentException("Root directory exceeds one bounded upload");
        }
        mapped.clear();
        mapped.asIntBuffer().put(topology);
        submit((long) page.firstNode * 32, words * 4);
    }

    private void submit(long destination, int bytes) {
        Vma.vmaFlushAllocation(vma, staging.allocation, 0, bytes);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long required = (long) allocator.requiredNodes() * 32;
            if (arena == null || arena.bytes < required) {
                long capacity = Math.min(maximumBytes, Math.max(required, arena == null ? INITIAL_BYTES : arena.bytes * 2));
                Buffer replacement = createBuffer(capacity, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, stack).buffer;
                previousArena = arena;
                arena = replacement;
                peakDeviceBytes = Math.max(peakDeviceBytes, deviceBytes());
            }
            check(VK12.vkResetFences(vk, fence), "reset DAG upload fence");
            check(VK12.vkResetCommandBuffer(commands, 0), "reset DAG upload commands");
            check(VK12.vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin DAG upload");
            if (previousArena != null) {
                VkBufferCopy.Buffer growth = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(previousArena.bytes);
                VK12.vkCmdCopyBuffer(commands, previousArena.handle, arena.handle, growth);
                barrier(stack, VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
            } else if (destination >= 32 && allocator.livePages() == 1) {
                // Node zero is an address sentinel, but initialize it as part of the first device allocation too.
                VK12.vkCmdFillBuffer(commands, arena.handle, 0, 32, 0);
            }
            VkBufferCopy.Buffer slice = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(destination).size(bytes);
            VK12.vkCmdCopyBuffer(commands, staging.handle, arena.handle, slice);
            barrier(stack, VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            check(VK12.vkEndCommandBuffer(commands), "end DAG upload");
            check(VK12.vkQueueSubmit(device.graphicsQueue().vkQueue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(commands)), fence), "submit DAG upload");
            copying = true;
        }
    }

    private void barrier(MemoryStack stack, int sourceAccess, int destinationAccess, int sourceStage, int destinationStage) {
        VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        VK12.vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, memory, null, null);
    }

    /** Nonblocking completion check; staging memory cannot be overwritten before it returns true. */
    boolean poll() {
        if (!copying) return true;
        int status = VK12.vkGetFenceStatus(vk, fence);
        if (status == VK12.VK_NOT_READY) return false;
        check(status, "poll DAG upload fence");
        copying = false;
        destroy(previousArena);
        previousArena = null;
        return true;
    }

    private void requireIdle() {
        if (copying || readerInFlight.getAsBoolean()) throw new IllegalStateException("DAG arena still in GPU use");
    }

    long buffer() { return arena == null ? 0 : arena.handle; }
    long deviceBytes() { return (arena == null ? 0 : arena.bytes) + (previousArena == null ? 0 : previousArena.bytes); }
    long peakDeviceBytes() { return peakDeviceBytes; }
    long liveBytes() { return allocator.liveBytes(); }
    int livePages() { return allocator.livePages(); }
    boolean copying() { return copying; }

    private void destroy(Buffer buffer) {
        if (buffer != null) Vma.vmaDestroyBuffer(vma, buffer.handle, buffer.allocation);
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException(operation + ": Vulkan result " + result);
    }

    @Override public void close() {
        if (copying) check(VK12.vkWaitForFences(vk, fence, true, -1L), "wait for final DAG copy");
        if (readerInFlight.getAsBoolean()) throw new IllegalStateException("DAG storage closed before its last reader completed");
        destroy(arena);
        destroy(previousArena);
        destroy(staging);
        VK12.vkDestroyFence(vk, fence, null);
        VK12.vkDestroyCommandPool(vk, commandPool, null);
    }
}
