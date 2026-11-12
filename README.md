# RTX Remix for Minecraft — Native Linux, Minecraft 26.3

**Play Minecraft Java Edition 26.3 with RTX Remix path tracing natively on
Linux x86-64. Wine and Proton are not required.** This community fork includes
the Fabric integration, Vulkan and OpenGL backends, Java/JNI bridge, and Linux
build and packaging tools.

Path tracing, DLSS Ray Reconstruction and Frame Generation have been exercised
in real Linux sessions on an NVIDIA GeForce RTX 5070 Laptop GPU. This is an
actively developed port; hardware compatibility and every block/entity state
have not been fully validated.

## Full Native Vulkan Render Path

**The Minecraft 26.3 Vulkan backend is ported end to end on Linux:** Minecraft
render targets and HUD, RTX Remix path tracing and presentation, sky generation,
rain/snow composition and FarField rendering use Vulkan. The Vulkan backend
does not create a hidden OpenGL rendering context for sky or weather. An
alternative OpenGL backend remains available as a separate choice.

DLSS upscaling, Ray Reconstruction and Frame Generation work in this Vulkan
path on the tested hardware. See [Vulkan Interop](docs/VULKAN_INTEROP.md) for
the image and semaphore exchange. GPU DAG construction and GPU-built meshes
are tracked separately as ongoing development; their status is described below.

## Linux Source Branches

| Branch | Contents |
|---|---|
| [`main`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/main) | Published native Linux / Minecraft 26.3 development snapshot |
| [`mc-26.3-linux`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/mc-26.3-linux) | Mod, Java/JNI bridge and GPU-DAG development |
| [`runtime/native-linux`](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/runtime/native-linux) | Companion native dxvk-remix runtime; clone into a separate directory |

Far terrain resides in device-local DAG pages without a persistent Java
chunk/DAG cache or a complete native scene mirror. The measured reference build
constructs these pages in temporary native CPU workspaces. **Full GPU DAG
construction, GPU-built near-terrain meshes and GPU-driven scene scheduling
are still in development.** A tested GPU node-interning kernel alone is not
an integrated GPU terrain builder.

## Measured Linux Performance

Preliminary measurements from **November 12, 2026**: **RTX 5070 Laptop GPU,
8 GiB VRAM**, Linux, NVIDIA **615.78.08**, Java **25.0.5**, 4 GiB Java heap,
**1920×1080 output / 960×540 internal**, DLSS Ray Reconstruction, up to two
generated frames per rendered frame, Simplista 64x R36, simulation distance 8.
The workload requested **32 RTX chunks plus DAG terrain out to 512 chunks**.

**Upscaling was enabled in these benchmarks.** The FPS figures below apply to
960×540 internal rendering upscaled to 1920×1080 with DLSS Ray Reconstruction;
Frame Generation was enabled as well. Native-resolution performance without
upscaling has not been measured by this dataset.

| Phase, CPU-built DAG reference `ad84df0` | Rendered FPS | Native presents/s including FG | Render-span p99 | Longest render span |
|---|---:|---:|---:|---:|
| Stationary, 6,000 frames | 30.43 | 91.29 | 78.21 ms | 128.86 ms |
| Movement, 3,601 frames | 20.76 | 62.29 | 126.51 ms | 255.74 ms |

Presents are measured native submission counters, not monitor scanout or
additional independently rendered frames. Render spans are CPU/wall time,
not isolated GPU timings. The 544-block outward and return camera path with
rotation completed with Java exit 0, but terrain buildup did not converge.
Full 512 coverage and visible RTX pixels through the complete 32-chunk radius
are **not proven**. Image/coverage review remains pending and JFR export failed.
These are provisional reference results, **not GPU-DAG results or a proven
speedup**. See [Linux Performance](docs/LINUX_PERFORMANCE.md) for package hashes,
historical runs, memory pressure, the chart and downloadable measurement data.

## What To Expect

