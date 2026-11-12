/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.GpuWork;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Borrowed dispatch wrappers for compute work recorded on Remix's device. Owns no Vulkan device or queue. */
final class RemixGpuContext {
    final GpuWork.Context info;
    final VkDevice device;

    RemixGpuContext() {
        info = GpuWork.context();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(info.apiVersion);
            VkInstance instance = new VkInstance(info.instance, VkInstanceCreateInfo.calloc(stack)
                    .sType$Default().pApplicationInfo(application));
            VkPhysicalDevice physical = new VkPhysicalDevice(info.physicalDevice, instance);
            device = new VkDevice(info.device, physical, VkDeviceCreateInfo.calloc(stack).sType$Default(), info.apiVersion);
        }
    }

    /** Admission is authoritative in the runtime; this snapshot only guides geometric capacity choices. */
    GpuWork.Context budget() { return GpuWork.context(); }

    static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }
}
