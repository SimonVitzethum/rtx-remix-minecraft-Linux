# RTX Remix Runtime — Native Linux

**This fork builds and runs RTX Remix natively on Linux x86-64**, including the
Minecraft Java Edition 26.3 integration. It produces `libremixapi.so`; Wine and
Proton are not required. Wayland, X11 and headless presentation are supported.

The companion Minecraft 26.3 integration has an **end-to-end Vulkan render
path**, including Minecraft targets/HUD, Remix path tracing and presentation,
sky, rain/snow composition and FarField. Its Vulkan backend uses no hidden
OpenGL rendering context. The optional OpenGL backend is a separate choice.
See the [Vulkan integration guide](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/blob/main/docs/VULKAN_INTEROP.md).

The source for this runtime is published on
[`runtime/native-linux`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/runtime/native-linux).
The companion Minecraft mod and JNI bridge are on
[`main`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux) and
[`mc-26.3-linux`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/mc-26.3-linux).
These are separate source trees; clone them into sibling directories.

## Native Linux Build and Features

Use the [Linux build guide](documentation/LinuxBuild.md) for GCC, Meson/Ninja,
native OpenUSD and NRD, Linux DLSS libraries, Slang and SPIR-V dependencies.
The Minecraft build stages this runtime with `tools/stage_linux_runtime.sh`
from its own checkout. The Windows bridge below is not required for native
Minecraft integration through the Remix C API.

- Native Vulkan path tracing and external-memory/timeline-semaphore interop.
- DLSS Super Resolution, Ray Reconstruction and Frame Generation exercised
  on the Linux test hardware; availability depends on GPU, driver and SDK.
- Linux texture-file notifications, streamed texture uploads, native ImGui
  input, and explicit image/buffer lifetimes.
- Cluster acceleration structures are experimental and opt-in. They are not
  evidence that Minecraft terrain meshes or its DAG are already GPU-built.
- XeSS, NRC, Reflex, Aftermath and NVAPI are compiled out on Linux; RTXIO is
  disabled.

## Nix Development Environment

The committed `flake.nix` and `flake.lock` provide a pinned C++/shader tool
environment for **x86-64 Linux and ARM64 Linux**:

```bash
nix develop
```

It supplies the compiler, Meson/Ninja, CMake, Vulkan headers/loader, Slang,
glslang and SPIR-V Tools. OpenUSD, NRD, NVIDIA SDK payloads and the installed
GPU driver retain their own version, platform and license requirements; they
are not implicitly bundled by this shell. Follow
[Linux Build](documentation/LinuxBuild.md) for those inputs and pass
`-Dslangc="$(command -v slangc)"` when configuring Meson to select the shell's
compiler instead of a separately installed Slang.

`nix flake check --no-build` evaluates the flake. `nix flake update` intentionally
changes the locked Nixpkgs version. The flake is a development shell, not a
sandboxed derivation of the complete proprietary-SDK runtime. Native Windows
development continues to use the Windows build workflow below.

Keep optional texturepacks local in ignored `resourcepacks/` or `texturepacks/`
directories; ZIP archives and Nix `result` links are ignored.

## Linux Performance Reference

On November 12, 2026, the Minecraft integration was measured on an **RTX 5070
Laptop GPU, 8 GiB VRAM**, NVIDIA **615.78.08**, Linux and Java **25.0.5**.
The CPU-built DAG reference used **1920×1080 output / 960×540 internal**, DLSS
Ray Reconstruction, up to two generated frames, Simplista 64x R36, RD32/SD8
and requested DAG distance 512.

**Upscaling and Frame Generation were enabled:** these figures use 960×540
internal rendering upscaled to 1920×1080. They are not measurements of native
1920×1080 rendering with upscaling disabled.

| Phase | Rendered FPS | Native presents/s including FG | Render-span p99 |
|---|---:|---:|---:|
| Stationary | 30.43 | 91.29 | 78.21 ms |
| Movement | 20.76 | 62.29 | 126.51 ms |

