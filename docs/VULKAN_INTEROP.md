# Vulkan interop on Minecraft 26.3 (verified against the decompiled sources)

Sources: `com.mojang.renderpearl.backend.vulkan.*`, `com.mojang.renderpearl.frontend.*`, `net.minecraft.client.Minecraft`.

## Backend choice
- `Options.preferredGraphicsBackend` (`options.txt`: `preferredGraphicsBackend:vulkan`) or launch argument `--graphicsBackend vulkan`
  (`PreferredGraphicsApi.DEFAULT|OPENGL|VULKAN`). `VULKAN` tries `VulkanBackend` first, then `GlBackend`.
- Detect at runtime: `RenderSystem.getDevice().getDeviceInfo().backendName()` equals `"Vulkan"`.

## Reaching the Vulkan objects
- `RenderSystem.getDevice()` is a `FrontendGpuDevice`; its private field `backend` (`GpuDeviceBackend`) is the `VulkanDevice`
  (access widener entry `FrontendGpuDevice.backend`). `VulkanDevice.vkDevice()` gives the `VkDevice`, `graphicsQueue()` a
  `VulkanQueue(VkQueue, queueFamilyIndex)`; `vma()` the allocator.
- `RenderSystem.getDevice().createCommandEncoder()` is a `FrontendCommandEncoder`; the public `backend()` is the singleton `VulkanCommandEncoder`.
- Main render target: `gameRenderer.mainRenderTarget().getColorTexture()` is a `VulkanGpuTexture`, `vkImage()` the `VkImage`.
  Created with usage 15 (copy src/dst, texture binding, render attachment), format `RGBA8_UNORM` = `VK_FORMAT_R8G8B8A8_UNORM`.
  All Minecraft textures live permanently in `VK_IMAGE_LAYOUT_GENERAL` (layout 1 in every barrier and copy).

## Frame submission
- One queue (graphics, also used for present). `VulkanCommandEncoder` builds a `VulkanQueue.Submission` (stages of waits, command buffers, signals;
  `vkQueueSubmit2KHR`). Public hooks: `waitSemaphore(sem, value, stageMask)`, `execute(VkCommandBuffer)`, `signalSemaphore(...)`,
  `allocateAndBeginTransientCommandBuffer()`; each first closes the current command buffer, so order is preserved.
  Stage masks are synchronization2 values (`0x10000` = ALL_COMMANDS).
- `Minecraft.renderFrame`: acquire swapchain image, `gameRenderer.render()`, `windowSurface.blitFromTexture(...)` (main RT to swapchain),
  `encoder.submit()` (signals its own timeline semaphore, waits for submit N-2), then `VulkanGpuSurface.present()` (`vkQueuePresentKHR`).
  Therefore at `present()` the finished frame is already submitted; the interop's present-time copy uses its own `VulkanQueue.beginSubmit()` submission.
- Pools are reset by index (2 in flight), so own command buffers must come from an own pool (done in `VulkanInterop`).

## Device extensions / features
- Required set: `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain`;
  features include `timelineSemaphore`. Instance API 1.2.
- Optional (enabled when supported): calibrated timestamps, wireframe, NV/AMD checkpoints, `VK_EXT_multi_draw`, portability subset if present.
- No external memory/semaphore extensions by default. `VulkanFeatureSetsMixin` appends an optional `FeatureSet` "RTX Remix interop"
  with `VK_KHR_external_memory`, `_external_memory_fd`, `_external_semaphore`, `_external_semaphore_fd` to `VulkanFeatureSets.optionalFeatureSets()`.
  `DeviceInfo.underlyingExtensions()` lists device extensions as `name (D)`; `VulkanBackendSupport.deviceHasInteropExtensions()` checks it.

## Remix side (dxvk-remix `D3D9SwapChainEx::EnsureInteropImage`)
R8G8B8A8_UNORM, optimal, usage SRC|DST|SAMPLED, dedicated exported opaque-FD memory; depth R32_SFLOAT. Timeline semaphores dxvkDone/clientDone exported as FDs.
The FD of an image/semaphore is reported once and the number repeated afterwards (import consumes it): `InteropTracker` re-imports only on handle/size change.

## Flow implemented (`vulkan/VulkanInterop`)
1. Init: import both semaphores (`vkImportSemaphoreFdKHR`, timeline).
2. After world: `compositeWorld`: in the frame submission wait dxvkDone, copy/blit Remix image over the main RT (before HUD).
3. At `VulkanGpuSurface.present` HEAD (`VulkanGpuSurfaceMixin` -> `RemixRenderer.presentFrameVk`): own submission waits dxvkDone, copies the main RT into the
   Remix image, signals clientDone; then `remix.Present` with `interopFlipY = 0` (Vulkan row order).
Imported images get a one-time UNDEFINED to GENERAL transition (relies on driver keeping contents; no queue-family-external ownership transfer).
