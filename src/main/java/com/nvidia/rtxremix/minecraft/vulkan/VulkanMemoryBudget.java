/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sun.management.OperatingSystemMXBean;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.EXTMemoryBudget;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Properties;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Process-wide Vulkan heap observations shared by all far-terrain resource owners on Minecraft's device. */
final class VulkanMemoryBudget {
    private final VulkanDevice device;
    private final MemoryBudget ledger;
    private final boolean driverBudget, unified;
    private final int localHeap;
    private final long localHeapBytes, maximumBufferBytes;
    private final boolean[] localTypes;
    private final Path scope;
    private final OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    VulkanMemoryBudget(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var physical = device.vkDevice().getPhysicalDevice();
            var memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(physical, memory);
            int selected = -1;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                if ((memory.memoryHeaps(i).flags() & VK12.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0
                        && (selected < 0 || memory.memoryHeaps(i).size() > memory.memoryHeaps(selected).size())) selected = i;
            }
            if (selected < 0) throw new IllegalStateException("Vulkan has no device-local memory heap");
            localHeap = selected;
            localHeapBytes = memory.memoryHeaps(selected).size();
            localTypes = new boolean[memory.memoryTypeCount()];
            for (int i = 0; i < localTypes.length; i++) localTypes[i] = memory.memoryTypes(i).heapIndex() == selected;
            var limits11 = VkPhysicalDeviceVulkan11Properties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(limits11);
            VK12.vkGetPhysicalDeviceProperties2(physical, properties);
            VkPhysicalDeviceProperties base = properties.properties();
            unified = base.deviceType() == VK12.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
            long allocationLimit = limits11.maxMemoryAllocationSize();
            if (allocationLimit < 0) allocationLimit = Long.MAX_VALUE;
            maximumBufferBytes = Math.min(allocationLimit, Integer.toUnsignedLong(base.limits().maxStorageBufferRange()));
            var count = stack.ints(0);
            check(VK12.vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null));
            var extensions = VkExtensionProperties.malloc(count.get(0), stack);
            check(VK12.vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, extensions));
            boolean supported = false;
            for (int i = 0; i < extensions.capacity(); i++) {
                supported |= extensions.get(i).extensionNameString().equals(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
            }
            driverBudget = supported;
        }
        scope = scopePath();
        ledger = new MemoryBudget(this::measure);
    }

    /** The same ledger is used for arena pages, working buffers, staging and their in-flight replacements. */
    MemoryBudget.Reservation reserve(long bytes, int memoryType) {
        if (memoryType < 0 || memoryType >= localTypes.length) throw new IllegalArgumentException("Unknown Vulkan memory type");
        boolean local = localTypes[memoryType];
        return ledger.reserve(local ? bytes : 0, !local || unified ? bytes : 0);
    }

    MemoryBudget.State state() { return ledger.state(); }
    long maximumBufferBytes() { return maximumBufferBytes; }

    private MemoryBudget.Snapshot measure() {
        long budget, usage;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (driverBudget) {
                var budgets = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
                var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budgets);
                VK12.vkGetPhysicalDeviceMemoryProperties2(device.vkDevice().getPhysicalDevice(), properties);
                budget = budgets.heapBudget(localHeap);
                usage = budgets.heapUsage(localHeap);
            } else {
                var budgets = VmaBudget.calloc(VK12.VK_MAX_MEMORY_HEAPS, stack);
                Vma.vmaGetHeapBudgets(device.vma(), budgets);
                // VMA without EXT cannot see the other logical device's allocations. Leave half the heap for them.
                budget = Math.min(localHeapBytes / 2, budgets.get(localHeap).budget());
                usage = budgets.get(localHeap).usage();
            }
        }
        long hostUsage = 0;
        long available = operatingSystem.getFreeMemorySize();
        long systemReserve = operatingSystem.getTotalMemorySize() / 4;
        if (scope != null) {
            hostUsage = number(scope.resolve("memory.current"));
            available = memAvailable();
            for (Path ancestor = scope; ancestor != null && ancestor.startsWith("/sys/fs/cgroup"); ancestor = ancestor.getParent()) {
                Path limit = ancestor.resolve("memory.max");
                if (Files.isRegularFile(limit)) {
                    String value = read(limit).trim();
                    if (!value.equals("max")) available = Math.min(available,
                            Math.max(0, Long.parseLong(value) - number(ancestor.resolve("memory.current"))));
                }
            }
        }
        long physicalAvailable = Math.max(0, memAvailableOr(available) - systemReserve);
        long headroom = Math.min(available, physicalAvailable);
        long hostBudget = headroom > Long.MAX_VALUE - hostUsage ? Long.MAX_VALUE : hostUsage + headroom;
        Runtime runtime = Runtime.getRuntime();
        long javaGrowth = Math.max(0, runtime.maxMemory() - runtime.totalMemory());
        return new MemoryBudget.Snapshot(budget, usage, hostBudget, hostUsage, budget / 8, javaGrowth,
                (driverBudget ? "VK_EXT_memory_budget(process-wide)" : "VMA-estimate(half-heap, other-device usage unavailable)")
                        + (scope != null ? "+cgroup.max+MemAvailable+JVM" : "+OS-free+JVM"));
    }

    private static void check(int result) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException("Vulkan memory-budget query failed: " + result);
    }

    private static Path scopePath() {
        Path proc = Path.of("/proc/self/cgroup");
        if (!Files.isRegularFile(proc)) return null;
        for (String line : read(proc).split("\\R")) if (line.startsWith("0::")) {
            Path path = Path.of("/sys/fs/cgroup").resolve(line.substring(3).replaceFirst("^/", "")).normalize();
            if (!path.startsWith("/sys/fs/cgroup")) throw new IllegalStateException("Invalid cgroup path");
            return path;
        }
        throw new IllegalStateException("No unified process cgroup for memory accounting");
    }

    private static long memAvailableOr(long fallback) {
        return Files.isRegularFile(Path.of("/proc/meminfo")) ? memAvailable() : fallback;
    }

    private static long memAvailable() {
        for (String line : read(Path.of("/proc/meminfo")).split("\\R")) if (line.startsWith("MemAvailable:")) {
            return Math.multiplyExact(Long.parseLong(line.substring(line.indexOf(':') + 1).trim().split("\\s+")[0]), 1024);
        }
        throw new IllegalStateException("MemAvailable missing from host memory accounting");
    }

    private static long number(Path path) { return Long.parseLong(read(path).trim()); }
    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException failure) { throw new IllegalStateException("Cannot measure memory budget from " + path, failure); }
    }
}