These are **whole Minecraft integration measurements**, not runtime-only GPU
timings or GPU-DAG results. Presents count native submissions, not physical
display scanout. Full 512 coverage and the complete visible 32-chunk RTX radius
remain unproven; the run completed its movement path but did not converge.
Image review and a usable JFR recording are not available for this run, so the
figures remain provisional and do not establish a speedup.
See the [performance report and measurement data](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/blob/main/docs/LINUX_PERFORMANCE.md)
for package hashes, methodology, historical controls and limitations.

Full GPU DAG construction, GPU-built near-terrain meshes and GPU-driven scene
scheduling remain under development in the companion integration.

## Upstream Project

[NVIDIA's upstream build workflow](https://github.com/NVIDIAGameWorks/dxvk-remix/actions/workflows/build.yml)
describes the upstream project, not validation of this Linux fork.

dxvk-remix is a fork of the [DXVK](https://github.com/doitsujin/dxvk) project, which overhauls the fixed-function graphics pipeline implementation in order to remaster games with path tracing.

Thanks to all the contributors to DXVK for creating this foundational piece of software, on top of which we were able to build the RTX Remix Runtime.

While dxvk-remix is a fork of DXVK, please report bugs encountered with dxvk-remix to this repo rather than to the DXVK project.

dxvk-remix also contains a subproject in the `bridge` folder, which enables 32 bit games to communicate with the 64 bit dxvk-remix runtime.

## Windows Build Instructions

### Requirements:
1. Windows 10 or 11
2. [Git](https://git-scm.com/download/win)
3. [Visual Studio ](https://visualstudio.microsoft.com/vs/older-downloads/)
    - VS 2019 and 2022 are tested
    - Building for Windows on Arm requires VS 2022 with the `C++ ARM64/ARM64EC build tools` individual component
    - MSVC v14.44 or newer is preferred when building for Windows on Arm
    - Note that our build system will always use the most recent version available on the system
4. [Windows SDK](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/)
    - 10.0.19041.0 is tested
5. [Meson](https://mesonbuild.com/)
    - 1.8.2 has been tested
    - Follow [instructions](https://mesonbuild.com/SimpleStart.html#installing-meson) on how to install and reboot the PC before moving on (Meson will indicate as much)
6. [Vulkan SDK](https://vulkan.lunarg.com/sdk/home#windows)
    - 1.4.313.2 or newer
    - You may need to uninstall previous SDK if you have an old version
7. [Python](https://www.python.org/downloads/)
    - 3.9 or newer
    - Ensure you are using python installed from the link above and not from the Microsoft Store
    - Python is required by developer build tooling; the packaged RTX Remix Runtime does not link against Python.
8. [DirectX Runtime](https://www.microsoft.com/en-us/download/details.aspx?id=35)
    - Latest version should work.
    - This includes d3d9x*.dll which are required to run the game
    - May already be installed if you have D3D9 games installed

#### Additional notes:
- If dependency paths change (for example, after installing a new Vulkan SDK), reconfigure the affected build from the repository root, such as `meson setup --reconfigure _CompRelease_x64`.

### Generate and build dxvk-remix Visual Studio project 
1. Clone the repository with all submodules:
	- `git clone --recursive https://github.com/NVIDIAGameWorks/dxvk-remix.git`

	If the clone was made non-recursively and the submodules are missing, clone them separately:
	- `git submodule update --init --recursive`

2. Install all the [requirements](#requirements) before proceeding further

3. Make sure PowerShell scripts are enabled
    - One-time system setup: run `Set-ExecutionPolicy -ExecutionPolicy RemoteSigned` in an elevated PowerShell prompt, then close and reopen any existing PowerShell prompts
	
4. To generate and build dxvk-remix project:
    - Right Click on `dxvk-remix\build_dxvk_all_ninja.ps1` and select "Run with Powershell"
    - If that fails or has problems, run the build manually in a way you can read the errors:
        - open a windows file explorer to the `dxvk-remix` folder
        - remove only the generated configuration that failed, such as `_CompDebug_x64/`; remove `_vs/` as well only if the generated Visual Studio solution must be recreated
        - type `cmd` in the address bar to open a command line window in that folder.
        - copy and paste `powershell -command "& .\build_dxvk_all_ninja.ps1"` into the command line, then press enter
    - Optional flags:
        - `-SkipApics` — skip downloading game test captures (requires auth token)
        - `-BuildArch <x64|arm64|arm64ec>` — target architecture, default `x64`. `arm64ec` is configured with the Visual Studio backend rather than Ninja, because Meson cannot link arm64ec targets with Ninja, so the `_Comp*_arm64ec` directories hold MSBuild projects instead of Ninja files. The generated **_vs** solution always drives the `_Comp*_x64` directories; build other architectures with `meson compile -C <build directory>`.
    - Examples:
        ```powershell
        .\build_dxvk_all_ninja.ps1
        .\build_dxvk_all_ninja.ps1 -SkipApics
        .\build_dxvk_all_ninja.ps1 -BuildArch arm64
        ```
    - This will build all 3 configurations of dxvk-remix project inside subdirectories of the build tree:
        - **_CompDebug_x64** - full debug instrumentation, runtime speed may be slow
        - **_CompDebugOptimized_x64** - partial debug instrumentation (i.e. asserts), runtime speed is generally comparable to that of release configuration
        - **_CompRelease_x64** - fastest runtime
        - Note: replace **x64** with the architecture selected via `-BuildArch`
    - This will generate a project in the **_vs** subdirectory

5. Open **_vs/dxvk-remix.sln** in Visual Studio (2019+). 
    - Do not convert the solution on load if prompted when using a newer version of Visual Studio 
    - Once generated, the project can be built via Visual Studio or via powershell scripts
    - A build will copy generated DXVK DLLs to any target project as specified in **gametargets.conf** (see its [setup section](#deploy-built-binaries-to-a-game))

### Deploy built binaries to a game 
1. First time only: copy **gametargets.example.conf** to **gametargets.conf** in the project root

2. Update paths in the **gametargets.conf** for your game. Follow example in the **gametargets.example.conf**. Make sure to remove "#" from the start of all three lines

3. Reconfigure and rebuild each configuration you use so Meson reloads **gametargets.conf**. For example:
    ```powershell
    meson setup --reconfigure _CompRelease_x64
    meson compile -C _CompRelease_x64
    ```
    The build deploys binaries to the game directories specified in **gametargets.conf**.

### Profiling Remix
Remix has support for profiling using the [Tracy](https://github.com/wolfpld/tracy) tool, specifically the [v0.8 release](https://github.com/wolfpld/tracy/releases/download/v0.8/Tracy-0.8.7z)

To enable Tracy profiling:
1. Open a command line window in a build folder (i.e. `dxvk-remix/_CompRelease_x64/`)
2. Run `meson --reconfigure -D enable_tracy=true`
3. Rebuild dxvk-remix-nv

To profile:
1. Launch tracy.exe
2. Launch the game and reach the section you wish to profile
3. When ready, hit `Connect` in Tracy to begin profiling.
4. It's best to collect at least 500 frames worth of data, so you can average out the results.

### Remix API

If there's an intent to use the Remix Renderer in projects with *available* source code, Direct3D 9 API can be utilized, since Remix's `d3d9.dll` implements the Direct3D 9 API.
Alternatively, Remix API can be used to programmatically pass the game data to the Remix Renderer, with *or* instead of Direct3D API. [Click for more info.](/documentation/RemixSDK.md)

## Project Documentation

- [Anti-Culling System](/documentation/AntiCullingSystem.md)
- [Contributing Guide](/CONTRIBUTING.md)
- [Foliage System](/documentation/FoliageSystem.md)
- [GPU Print](/documentation/GpuPrint.md)
- [Opacity Micromap](/documentation/OpacityMicromap.md)
- [Particle Spawn Distribution](/documentation/particle-spawn-distribution.md)
- [Remix API](/documentation/RemixSDK.md)
- [Remix Runtime Binaries](/documentation/RemixRuntimeBinaries.md)
- [Rtx Options](/RtxOptions.md)
- [Terrain System](/documentation/TerrainSystem.md)
- [Unit Test](/documentation/UnitTest.md)
