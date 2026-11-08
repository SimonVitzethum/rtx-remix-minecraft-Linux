/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.TextureFormat;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.KHRExternalSemaphoreFd;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImportMemoryFdInfoKHR;
import org.lwjgl.vulkan.VkImportSemaphoreFdInfoKHR;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Vulkan counterpart of {@code GlInterop}: imports the Remix color and primary-depth images and the two
 * timeline semaphores into Minecraft's own VkDevice (opaque file descriptors), and copies between
 * Minecraft's main render target and the imported color image on Minecraft's graphics queue.
 *
 * <p>Two copy flavours, matching the two points where the OpenGL path touches the shared image:
 * <ul>
 *   <li>{@link #compositeWorld}: recorded into the frame's own submission (through the
 *       {@link VulkanCommandEncoder}) right after the world, waits for Remix's done value, copies the
 *       Remix output over the main render target before the HUD is drawn.</li>
 *   <li>{@link #copyFrameToRemix}: its own queue submission at present time (the frame submission is
 *       already gone then). Waits for Remix's done value, copies the main render target into the Remix
 *       image, signals clientDone.</li>
 * </ul>
 * All images stay in {@code VK_IMAGE_LAYOUT_GENERAL}, as Minecraft's textures and Remix's export do.
 * Render thread only.
 */
