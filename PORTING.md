# dxvk-remix: Native Linux build (Port in progress)

Status: **early / iterative port**. This document tracks the effort to build the
RTX Remix runtime as a native Linux shared library so the Minecraft RTX Remix
mod (`rtx-remix-minecraft`) can run without Proton/Wine.

## Why

The mod's JNI bridge (`remixbridge.so`) already loads a runtime via `dlopen`
and calls `remixapi_InitializeLibrary`. What it needs is a Linux build of the
Remix runtime exposing that C API (plus the Vulkan renderer / DLSS / NRC /
NRD / USD components). NVIDIA publishes Windows binaries only, so this is a
self-maintained build.

## Layout

- `dxvk-remix/` — this clone (branch `native-linux` for our changes).
- Upstream: https://github.com/NVIDIAGameWorks/dxvk-remix

## What already works

- Submodules initialized (`include/vulkan`, `submodules/{rtxdi,rtxcr,nrc,
  nvapi,Detours,dlss,sharc,xess}`).
- Packman dependency fetch now runs on Linux:
  - `meson.build`: dep fetch is cross-platform now. Windows keeps
    `scripts-common/update-deps.cmd`; POSIX uses
    `scripts-common/packman/packman pull packman-external.xml`.
  - Confirmed: packman pulled `rtx-remix-*` packages (arm64 variants) into
    `~/.cache/packman`.

## Blockers found (in order)

1. `update-deps.cmd` (Windows batch) at meson configure time — fixed via the
   cross-platform dep fetch above.
2. Case sensitivity: `submodules/nrc/include` vs actual `submodules/nrc/Include`
   — fixed in `meson.build` (`nrc_include_path` → `submodules/nrc/Include`).
3. `windres` not found: the build expects the mingw resource compiler for
   Windows `.rc` resources. A native Linux build should skip/guard resource
   compilation (or provide windres). **Not yet fixed.**

## Likely next work

- Guard/skip `windres` and other mingw tools in `meson.build` for non-Windows
  hosts.
- Audit `meson.build` / `src/**` for Windows-only assumptions (D3D9 interposer,
  `__declspec`, `.lib` linking, `CreateFileW`-style host code).
- Decide target shape: a Linux shared object exposing the Remix C API
  (`remixapi_*`), not the D3D9 interposer. Likely a new/modified meson target
  and `copy_sdk` equivalent for Linux.
- Wire the produced `.so` into the mod's `remix-sdk-linux64` payload and the
  `NativeExtractor` path already added.

## Reproducing

```
git submodule update --init --recursive
meson setup _CompLinuxRelease
meson compile -C _CompLinuxRelease
```

(Currently fails at `windres`; work in progress.)

## Notes

- This is a multi-week engineering effort, not a one-shot port. The meson
  portability is the surface; the deeper work is source-level platform
  assumptions in the runtime.
