# Native Vulkan Rendering and Interop on Minecraft 26.3

The Linux port has an end-to-end Vulkan render path when Minecraft's Vulkan
backend is active: Minecraft targets and HUD, Remix path tracing and final
presentation, sky generation, weather composition and FarField rendering.
No hidden OpenGL rendering context is created for sky or weather. OpenGL
remains an alternative backend, selected independently.

DLSS upscaling with Ray Reconstruction and Frame Generation were active in
the documented Linux benchmarks: 960×540 internal rendering and 1920×1080
output. See [Linux Performance](LINUX_PERFORMANCE.md) for measured settings,
hardware and coverage limits. GPU DAG construction and GPU-generated meshes
are separate work in progress, not implied by Vulkan rendering support.

Source references: `vulkan/VulkanInterop.java`, `VulkanSkySource.java`,
`VulkanWeatherPass.java`, `sky/SkyGenerator.java`, `RemixRenderer.java`,
`InteropTracker.java`, and Minecraft's `com.mojang.renderpearl.backend.vulkan.*`,
`com.mojang.renderpearl.frontend.*` and `net.minecraft.client.Minecraft`.

## Backend choice
- `Options.preferredGraphicsBackend` (`options.txt`: `preferredGraphicsBackend:vulkan`) or launch argument `--graphicsBackend vulkan`
  (`PreferredGraphicsApi.DEFAULT|OPENGL|VULKAN`). `VULKAN` tries `VulkanBackend` first, then `GlBackend`.
- Detect at runtime: `RenderSystem.getDevice().getDeviceInfo().backendName()` equals `"Vulkan"`.
- Verify the actual backend in the log. Minecraft may choose OpenGL when
  Vulkan initialization is unsupported; requesting Vulkan alone does not prove
  that a session used it.

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
2. After world: `compositeWorld` waits for dxvkDone, acquires exported color
   and optional depth from `VK_QUEUE_FAMILY_EXTERNAL`, then copies/blits the
   Remix color over the Minecraft target before the HUD. `VulkanWeatherPass`
   composes rain/snow using the imported primary depth in that ownership interval.
3. At `VulkanGpuSurface.present` HEAD (`VulkanGpuSurfaceMixin` -> `RemixRenderer.presentFrameVk`): own submission waits dxvkDone, copies the main RT into the
   Remix image, signals clientDone; then `remix.Present` with `interopFlipY = 1`.
The presentation copy releases both exported images to
`VK_QUEUE_FAMILY_EXTERNAL` before signaling clientDone. Remix waits and
reacquires them. `InteropTracker` ensures one acquire per exported submission
value; menu frames also acquire and release when they skip world composition.
Color and depth stay in `GENERAL` at the device boundary. CPU submission
completion does not replace the GPU semaphore and ownership operations.

## Orientation and Vulkan Resources
- Minecraft's Vulkan main render target is stored bottom-up like GL's (its swapchain blit flips it). Both copies therefore flip like the GL path:
  composite = flipping blit (Remix image -> main RT), present copy = plain copy, `interopFlipY = 1`.
- LWJGL's typed `pNext(struct)` PREPENDS and overwrites the struct's own pNext. Chaining dedicated -> import by hand and then calling `allocate.pNext(dedicated)`
  silently dropped the FD import: a fresh unshared allocation that works in isolation (symptom: nothing written by either side is ever seen by the other).
- `SkyGenerator.create()` selects `VulkanSkySource` when Vulkan is active.
  Its compute pipeline generates the atmospheric sky on the Vulkan device.
- `VulkanWeatherPass` implements rain/snow composition with Vulkan resources
  and imported depth. These features do not use a side OpenGL window/context.
- Shared images get queue family ownership transfers to/from `VK_QUEUE_FAMILY_EXTERNAL`.
- Debug aids: file `rtxremix-vk-debug` in the game dir (or `-Drtxremix.vk.debug`): `readback` (PNG of main RT / Remix image to /tmp), `clear`, `sync`.