This community Linux port is a development build of NVIDIA's beta showcase.
The original project was built as a technology showcase, but we love Minecraft
and want it to succeed as a rendering mod. We tuned it against one showcase
world, built for this project by [BlockWorks](https://www.blockworks.uk/) (see
[Showcase World And Materials Pack](#showcase-world-and-materials-pack)), and the places
that world visits are where it looks and runs best. We have worked hard to fix
as many issues as we could, but it ships with
[known issues](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/issues),
and there are certainly bugs yet to be discovered.

Three areas have had less attention than the rest:

- **Other mods.** It has had limited testing alongside other mods, and a
  renderer that takes over the scene the way this one does is likely to
  conflict with anything that changes rendering or the chunk pipeline.
- **A full playthrough.** Blocks, mobs, dimensions, and situations the showcase
  never reaches have had less care, and some will look wrong or run slowly.
- **Other versions.** It targets Minecraft 26.3 only. Ports to other versions
  are something we hope the community takes on.

We hope the community helps fill it out. If something breaks,
[report it](docs/PLAYING.md#reporting-a-bug); if you can fix it, see
[Contributing](#contributing).

## Universal drop-in distribution (release gate pending)

The root `./gradlew build` (Windows: `gradlew.bat build`) now targets one
`build/libs/rtx-remix-renderer-universal.jar` for Linux and Windows on both
x86-64 and ARM64. Fabric API 0.165.2+26.3 is nested inside it. Minecraft 26.3,
Fabric Loader >=0.19.5 and Java 25 remain launcher prerequisites. The player
installation requires no C/C++ compiler; native selection and installation are
automatic, and shader compilation is permitted.

**No four-platform combined-API release exists yet.** The build refuses to label a
partial distribution as universal: the runtime source/ABI freeze and all four
matching precompiled packages are still release gates. Historical Linux tests
used API 0.16.2; its runtime requires **glibc 2.44**, and the tested
Vulkan **1.4.351** / NVIDIA **615.78.08** versions do not establish a lower driver
minimum. Windows/ARM64 hardware validation remains outstanding. This does not
promise every Linux ABI or driver. See [the exact package and compatibility
contract](docs/NATIVE_DISTRIBUTION.md) and [build instructions](docs/GETTING_STARTED.md).

External texture/resource packs stay local and are not included in the mod
JAR. The renderer's own assets, baseline textures and two required runtime DDS
files remain part of the distribution.

## Upstream Windows Download

This section describes NVIDIA's original Windows distribution and optional
showcase content. It does not install this fork's native Linux runtime.
Linux users should follow [Linux](#linux) below.

Download the installer bundle, `rtx-remix-renderer-<version>-installer.zip`,
from the
[Releases page](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases/latest).
It holds the mod, the
[RTX Remix Materials pack and the RTX Remix Showcase World](#showcase-world-and-materials-pack),
and the installer that sets them up.

## Upstream Windows Installation

1. Extract the installer bundle.
2. Double-click `install.bat` and follow the menu. Pick a launcher, keep
   RTX Remix Renderer, RTX Remix Materials, and RTX Remix Showcase World
   ticked, and press Enter on Continue.
3. Sign in, and launch the **NVIDIA RTX Remix for Minecraft** profile from
   your launcher.
4. Open Singleplayer and choose **NVIDIA RTX Demo**.

The step-by-step version, with launcher advice and troubleshooting, is in
[Playing](docs/PLAYING.md).

## Requirements

- Windows 10 or 11, x64 or ARM64, or native Linux x64.
- Minecraft Java Edition 26.3, on a Microsoft account that owns the game.
- A compatible NVIDIA GeForce RTX GPU and Vulkan driver. Linux measurements
  used an RTX 5070 Laptop GPU with driver 615.78.08. Upstream Windows releases
  specify their own GPU and driver requirements.
- About 5 GB of free disk space while installing; about 3 GB stays in use.
- An internet connection while the installer runs.
- 16 GB of system memory is recommended.

The upstream installer manages Java, Fabric and its Windows runtime. The native
Linux source workflow requires JDK 25 and a separately built Linux runtime.

Building and running from source has its own
[prerequisites](docs/GETTING_STARTED.md#prerequisites), including Prism
Launcher, signed in to a Microsoft account that owns Minecraft: Java Edition.

## Linux

The mod runs natively on Linux x64; no Proton or Wine is needed. Build the mod
with `-Parch=linux64` and stage the natively built dxvk-remix runtime with
`tools/stage_linux_runtime.sh`; see
[Getting Started](docs/GETTING_STARTED.md#linux).

Clone the mod and the companion runtime into sibling directories:

```bash
git clone git@github.com:SimonVitzethum/rtx-remix-minecraft-Linux.git rtx-remix-minecraft
git clone --single-branch --branch runtime/native-linux \
  git@github.com:SimonVitzethum/rtx-remix-minecraft-Linux.git dxvk-remix
```

Build the runtime using its
[Linux build guide](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/blob/runtime/native-linux/documentation/LinuxBuild.md).
From the mod checkout, with `JAVA_HOME` pointing to a **64-bit JDK 25**:

```bash
tools/stage_linux_runtime.sh ../dxvk-remix ../dxvk-deps
./gradlew test jar  # Java development; native release gates are described above
./gradlew setupRuntime -Parch=linux64  # explicit local JNI build and runtime staging
tools/linux/rtx.sh launch
```

The launcher builds and installs into a development Prism instance. Distribution
JARs are written under `build/distributions/`; the lightweight `build/libs/`
JAR is not the packaged Linux release. Sign in to Prism with an account that
owns Minecraft before launching. Do not replace a live game's native libraries.

Windowing follows the `LinuxWindowPlatform` JVM flag: `-Drtxremix.wayland=true`
selects Wayland, `-Drtxremix.wayland=false` selects X11/XWayland. Precompile
shaders before the game starts with `tools/linux/precompile.sh <game dir>`; in
Prism Launcher set the pre-launch command to
`bash "$INST_MC_DIR/remix-sdk/precompile.sh" "$INST_MC_DIR"`.

DLSS Super Resolution, Ray Reconstruction and Frame Generation have been
exercised on Linux; availability depends on the GPU, driver and SDK. XeSS,
NRC, Reflex, Aftermath and NVAPI are compiled out, and RTXIO is disabled.
The measurement host has 32 GiB RAM and used a 4 GiB Java heap. Leave memory
for native allocations, the integrated server and the driver rather than
reserving all RAM for Java.

Exact Vanilla Endportal/Gateway screen projection, darker portal particles,
additional block/entity states and complete 512/32 validation remain open.
Cluster acceleration structures are experimental and opt-in. No general
game-performance improvement is claimed for them.

## Crash Reports And Privacy

The mod collects no data of its own. The RTX Remix runtime it carries can send
basic session information and crash reports to NVIDIA. Its First Use Guide asks
about both, with both on by default, and the Permissions tab of the RTX Remix
Graphics Settings menu changes them at any time.
[Crash Reports And Privacy](docs/PLAYING.md#crash-reports-and-privacy) lists
what is sent.

## What it does

**Captured blocks, mobs and items are lit by a path tracer.** Light bounces off walls,
through glass, and out of torches the way it does in a film renderer, so shadows,
reflections, and the colour a nearby block casts on its neighbours are all
computed rather than painted in.

**The look follows the game.** Time of day, weather, and being underwater each
bring their own lighting, and a map author can attach a look to a place in the
world. Nothing needs switching on.

**A new block.** The Copper Beacon is a directional lamp you can place or
carry, and its beam is a real light that picks out walls, rain, and anything
standing in it.

**Camera flythroughs.** Record a path by walking it, then replay it, for a video
or for an exact before-and-after comparison.

**A settings screen in the game.** Distances, framerate, sky, lighting,
exposure, and materials, all in Options, with no config files to edit, and a
button into Remix's own Graphics Settings for the quality tier.

## Showcase World And Materials Pack

Two extras go with the mod. Each release carries both, inside the installer
bundle and as files of their own, and the installer puts them in place. The
developer launcher in [`tools/`](tools/README.md) downloads both the first
time it runs and launches into the world with the pack on.

- **RTX Remix Materials**, a texture resource pack created by an art team at
  NVIDIA, is the look the renderer was made for, and the recommended way to
  play. The installer turns it on above the textures built into the mod and
  below your own packs. Without it, the mod's built-in baseline textures give
  vanilla's textures simpler materials. Its normal and specular maps follow the
  [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard), so
  other renderers and shader packs that read LabPBR may be able to use it too,
  but it has only been tested with this mod. Its `_sss` subsurface maps are
  this mod's own addition, described in
  [Resource Packs](docs/RESOURCE_PACKS.md).
- **RTX Remix Showcase World**, the map the renderer was tuned against, was
  built for this project by BlockWorks: [website](https://www.blockworks.uk/),
  [YouTube](https://www.youtube.com/@BlockWorksYT),
  [X](https://x.com/BlockWorksYT), and
  [Instagram](https://www.instagram.com/blockworks/). The installer unpacks it
  as **NVIDIA RTX Demo** under Singleplayer, and never overwrites a copy you
  already have.

To add either by hand, take `RTX-Remix-Materials-<version>.zip` or
`RTX-Remix-Showcase-World-<version>.zip` from the release and follow
[Installing Without The Installer](docs/PLAYING.md#installing-without-the-installer).

## Start Here

| You want to | Read |
|---|---|
| Play with path tracing | [Playing](docs/PLAYING.md) |
| Check the known issues | [Issue tracker](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/issues) |
| Play on a server | [Multiplayer](docs/PLAYING.md#multiplayer) |
| Configure the renderer | [Settings](docs/SETTINGS.md) |
| Look up a command, a key, or a mod-added block | [In-Game Guide](docs/IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](docs/RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](docs/CAMERA_PATHS.md) |
| Install, repair, or uninstall in detail | [packaging/INSTALL.md](packaging/INSTALL.md) |
| Build and run from source | [Getting Started](docs/GETTING_STARTED.md) |
| Check native Linux FPS and measurement limits | [Linux Performance](docs/LINUX_PERFORMANCE.md) |
| Tune the developer knobs behind the renderer | [Tuning](docs/TUNING.md) |
| Diagnose a rendering or native integration problem | [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) |
| Contribute a change | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Report a bug | [Reporting a Bug](docs/PLAYING.md#reporting-a-bug) |
| Report a security vulnerability | [SECURITY.md](SECURITY.md) |
| See what changed | [Releases](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases) |

## Source And Tooling Layout

| Path | Purpose |
|---|---|
| `src/` | Mod implementation and JVM tests |
| `rtxremix-java/` | Minecraft-agnostic Java facade and JNI bridge over the RTX Remix C API |
| `tools/` | Developer launcher, benchmarks, profiling, SDK staging, and development installation |
| `packman/` | Packman bootstrap and runtime package pins |
| `packaging/` | Public installer, runtime defaults, and distribution payload |
| `docs/` | Player, authoring, and source-build guides |

NVIDIA maintains some release and CI tooling outside this repository. It is
not required by the public Gradle build, the developer tools, or the installed
mod.

## Contributing

External contributions are welcome, and the gaps above are where help matters
most: compatibility with other mods, coverage of the blocks, mobs, and places a
real playthrough reaches, and performance on hardware we do not have. Fork this
repository, create a development branch, and submit a pull request. Sign off
each commit with `git commit -s` to certify it under the
[Developer Certificate of Origin](https://developercertificate.org/); there is
no Contributor License Agreement to sign. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the full guidelines.

## License

This community fork builds on
[NVIDIA RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)
and [dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix). Report fork-specific
issues on the [Linux fork tracker](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/issues).
This project is licensed under the Apache License, Version 2.0 (`Apache-2.0`).
See the [LICENSE](LICENSE) file. Attribution notices are in [NOTICE](NOTICE),
and third-party software distributed with this project is documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The mod JAR and the
installer bundle on the Releases page also carry the RTX Remix runtime, whose
NVIDIA components are covered by their own license terms;
THIRD_PARTY_NOTICES.md lists them.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA
Corporation in the U.S. and other countries.

## Experimental Forge 1.12.2

The [Forge module](forge-1.12.2/README.md) provides a separate Windows x64 build.
Its guide describes the implemented rendering features and current limitations.
