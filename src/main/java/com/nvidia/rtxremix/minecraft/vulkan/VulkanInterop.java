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
import org.lwjgl.vulkan.VkImageViewCreateInfo;
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

    /** Debug aid: -Drtxremix.vk.debug=readback|clear, or the same word in the file rtxremix-vk-debug in the game directory. */
    private static final String DEBUG_MODE = debugMode();
    private static final boolean DEBUG_CLEAR = "clear".equals(DEBUG_MODE);

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long dxvkDoneSemaphore;
    private final long clientDoneSemaphore;
    private final long dxvkDoneHandle;
    private final long clientDoneHandle;
    private final InteropTracker colorTracker = new InteropTracker();
    private final InteropTracker depthTracker = new InteropTracker();
    private final InteropTracker clientTracker = new InteropTracker();
    private final VulkanWeatherPass weatherPass = VulkanWeatherPass.create();
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
        if (!DEBUG_MODE.isEmpty()) {
            LOGGER.warn("VulkanInterop debug mode: {}", DEBUG_MODE);
        }
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
                        .image(image);
                // LWJGL's typed pNext(struct) PREPENDS: it overwrites the struct's own pNext with the chain so far.
                // Chaining the dedicated struct by hand first would silently drop the import (a fresh, unshared
                // allocation that looks fine in isolation). Prepend one by one: allocate -> dedicated -> import.
                VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(allocationSize)
                        .memoryTypeIndex(memoryType);
                allocate.pNext(importInfo);
                allocate.pNext(dedicated);
                check(VK12.vkAllocateMemory(vk, allocate, null, out), "vkAllocateMemory(import " + name + ")");
                memory = out.get(0);
                check(VK12.vkBindImageMemory(vk, image, memory, 0L), "vkBindImageMemory(" + name + ")");
                transitionToGeneral(image);
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                        .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(VK12.vkCreateImageView(vk, viewInfo, null, out), "vkCreateImageView(" + name + ")");
                return new ImportedImage(image, memory, width, height, out.get(0));
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
        if (DEBUG_READBACK && debugFrame % 60 == 0) {
            // Needs the Remix frame finished: wait on the CPU for the done value.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkSemaphoreWaitInfo wait = org.lwjgl.vulkan.VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                        .semaphoreCount(1).pSemaphores(stack.longs(dxvkDoneSemaphore))
                        .pValues(stack.longs(info.dxvkDoneSemaphore_ValueToWait));
                VK12.vkWaitSemaphores(vk, wait, 5_000_000_000L);
            }
            debugReadback("remixImage@composite", color.image, color.width, color.height);
            debugFrame--;
        }
        VulkanCommandEncoder encoder = VulkanBackendSupport.encoder();
        long targetImage = VulkanBackendSupport.colorImage(target);
        encoder.waitSemaphore(dxvkDoneSemaphore, info.dxvkDoneSemaphore_ValueToWait, STAGE_ALL_COMMANDS);
        VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            externalOwnershipTransfer(cb, stack, color.image, false);
            // Remix's image has Vulkan row order (row 0 on top), Minecraft's Vulkan render target keeps GL's bottom-up rows.
            recordCopy(cb, stack, color.image, color.width, color.height, targetImage, target.width, target.height, true);
            if (weatherPass != null && depth != null) {
                weatherPass.record(cb, stack, target, this, color.image, depth.image);
            }
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
        long targetImage = VulkanBackendSupport.colorImage(target);
        debugReadback("mainRT@present", targetImage, target.width, target.height);
        long value = clientTracker.nextClientDone();
        VkCommandBuffer cb = nextPresentBuffer(value);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            beginOneTime(cb, stack);
            if (DEBUG_CLEAR) {
                org.lwjgl.vulkan.VkClearColorValue magenta = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
                magenta.float32(0, 1f).float32(1, 0f).float32(2, 1f).float32(3, 1f);
                org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
                range.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VK12.vkCmdClearColorImage(cb, color.image, VK12.VK_IMAGE_LAYOUT_GENERAL, magenta, range);
            } else {
                recordCopy(cb, stack, targetImage, target.width, target.height, color.image, color.width, color.height, false);
            }
            externalOwnershipTransfer(cb, stack, color.image, true);
            check(VK12.vkEndCommandBuffer(cb), "vkEndCommandBuffer(present copy)");
        }
        try (VulkanQueue.Submission submission = device.graphicsQueue().beginSubmit()) {
            submission.waitSemaphore(dxvkDoneSemaphore, info.dxvkDoneSemaphore_ValueToWait, STAGE_ALL_COMMANDS);
            submission.executeCommands(cb);
            submission.signalSemaphore(clientDoneSemaphore, value, STAGE_ALL_COMMANDS);
        }
        if ("sync".equals(DEBUG_MODE) || DEBUG_CLEAR) {
            device.graphicsQueue().waitIdle();
        }
    }

    private static final boolean DEBUG_READBACK = "readback".equals(DEBUG_MODE);

    private static String debugMode() {
        String mode = System.getProperty("rtxremix.vk.debug");
        if (mode == null) {
            try {
                java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("rtxremix-vk-debug");
                if (java.nio.file.Files.isRegularFile(file)) {
                    mode = java.nio.file.Files.readString(file).strip();
                }
            } catch (java.io.IOException | RuntimeException e) {
                // no debug file
            }
        }
        return mode == null ? "" : mode;
    }
    private int debugFrame;

    /** Debug aid (-Drtxremix.vk.debug=readback): logs mean colour and a few pixels of an image, synchronously. */
    public void debugReadback(String label, long image, int w, int h) {
        if (!DEBUG_READBACK || (debugFrame++ % 60) != 0) {
            return;
        }
        VulkanQueue queue = device.graphicsQueue();
        queue.waitIdle();
        long size = (long) w * h * 4;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo bi = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer out = stack.mallocLong(1);
            check(VK12.vkCreateBuffer(vk, bi, null, out), "debug vkCreateBuffer");
            long buffer = out.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            VK12.vkGetBufferMemoryRequirements(vk, buffer, req);
            int type = InteropTracker.pickMemoryType(req.memoryTypeBits(), memoryFlags(stack),
                    VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            check(VK12.vkAllocateMemory(vk, ai, null, out), "debug vkAllocateMemory");
            long memory = out.get(0);
            VK12.vkBindBufferMemory(vk, buffer, memory, 0L);
            VkCommandBuffer cb = presentBuffers[0];
            VK12.vkResetCommandBuffer(cb, 0);
            beginOneTime(cb, stack);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            org.lwjgl.vulkan.VkBufferImageCopy.Buffer region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(w, h, 1);
            VK12.vkCmdCopyImageToBuffer(cb, image, VK12.VK_IMAGE_LAYOUT_GENERAL, buffer, region);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            check(VK12.vkEndCommandBuffer(cb), "debug end");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb));
            check(VK12.vkQueueSubmit(queue.vkQueue(), submit, 0L), "debug submit");
            queue.waitIdle();
            PointerBuffer ptr = stack.mallocPointer(1);
            check(VK12.vkMapMemory(vk, memory, 0L, size, 0, ptr), "debug map");
            java.nio.ByteBuffer data = org.lwjgl.system.MemoryUtil.memByteBuffer(ptr.get(0), (int) size);
            long r = 0, g = 0, b = 0;
            for (int i = 0; i < size; i += 4) {
                r += data.get(i) & 255; g += data.get(i + 1) & 255; b += data.get(i + 2) & 255;
            }
            long n = (long) w * h;
            StringBuilder px = new StringBuilder();
            int[][] pts = {{w / 2, h - 12}, {w / 2, h / 2}, {w / 10, h / 10}, {w - 10, 10}};
            for (int[] pt : pts) {
                int o = (pt[1] * w + pt[0]) * 4;
                px.append(String.format(" (%d,%d)=%02x%02x%02x%02x", pt[0], pt[1], data.get(o) & 255, data.get(o + 1) & 255,
                        data.get(o + 2) & 255, data.get(o + 3) & 255));
            }
            if (true) {
                try {
                    java.awt.image.BufferedImage png = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int o = (y * w + x) * 4;
                            png.setRGB(x, y, ((data.get(o) & 255) << 16) | ((data.get(o + 1) & 255) << 8) | (data.get(o + 2) & 255));
                        }
                    }
                    javax.imageio.ImageIO.write(png, "png", new java.io.File("/tmp/s14-" + label.replace('@', '-') + ".png"));
                } catch (java.io.IOException e) {
                    LOGGER.warn("debug png failed", e);
                }
            }
            VK12.vkUnmapMemory(vk, memory);
            LOGGER.info("VK debug readback {} {}x{}: mean rgb=({}, {}, {}){}", label, w, h, r / n, g / n, b / n, px);
            VK12.vkDestroyBuffer(vk, buffer, null);
            VK12.vkFreeMemory(vk, memory, null);
        }
    }

    private int[] memoryFlags(MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(vk.getPhysicalDevice(), props);
        int[] flags = new int[props.memoryTypeCount()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = props.memoryTypes(i).propertyFlags();
        }
        return flags;
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

    /** The sampled view of the imported color image (RGBA8), or 0. */
    public long colorView() {
        return color == null ? 0L : color.view;
    }

    /** The sampled view of the imported primary-depth image (R32F), or 0. */
    public long depthView() {
        return depth == null ? 0L : depth.view;
    }

    /** The imported color VkImage, or 0. */
    public long colorImage() {
        return color == null ? 0L : color.image;
    }

    /**
     * Hands a shared image to (release) or takes it back from (acquire) the other device: a queue family ownership
     * transfer with VK_QUEUE_FAMILY_EXTERNAL, which also makes the driver flush or decompress what the copy wrote,
     * so the runtime's device sees the pixels (without it Remix kept reading its own previous output).
     */
    void externalOwnershipTransfer(VkCommandBuffer cb, MemoryStack stack, long image, boolean release) {
        int family = device.graphicsQueue().queueFamilyIndex();
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(release ? VK12.VK_ACCESS_MEMORY_WRITE_BIT : 0)
                .dstAccessMask(release ? 0 : VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                .oldLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(release ? family : VK_QUEUE_FAMILY_EXTERNAL)
                .dstQueueFamilyIndex(release ? VK_QUEUE_FAMILY_EXTERNAL : family)
                .image(image);
        barrier.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, barrier);
    }

    private static final int VK_QUEUE_FAMILY_EXTERNAL = ~1; // VK_QUEUE_FAMILY_EXTERNAL = (~0U - 1)

    private void recordCopy(VkCommandBuffer cb, MemoryStack stack, long src, int srcW, int srcH,
                            long dst, int dstW, int dstH, boolean flipY) {
        // Everything submitted before this point (render passes, uploads) has to be visible to the copy.
        VulkanCommandEncoder.memoryBarrier(cb, stack);
        if (flipY || InteropTracker.needsBlit(srcW, srcH, dstW, dstH)) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.srcOffsets(1).set(srcW, srcH, 1);
            if (flipY) {
                region.dstOffsets(0).set(0, dstH, 0);
                region.dstOffsets(1).set(dstW, 0, 1);
            } else {
                region.dstOffsets(1).set(dstW, dstH, 1);
            }
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
        VK12.vkDestroyImageView(vk, image.view, null);
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
        if (weatherPass != null) {
            weatherPass.close();
        }
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

    private record ImportedImage(long image, long memory, int width, int height, long view) {
    }
}
