# RTX Remix Java + JNI Shim

**Native Linux x86-64 is supported.** The Fabric 26.3 build uses JDK 25,
`remixbridge.so` and the native `libremixapi.so` runtime without Wine or Proton.
See [Linux setup](../docs/GETTING_STARTED.md#linux) and the dated
[Linux performance measurements](../docs/LINUX_PERFORMANCE.md).

`rtxremix-java` is a Java facade and JNI shim over the NVIDIA RTX Remix C API.
It exposes runtime operations, data structures, and handles to Java consumers,
marshals calls through JNI, and loads the separately distributed native runtime
built from [dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix).
It does not implement a renderer or interpret application state.

The shim is Minecraft-agnostic. It must never import `net.minecraft` or
`net.fabricmc`; game integration belongs to the consuming application.
The root `RemixJavaBoundaryTest` enforces that dependency boundary.

## Components

Paths in this table are relative to `rtxremix-java/`.

| Path | Responsibility |
|---|---|
| `src/com/nvidia/rtxremix/RemixAPI.java` | Java facade for runtime operations |
| `src/com/nvidia/rtxremix/RemixBridge.java` | Static JNI entry points |
| `src/com/nvidia/rtxremix/` | API data holders, builders, constants, and handles |
| `native/remixbridge.cpp` | Runtime loading, JNI marshalling, and C API calls |
| `native/include/remix_c.h` | C API header copied from the pinned runtime build |
| `native/build_native.bat` | MSVC bridge build for Windows x64 or ARM64 |
| `native/build_native.sh` | Native Linux bridge build with g++/clang |
| `src/com/nvidia/rtxremix/VoxelDag.java` | Separate Minecraft-agnostic DAG service |

The bridge loads `libremixapi.so` on Linux or `d3d9.dll` on Windows, calls `remixapi_InitializeLibrary`, and forwards
Java operations through the resulting C API interface. Java-side handles are
raw `long` values. Native methods that require direct `ByteBuffer`s must not
receive heap buffers. Document buffer directness on every new JNI entry point.
There is no JNA interface table.

## Consumption By The Root Build

The repository's root Gradle project compiles these Java sources directly with
the mod and builds `remixbridge.so` on Linux or `remixbridge.dll` on Windows. There is no separate wrapper Java build
or example launcher. The mod is a consumer of the shim, not a dependency of it.

The Minecraft 26.3 root build requires a 64-bit JDK 25. Linux uses a C++17
compiler and Vulkan/OpenGL development libraries; Windows uses Visual Studio
C++ tools. Running also requires a supported NVIDIA RTX GPU and
a runtime matching the copied C API header. The root paths are:

| Path | Responsibility |
|---|---|
| `packman/remix-sdk.packman.xml` | Published runtime version pins |
| `packman/` | Runtime package pins and Packman bootstrap |
| `tools\rtx.bat sdk` | SDK source selection, local snapshots, and refresh |
| `remix-runtime/{linux64,win64,arm64}/` | Ignored runtime staging directories |
| `build/native/{linux64,win64,arm64}/` | Ignored JNI bridge build outputs |

On Linux, stage the separately built runtime and run from the repository root:

```bash
tools/stage_linux_runtime.sh ../dxvk-remix ../dxvk-deps
"$JAVA_HOME/bin/java" -cp gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain build test package verifyPackageJar -Parch=linux64
```

The Linux runtime source is on the
[`runtime/native-linux` branch](https://github.com/SimonVitzethum/rtx-remix-minecraft-Linux/tree/runtime/native-linux).
For a bridge-only Linux build, run `rtxremix-java/native/build_native.sh x64`.
The root build packages the bridge together with the staged runtime. A Java
test run alone does not prove GPU interoperability or rendering correctness.

On Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\tools\rtx.bat launch
```

On Windows, Gradle fetches missing runtimes automatically, then combines the selected runtime
with the separately built bridge during development staging and packaging. Packman runtime directories are read-only
junctions into a shared cache, so they stay outside `build/` and must never
receive bridge outputs. A staged local runtime is a snapshot copy instead.
SDK payloads and native outputs are not committed.

For a bridge-only rebuild from the root, use
`.\gradlew.bat compileNativeWin64` (`compileNativeArm64` for ARM64), or
`.\rtxremix-java\native\build_native.bat x64` (`arm64` for cross-compilation).
The native build uses `rtxremix-java/native/include/remix_c.h` unless
`REMIX_INCLUDE` selects another header directory. See
[Getting Started](../docs/GETTING_STARTED.md) for prerequisites, published and
local SDK workflows, and packaging.

## API Change Workflow

1. Put renderer behavior and C API changes in dxvk-remix. Keep Java types and
   JNI marshalling in this shim, without application-specific dependencies.
2. Once the matching runtime package is published, update
   `packman/remix-sdk.packman.xml` at the repository root and the copied header
   together. The header matches the pinned build, which can be ahead of
   dxvk-remix `main`; do not edit it in isolation.
3. Update `native/remixbridge.cpp`, `RemixBridge.java`, `RemixAPI.java`, affected
   data types, and all consuming mod callers in the same pull request.
   `StructType.java` values and API version constants must match the runtime.
   See [Runtime And Java API Changes](../AGENTS.md#runtime-and-java-api-changes)
   for struct-type coordination and version negotiation.
4. Restore the pinned SDKs and rebuild the bridges from the root with
   `.\tools\rtx.bat sdk packman --arch all` and
   `.\gradlew.bat compileNativeWin64 compileNativeArm64`.
5. Build, run the JVM tests, and exercise native loading and rendering by
   launching with the root `tools\rtx.bat launch` before requesting review. Java compilation
   and JVM tests alone do not validate JNI compatibility. The published,
   pinned runtime must implement the API exposed by the header and facade.

Repository-wide contribution rules and sign-off requirements are in
[CONTRIBUTING.md](../CONTRIBUTING.md). Agent guidance and JNI invariants are in
[AGENTS.md](../AGENTS.md#integrated-jni-wrapper).

## License

Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.

The shim is covered by the repository's Apache License, Version 2.0.
See [LICENSE](../LICENSE), [NOTICE](../NOTICE), and
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for the applicable terms and
attributions.