public final class VulkanInterop implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanInterop.class);

    private static final int FORMAT_RGBA8_UNORM = VK12.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int FORMAT_R32_SFLOAT = VK12.VK_FORMAT_R32_SFLOAT;
    private static final int IMAGE_USAGE = VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final long STAGE_ALL_COMMANDS = 0x10000L; // VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
    /** Command buffers of the present-time submission; one is reused every third frame. */
    private static final int PRESENT_RING = 3;

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long dxvkDoneSemaphore;
    private final long clientDoneSemaphore;
    private final long dxvkDoneHandle;
    private final long clientDoneHandle;
    private final InteropTracker colorTracker = new InteropTracker();
    private final InteropTracker depthTracker = new InteropTracker();
    private final InteropTracker clientTracker = new InteropTracker();
    private ImportedImage color;
    private ImportedImage depth;

    private long presentPool;
    private final VkCommandBuffer[] presentBuffers = new VkCommandBuffer[PRESENT_RING];
    private int presentIndex;
    private long presentRingFirstValue;

    public VulkanInterop(InteropInfo info) {
        if (info.dxvkDoneSemaphore_Handle == 0 || info.clientDoneSemaphore_Handle == 0) {
            throw new IllegalArgumentException("Remix interop semaphore handles must be nonzero");
        }
        if (!VulkanBackendSupport.deviceHasInteropExtensions()) {
            throw new IllegalStateException(
                    "Minecraft's Vulkan device was created without " + VulkanBackendSupport.INTEROP_EXTENSIONS
                            + " (the VulkanFeatureSets mixin did not apply or the GPU lacks them)");
        }
        device = VulkanBackendSupport.device();
        vk = device.vkDevice();
        dxvkDoneHandle = info.dxvkDoneSemaphore_Handle;
        clientDoneHandle = info.clientDoneSemaphore_Handle;
        dxvkDoneSemaphore = importTimelineSemaphore(dxvkDoneHandle, "dxvkDone");
        clientDoneSemaphore = importTimelineSemaphore(clientDoneHandle, "clientDone");
        createPresentRing();
        LOGGER.info("VulkanInterop: imported semaphores dxvkDone=0x{} clientDone=0x{}",
                Long.toHexString(dxvkDoneSemaphore), Long.toHexString(clientDoneSemaphore));
    }

    // ---- import -------------------------------------------------------------------------------------------

    private long importTimelineSemaphore(long fd, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0L);
            VkSemaphoreCreateInfo create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer out = stack.mallocLong(1);
            check(VK12.vkCreateSemaphore(vk, create, null, out), "vkCreateSemaphore(" + name + ")");
            long semaphore = out.get(0);
            VkImportSemaphoreFdInfoKHR imp = VkImportSemaphoreFdInfoKHR.calloc(stack).sType$Default()
                    .semaphore(semaphore)
                    .flags(0)
                    .handleType(VK12.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT)
                    .fd((int) fd);
            int result = KHRExternalSemaphoreFd.vkImportSemaphoreFdKHR(vk, imp);
            if (result != VK12.VK_SUCCESS) {
                VK12.vkDestroySemaphore(vk, semaphore, null);
                check(result, "vkImportSemaphoreFdKHR(" + name + ")");
            }
            return semaphore;
        }
    }

    /**
     * Imports the color and depth images again when Remix reports a new one (resize). Must be called after
     * Submit(), which allocates the image. Returns whether the color image was replaced.
     */
    public boolean recreateImagesIf(InteropInfo info) {
        validateSemaphoreHandles(info);
        boolean colorChanged = updateImage(info.imageMemory_Handle, info.allocationSize, info.width, info.height,
                vulkanFormat(info.interopImageFormat), colorTracker, true);
        updateImage(info.depthImageMemory_Handle, info.depthAllocationSize, info.depthWidth, info.depthHeight,
                vulkanFormat(info.interopDepthImageFormat), depthTracker, false);
        return colorChanged;
    }

    private boolean updateImage(long handle, long allocationSize, int width, int height, int format,
                                InteropTracker tracker, boolean isColor) {
        InteropTracker.ImageKey key = new InteropTracker.ImageKey(handle, width, height, format);
        if (tracker.needsRelease(key)) {
            destroyImage(isColor);
            tracker.released();
            return isColor;
        }
        if (!tracker.needsImport(key)) {
            return false;
        }
        if (allocationSize <= 0) {
            throw new IllegalArgumentException("Invalid Remix " + (isColor ? "color" : "depth") + " interop image");
        }
        destroyImage(isColor);
        ImportedImage image = importImage(handle, allocationSize, width, height, format, isColor ? "color" : "depth");
        if (isColor) {
            color = image;
        } else {
            depth = image;
        }
        tracker.imported(key);
        LOGGER.info("VulkanInterop: imported {} image {}x{} format={}", isColor ? "color" : "depth", width, height, format);
        return true;
    }

    private ImportedImage importImage(long fd, long allocationSize, int width, int height, int format, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
                    .pNext(external)
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(IMAGE_USAGE)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(width, height, 1);
            LongBuffer out = stack.mallocLong(1);
            check(VK12.vkCreateImage(vk, create, null, out), "vkCreateImage(" + name + ")");
            long image = out.get(0);
            long memory = 0;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                VK12.vkGetImageMemoryRequirements(vk, image, requirements);
                int memoryType = pickDeviceLocalType(requirements.memoryTypeBits(), stack);
                if (memoryType < 0) {
                    throw new IllegalStateException("No device-local memory type fits the imported " + name + " image");
                }
                if (requirements.size() > allocationSize) {
                    throw new IllegalStateException("Imported " + name + " image needs " + requirements.size()
                            + " bytes but Remix exported " + allocationSize);
                }
                VkImportMemoryFdInfoKHR importInfo = VkImportMemoryFdInfoKHR.calloc(stack).sType$Default()
                        .handleType(VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
                        .fd((int) fd);
                VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default()
                        .pNext(importInfo.address())
                        .image(image);
                VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .pNext(dedicated)
                        .allocationSize(allocationSize)
                        .memoryTypeIndex(memoryType);
                check(VK12.vkAllocateMemory(vk, allocate, null, out), "vkAllocateMemory(import " + name + ")");
                memory = out.get(0);
                check(VK12.vkBindImageMemory(vk, image, memory, 0L), "vkBindImageMemory(" + name + ")");
                transitionToGeneral(image);
                return new ImportedImage(image, memory, width, height);
            } catch (RuntimeException e) {
                VK12.vkDestroyImage(vk, image, null);
                if (memory != 0) {
                    VK12.vkFreeMemory(vk, memory, null);
                }
                throw e;
            }
        }
    }

    private int pickDeviceLocalType(int typeBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(vk.getPhysicalDevice(), props);
        int[] flags = new int[props.memoryTypeCount()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = props.memoryTypes(i).propertyFlags();
        }
        return InteropTracker.pickMemoryType(typeBits, flags, VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    /**
     * One-time UNDEFINED to GENERAL transition of a fresh import on its own submission. The exporter wrote the image
     * before the import, and on the drivers this was written for the transition keeps the contents; the proper
     * hand-over (queue family external ownership transfer on both sides) would need the runtime's cooperation.
     */
    private void transitionToGeneral(long image) {
        VulkanQueue queue = device.graphicsQueue();
        // The ring buffer used below may still be in flight from a present copy.
        queue.waitIdle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = presentBuffers[0];
            VK12.vkResetCommandBuffer(cb, 0);
            beginOneTime(cb, stack);
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(0)
                    .dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            barrier.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
            check(VK12.vkEndCommandBuffer(cb), "vkEndCommandBuffer(transition)");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb));
            check(VK12.vkQueueSubmit(queue.vkQueue(), submit, 0L), "vkQueueSubmit(transition)");
            queue.waitIdle();
        }
    }

    // ---- per frame ----------------------------------------------------------------------------------------

    /**
     * Records "wait for Remix, then copy the Remix output over the main render target" into the frame's own
     * submission and returns. Equivalent of {@code waitForVk} plus {@code TextureOverlay}. Must run outside a render pass.
     */
    public void compositeWorld(InteropInfo info, RenderTarget target) {
        recreateImagesIf(info);
        if (color == null) {
            return;
        }
        colorTracker.registerWait(info.dxvkDoneSemaphore_ValueToWait);
        VulkanCommandEncoder encoder = VulkanBackendSupport.encoder();
        long targetImage = VulkanBackendSupport.colorImage(target);
        encoder.waitSemaphore(dxvkDoneSemaphore, info.dxvkDoneSemaphore_ValueToWait, STAGE_ALL_COMMANDS);
        VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            recordCopy(cb, stack, color.image, color.width, color.height, targetImage, target.width, target.height);
        }
        check(VK12.vkEndCommandBuffer(cb), "vkEndCommandBuffer(composite)");
        encoder.execute(cb);
    }

    /**
     * Copies the main render target into the Remix image on a submission of its own and signals clientDone.
     * Call after the frame's submission (the render target holds the finished frame, HUD included) and before
     * Remix's present, which waits for {@link #clientDoneValue()}.
     */
    public void copyFrameToRemix(InteropInfo info, RenderTarget target) {
        recreateImagesIf(info);
        if (color == null) {
            // Nothing to copy, but Remix still waits for clientDone, so keep the counter moving.
            signalOnly();
            return;
        }
        long value = clientTracker.nextClientDone();
        VkCommandBuffer cb = nextPresentBuffer(value);
        long targetImage = VulkanBackendSupport.colorImage(target);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            beginOneTime(cb, stack);
            recordCopy(cb, stack, targetImage, target.width, target.height, color.image, color.width, color.height);
            check(VK12.vkEndCommandBuffer(cb), "vkEndCommandBuffer(present copy)");
        }
        try (VulkanQueue.Submission submission = device.graphicsQueue().beginSubmit()) {
            submission.waitSemaphore(dxvkDoneSemaphore, info.dxvkDoneSemaphore_ValueToWait, STAGE_ALL_COMMANDS);
            submission.executeCommands(cb);
            submission.signalSemaphore(clientDoneSemaphore, value, STAGE_ALL_COMMANDS);
        }
    }

    private void signalOnly() {
        long value = clientTracker.nextClientDone();
        try (VulkanQueue.Submission submission = device.graphicsQueue().beginSubmit()) {
            submission.signalSemaphore(clientDoneSemaphore, value, STAGE_ALL_COMMANDS);
        }
    }

    /** The clientDone value Remix's present has to wait for. */
    public long clientDoneValue() {
        return clientTracker.clientDoneValue();
    }

    /** The imported primary-depth VkImage, or 0 (not consumed yet: the Remix weather pass is OpenGL only). */
    public long depthImage() {
        return depth == null ? 0L : depth.image;
    }

    /** The imported color VkImage, or 0. */
    public long colorImage() {
        return color == null ? 0L : color.image;
    }

    private void recordCopy(VkCommandBuffer cb, MemoryStack stack, long src, int srcW, int srcH,
                            long dst, int dstW, int dstH) {
        // Everything submitted before this point (render passes, uploads) has to be visible to the copy.
        VulkanCommandEncoder.memoryBarrier(cb, stack);
        if (InteropTracker.needsBlit(srcW, srcH, dstW, dstH)) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.srcOffsets(1).set(srcW, srcH, 1);
            region.dstOffsets(1).set(dstW, dstH, 1);
            VK12.vkCmdBlitImage(cb, src, VK12.VK_IMAGE_LAYOUT_GENERAL, dst, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    region, VK12.VK_FILTER_NEAREST);
        } else {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.extent().set(srcW, srcH, 1);
            VK12.vkCmdCopyImage(cb, src, VK12.VK_IMAGE_LAYOUT_GENERAL, dst, VK12.VK_IMAGE_LAYOUT_GENERAL, region);
        }
        // And the copy has to be done before anything that follows reads or overwrites either image.
        VulkanCommandEncoder.memoryBarrier(cb, stack);
    }

    // ---- present ring -------------------------------------------------------------------------------------

    private void createPresentRing() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(device.graphicsQueue().queueFamilyIndex());
            LongBuffer pool = stack.mallocLong(1);
            check(VK12.vkCreateCommandPool(vk, poolInfo, null, pool), "vkCreateCommandPool");
            presentPool = pool.get(0);
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(presentPool)
                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(PRESENT_RING);
            PointerBuffer buffers = stack.mallocPointer(PRESENT_RING);
            check(VK12.vkAllocateCommandBuffers(vk, allocate, buffers), "vkAllocateCommandBuffers");
            for (int i = 0; i < PRESENT_RING; i++) {
                presentBuffers[i] = new VkCommandBuffer(buffers.get(i), vk);
            }
        }
    }

    /**
     * Returns the next ring buffer, once the submission that last used it is finished: Remix's clientDone counter
     * (which this class signals at the end of each present submission) tells.
     */
    private VkCommandBuffer nextPresentBuffer(long value) {
        long previousUse = value - PRESENT_RING;
        if (previousUse > 0) {
            waitClientDone(previousUse);
        }
        VkCommandBuffer cb = presentBuffers[presentIndex];
        presentIndex = (presentIndex + 1) % PRESENT_RING;
        VK12.vkResetCommandBuffer(cb, 0);
        return cb;
    }

    private void waitClientDone(long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkSemaphoreWaitInfo wait = org.lwjgl.vulkan.VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(clientDoneSemaphore))
                    .pValues(stack.longs(value));
            check(VK12.vkWaitSemaphores(vk, wait, 5_000_000_000L), "vkWaitSemaphores(clientDone " + value + ")");
        }
    }

    private static void beginOneTime(VkCommandBuffer cb, MemoryStack stack) {
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(VK12.vkBeginCommandBuffer(cb, begin), "vkBeginCommandBuffer");
    }

    // ---- teardown -----------------------------------------------------------------------------------------

    private void validateSemaphoreHandles(InteropInfo info) {
        if (info.dxvkDoneSemaphore_Handle != dxvkDoneHandle || info.clientDoneSemaphore_Handle != clientDoneHandle) {
            throw new IllegalStateException("Remix replaced an interop semaphore during an active session");
        }
    }

    private void destroyImage(boolean isColor) {
        ImportedImage image = isColor ? color : depth;
        if (image == null) {
            return;
        }
        device.graphicsQueue().waitIdle();
        VK12.vkDestroyImage(vk, image.image, null);
        VK12.vkFreeMemory(vk, image.memory, null);
        if (isColor) {
            color = null;
        } else {
            depth = null;
        }
    }

    @Override
    public void close() {
        device.graphicsQueue().waitIdle();
        destroyImage(true);
        destroyImage(false);
        colorTracker.released();
        depthTracker.released();
        VK12.vkDestroySemaphore(vk, dxvkDoneSemaphore, null);
        VK12.vkDestroySemaphore(vk, clientDoneSemaphore, null);
        VK12.vkDestroyCommandPool(vk, presentPool, null);
        LOGGER.info("VulkanInterop: destroyed");
    }

    private static int vulkanFormat(int remixFormat) {
        return switch (remixFormat) {
            case TextureFormat.RGBA8_UNORM -> FORMAT_RGBA8_UNORM;
            case TextureFormat.R32_SFLOAT -> FORMAT_R32_SFLOAT;
            default -> throw new IllegalArgumentException("Unsupported Remix interop texture format: " + remixFormat);
        };
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " in " + operation);
        }
    }

    private record ImportedImage(long image, long memory, int width, int height) {
    }
}
