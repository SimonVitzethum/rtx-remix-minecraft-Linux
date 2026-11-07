# dxvk-remix: Native Linux build (Port in progress)

Status: **early / iterative port**. Goal: a Linux shared object exposing the
Remix C API (`remixapi_*`) so the Minecraft RTX Remix mod can run natively.

## Source structure (analysis)

- Renderer core: `src/dxvk/` + `src/dxvk/rtx_render/` (~107 .cpp), Vulkan-based.
- Remix C API: `src/dxvk/rtx_render/rtx_remix_api.cpp` — independent of D3D9.
- D3D9 interposer (Windows-only): `src/d3d9/` (34 .cpp).
- Windows headers in the core: only ~10 files, mostly shallow (logging via
  `OutputDebugString`/`RtlCaptureStackBackTrace`, `RtlGetVersion`, ImGui
  Win32 backend). Feasible to port.

## Meson/build portability (progress)

Fixed:
- Packman dep fetch cross-platform (POSIX uses `packman/packman pull`).
- `submodules/nrc/Include` case-sensitivity.
- `windres` guarded to Windows hosts.
- `vulkan` resolved via pkg-config on POSIX (1.4.363).
- Windows-only libraries (`d3d9`, `d3d11`, `dxgi`, `ws2_32`, `delayimp`,
  `d3dcompiler_47`, `shlwapi`, `dbghelp`) guarded to Windows hosts; `ws2_32`/
  `delayimp` removed from the dxvk core deps on Linux.

Blockers not yet solved: the **external dependency binaries** (below).

## The real gating factor: external binary dependencies

Everything below is fetched via Packman as **Windows-only binaries** (the
`external/` dir is full of symlinks to win/arm64 payloads). There are **no
published Linux variants**:

| Dependency | Package | Linux route (port/equivalent) |
|---|---|---|
| OpenUSD | `usd_release` (windows-x86_64) | build from source (Pixar OpenUSD, cross-platform) |
| RTXIO | `rtxio` | GDeflate is open source; Vulkan ext `VK_NV_memory_decompression` + `VK_NV_copy_memory_indirect` are supported on Linux+Windows; CPU fallback `libdeflate`/nvCOMP (Linux) — map/port the rtxio API onto these |
| NRC | `nrc_arm64` | submodule source exists (`submodules/nrc`) → build |
| NRD | `nrd` / `nrd_arm64` | **Open source (NVIDIA-RTX/NRD), builds natively on Linux** (SPIR-V embedded, .sh build scripts) |
| DLSS-FG | `ngx_sdk_dlfg` | Linux via Streamline (`libnvidia-ngx-dlssg*`) |
| DLSS-RR | `ngx_sdk_dlnr` | Linux via Streamline (`libnvidia-ngx-dlssnr*`) |
| Reflex | `reflex_arm64` | Linux build exists (`NvLowLatencyVk`) |
| Aftermath | `aftermath` | **NVIDIA ships Linux `.so` in the Nsight Aftermath SDK** (Vulkan on Linux, driver R455+) |
| Sentry-native | `sentry-native` | build from source (cross-platform) |
| Slang | `slang` (win64) | build from source (NVIDIA Slang, supports Linux) |
| glslang/SPIRV-Tools | `glslangvalidator`, `spirv_tools` | build from source (cross-platform) |
| XeSS | submodule `submodules/xess` | source exists; verify Linux support |

**Conclusion:** every external dependency has a Linux path — either official
Linux binaries (Aftermath, DLSS/NGX via Streamline, Reflex) or buildable from
source (NRD, OpenUSD, Slang, glslang/SPIRV-Tools, Sentry, NRC, XeSS, and the
RTXIO/GDeflate Vulkan-layer approach). No hard, unbridgeable dependency remains;
the work is building/maintaining these Linux variants.

## Next steps

1. Build the buildable Linux deps first: OpenUSD, Slang, glslang/SPIRV-Tools,
   Sentry-native, NRC, XeSS, Reflex.
2. Resolve/obtain Linux variants for RTXIO, NRD, Aftermath (NVIDIA/community).
3. Finish the meson Linux target (renderer/SDK core, excluding `src/d3d9/`
   and `imgui_impl_win32.cpp`).
4. Replace the ~10 shallow Windows-header usages in the core.
5. Wire the resulting `.so` into the mod's `remix-sdk-linux64` payload.

## Reproducing

```
git submodule update --init --recursive
meson setup _CompLinuxRelease
meson compile -C _CompLinuxRelease
```

(Currently stops at the Windows-only dependency resolution for `rtxio`.)

## RTXIO port (Linux shim mapping)

RTXIO is a closed Windows binary. dxvk-remix uses a small, well-defined API
surface (from `external/rtxio/include/rtxio/*.h`):

Used functions: `rtxioCreate`, `rtxioOpenFile`, `rtxioEnqueueUpdateRequests`,
`rtxioEnqueueWait`, `rtxioEnqueueSignal`, `rtxioFlush`, `rtxioRelease`,
`rtxioGetCounter`, `rtxioGetTimelineValue`, `rtxioVulkanGetInstanceExtensions`,
`rtxioVulkanGetDeviceExtensions`, `rtxioVulkanGetPhysicalDeviceFeatures`,
`rtxioVulkanGetSubresourceIndex`.

What RTXIO does internally, and the Linux equivalent:

| RTXIO function/feature | Linux equivalent |
|---|---|
| GDeflate GPU decompression | `VK_NV_memory_decompression` (device extension, supported on Linux NVIDIA driver) |
| Copy decompressed data into buffer/image subresources | `VK_NV_copy_memory_indirect` (device extension) or `vkCmdCopyBufferToImage` |
| CPU fallback decompression (`RTXIO_FORCE_CPU`) | `libdeflate` (open source) / `nvCOMP` (Linux) |
| Packed resource file reads (`rtxioOpenFile`) | POSIX `open`/`read` |
| Async scheduling + queue sharing (`RTXIO_VULKAN_SHARED_QUEUE`, `RTXIOVkDevice`) | Vulkan device queue + **timeline semaphores** (`rtxioGetTimelineValue`, EnqueueWait/Signal) |
| Subresource indexing (`rtxioVulkanGetSubresourceIndex`) | trivial computation (arrayLayer*mipLevels + mipLevel) |
| Instance/device extensions + features queries | return `VK_NV_memory_decompression`/`VK_NV_copy_memory_indirect`; query support via `vkEnumerate*`/`vkGetPhysicalDeviceFeatures2` |

The `rtxio.h` / `rtxioVulkan.h` headers are already compiler-clean (GCC
`visibility("default")` attributes), so a **shim library** exposing the same
13 functions can be built for Linux on top of the primitives above. ABI
structs (`RTXIOVkDevice`, `RTXIOVkImage`, destination types) are matched
directly from the vendored headers. This is the practical replacement for the
closed `rtxio.dll`.

## Scope assessment

This is a multi-week to multi-month engineering effort. The meson/build
portability is mostly solved; the remaining work is dominated by producing or
obtaining Linux builds of the external dependency set, several of which are
NVIDIA-proprietary closed binaries without Linux releases.
