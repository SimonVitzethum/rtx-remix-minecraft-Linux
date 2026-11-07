# dxvk-remix: Native Linux build (Port in progress)

Status: **early / iterative port**. This document tracks the effort to build the
RTX Remix runtime as a native Linux shared library so the Minecraft RTX Remix
mod (`rtx-remix-minecraft`) can run without Proton/Wine.

## Goal

A Linux shared object exposing the **Remix C API** (`remixapi_*`,
`remixapi_InitializeLibrary` et al.) plus the Vulkan renderer core. The mod's
JNI bridge (`remixbridge.so`) already `dlopen`s a runtime and calls
`remixapi_InitializeLibrary`.

## Why

NVIDIA ships Windows binaries only. The Remix renderer is built into the
D3D9 translation layer, which is Windows-specific. For Linux we want just the
renderer/SDK part, not the D3D9 interposer.

## Source structure (analysis)

- Renderer core: `src/dxvk/` + `src/dxvk/rtx_render/` (~107 .cpp in
  rtx_render; Vulkan-based, cross-platform).
- Remix C API implementation: `src/dxvk/rtx_render/rtx_remix_api.cpp` —
  includes only internal rtx_render headers + `remix/remix_c.h`; **no**
  D3D9 dependency.
- D3D9 interposer (Windows-only): `src/d3d9/` (34 .cpp) + `src/d3d9/` entry
  points (`d3d9_main.cpp`, `d3d9_rtx_shim.cpp`).
- Windows headers used in the core: only **10 files**, and mostly shallow:
  - `dxvk_instance.cpp`: `OutputDebugString`, `RtlCaptureStackBackTrace`,
    `GetLastError` (logging → replace with stderr / `backtrace()`).
  - `rtx_system_info.cpp`: `RtlGetVersion` (→ `uname`).
  - `imgui_impl_win32.cpp`: ImGui Win32 platform backend (→ omit/other backend).
  - `rtx_overlay_window.cpp`, `rtx_camera.cpp`, `rtx_shader_manager.h`,
    `imgui.cpp`, `dxvk_imgui.h`, `rtx_ngx_wrapper.cpp`: minor `DWORD`/
    `HANDLE` use.

**Conclusion:** a native Linux renderer/SDK build is feasible with targeted
edits to a small, well-identified set of files plus a new Linux meson target
that excludes `src/d3d9/` and `imgui_impl_win32.cpp`.

## What already works / fixed

- Submodules initialized.
- Packman dependency fetch now runs on Linux (cross-platform `meson.build`
  dep fetch; Windows keeps `update-deps.cmd`, POSIX uses
  `scripts-common/packman/packman pull`).
- `submodules/nrc/Include` case-sensitivity fixed.
- `windres` now only required on Windows hosts (native POSIX skips `.rc`).

## Blockers found (in order)

1. `update-deps.cmd` — fixed (cross-platform dep fetch).
2. `submodules/nrc/include` vs `Include` — fixed.
3. `windres` missing — fixed (guarded to Windows host).
4. `vulkan-1` library (Windows name) — needs `vulkan` on POSIX. Not yet fixed.
5. `d3d9`, `d3d11`, `dxgi`, `ws2_32`, `delayimp` libraries — Windows-only.
   The full target cannot link on Linux; hence the new reduced renderer/SDK
   target (below).

## Planned Linux target

New meson target (or a small root meson fork):
- Sources: `src/dxvk/**` (minus `imgui_impl_win32.cpp` and any other
  Win32-only files) + `src/dxvk/rtx_render/**` + `src/dxvk/rtx_remix_api.cpp`.
- Link: `libvulkan.so`, plus DLSS/NGX, NRC, NRD, RTXIO, USD libraries from the
  packman-fetched payloads (LINUX variants needed; currently only win/arm64
  variants are published).
- Output: `libremix.so` / a Remix runtime `.so` exposing `remixapi_*`.

Depends on Linux binaries for the NVIDIA components (DLSS/NGX/NRC/NRD/RTXIO/
USD). Some exist as Linux `.so` (DLSS/NGX via Streamline), others must be built
from submodules.

## Reproducing

```
git submodule update --init --recursive
meson setup _CompLinuxRelease
meson compile -C _CompLinuxRelease
```

(Currently stops at the Windows-library resolution until the reduced Linux
target is added.)

## Next steps

1. Add a `linux` branch/option to `meson.build` that resolves `vulkan` (not
   `vulkan-1`) and skips the Windows-only libraries.
2. Introduce the reduced renderer/SDK target (exclude `src/d3d9/` and
   `imgui_impl_win32.cpp`).
3. Replace the ~10 shallow Windows-header usages in the core.
4. Build/obtain Linux variants of DLSS/NGX/NRC/NRD/RTXIO/USD.
5. Wire the resulting `.so` into the mod's `remix-sdk-linux64` payload
   (`NativeExtractor` path already added).
