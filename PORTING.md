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

| Dependency | Package | Linux availability |
|---|---|---|
| OpenUSD | `usd_release` (windows-x86_64) | build from source (Pixar OpenUSD) |
| RTXIO | `rtxio` | NVIDIA-proprietary, no Linux build |
| NRC | `nrc_arm64` | submodule source exists (`submodules/nrc`) |
| NRD | `nrd` / `nrd_arm64` | NVIDIA-proprietary, no Linux build |
| DLSS-FG | `ngx_sdk_dlfg` | Linux via Streamline (libnvidia-ngx-*) |
| DLSS-RR | `ngx_sdk_dlnr` | Linux via Streamline |
| Reflex | `reflex_arm64` | Linux build exists (`NvLowLatencyVk`) |
| Aftermath | `aftermath` | NVIDIA-proprietary, no Linux build |
| Sentry-native | `sentry-native` | build from source |
| Slang | `slang` (win64) | build from source (NVIDIA Slang) |
| glslang/SPIRV-Tools | `glslangvalidator`, `spirv_tools` | build from source |
| XeSS | submodule `submodules/xess` | source exists |

Implication: a native build needs **Linux variants of ~12 external deps**. Some
are buildable from source (OpenUSD, Slang, glslang/SPIRV-Tools, Sentry, NRC,
XeSS, Reflex); the NVIDIA-proprietary closed binaries (RTXIO, NRD, Aftermath)
have no Linux releases and are the hard blockers unless NVIDIA publishes them
or we reimplement/stub them.

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

## Scope assessment

This is a multi-week to multi-month engineering effort. The meson/build
portability is mostly solved; the remaining work is dominated by producing or
obtaining Linux builds of the external dependency set, several of which are
NVIDIA-proprietary closed binaries without Linux releases.
