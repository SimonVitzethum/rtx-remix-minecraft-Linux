# Native distribution contract

`./gradlew build` produces **one** `build/libs/rtx-remix-renderer-universal.jar`
with custom Java/JNI bindings, a verified native bootstrap contract, Fabric API
0.165.2+26.3, mod assets, runtime DDS textures, configuration and licenses.
It does not fetch, compile or require unpublished native packages. Any archives
already present for packaging are fully verified. The SHA-256 sidecar names the
exact JAR; `verifyPackageJar` remains mandatory. `package` aliases `build`.

`./gradlew verifyNativeDistribution` is the explicit all-platform maintainer
gate. It requires the four frozen runtime/bridge packages and verifies every
inventory. Release archives depend on it. Matching-platform CPU JNI/API probes
and separate GPU/driver hardware validation are also required for native release
readiness. A portable Java build does not certify RTX operation on four platforms.

## Current evidence and release blockers

| Platform | Combined runtime + bridge bundle | Actual validation |
|---|---|---|
| Linux x86-64 | Not available; native package pending | Historical API 0.16.2 GPU sessions; API 0.16.4 JNI/CPU checks pass; combined runtime link and game tests pending |
| Linux ARM64 | Not available | ELF AArch64 DLSS libraries exist; own runtime/dependencies/JNI producer and hardware tests pending |
| Windows x86-64 | Not available | Official API 0.16.2 runtime is incompatible; own runtime build and hardware tests pending |
| Windows ARM64 | Not available | Pure ARM64 DLSS DLLs exist; own runtime/dependencies/JNI producer and hardware tests pending |

The checked-in manifest has four `unavailable` entries and `freeze=pending`.
The ordinary Java build succeeds with this explicit bootstrap state; the native
release gate fails with all four reasons. No final native bundle hashes or
working download URLs are claimed. Compatible binaries must first be built,
audited and published. Startup checks only the selected platform and reports its
unavailable reason before initializing the renderer.

The coordinated header/JNI ABI is frozen at API **0.16.4**: 41 interface
pointers, with the original 36-pointer prefix preserved for API 0.16.2/0.16.3
clients. Compact quads retain struct type **32** and `RESOURCE_BUSY` remains
error **14**; generic GPU work uses struct types **33–37**. New JNI bridges must
export `bridgeContract()J` and all five GPU work entry points. Startup rejects a
stale bridge before initialization. The frozen header SHA-256 is
`54edd326b43281a8a5c766a5ec05cd8b144397fdfc0cbc518304236963008210`.
The full runtime link, production GPU-DAG migration, combined game validation
and four-platform binary distribution remain separate pending work. Manifest
`freeze=pending` describes that distribution state. The build derives `api`,
`interfacePointers`, header digest and native
source digest into `native-source.properties` from the actual copied source;
these values are not hard-coded in the installer or producer. A release manifest
must exactly match that compiled contract. `createMesh` appends direct compact-quad buffer/count arrays
to its existing JNI export. All producers also include `voxel_dag.cpp`,
`voxel_dag_jni.cpp`, and MIT miniz, including `VoxelDag.overlaySurface`.
Generated JNI headers and JVM descriptors are verified against each bundle;
export names alone cannot detect a changed parameter list.

## Supported ABI and driver policy

There is **no established four-platform minimum** until native release gates
pass. Do not interpret target selection as support for every distribution,
CPU feature set, NVIDIA GPU or driver.

The audited historical Linux runtime requires **GLIBC_2.44**; its JNI bridge
requires **GLIBC_2.34**. The combined historical package therefore needs at least
**glibc 2.44**, plus its actual libstdc++/CXXABI dependencies. A claim of an older
glibc minimum requires rebuilding the runtime and its dependencies in an older
sysroot and auditing every shipped ELF. musl/Alpine is not supported. Linux
producers use baseline x86-64 or ARMv8-A, never `-march=native`/x86-64-v3.

**Vulkan 1.4.351**, **NVIDIA 615.78.08** and an **RTX 5070 Laptop GPU** are the
known tested Linux combination, not proven lower driver/API minima. Existing
Minecraft Vulkan interop requires push descriptors, dynamic rendering,
synchronization2 and timeline semaphores; Remix additionally needs its
ray-tracing/buffer-device-address features. Optional compact mesh extensions
have their own capability checks. Driver compatibility must be measured and
recorded per release. NVIDIA drivers and the Vulkan loader remain system
requirements. The minimum Windows version is likewise pending producer and
hardware validation, rather than inherited from an older upstream package.

Minecraft **26.3**, Fabric Loader **>=0.19.5** and CPU-matching 64-bit **Java 25**
are launcher prerequisites. Fabric API **0.165.2+26.3** is included through Loom
nested-JAR metadata, retaining its nested modules/licenses. It is pinned to
SHA-256 `2612a078a5daa06d9a14cabad40fe66de36b8cb22ee8cdf346d5d9894478c392`.

## Player installation

Put the portable mod JAR in `mods/`. RTX initialization additionally needs a published compatible native package for the current platform. The installer
selects exactly Linux/Windows and x86-64/ARM64 (`amd64`, `x86_64`, `aarch64`,
`arm64`). Unsupported hosts get an error without an x86 fallback. Pure ARM64 PE
machine `0xAA64` is required; ARM64EC `0xA641` is rejected.

Bundled archives are preferred. Their download URL may be omitted for fully bundled offline delivery. The manifest also permits an actual versioned HTTPS
fallback, pinned by SHA-256 and exact byte counts. It never fetches `latest` or
remote manifests, executes a download, or invokes a C/C++ compiler. Shader/cache
compilation is permitted. The verified cache is under `.rtx-remix-cache/` in
the game directory. Downloads use 10-second connect and 20-second read timeouts,
a five-minute streaming deadline, at most three redirects, 1 GiB compressed and
3 GiB expanded per platform. A new launch can retry a real failure; no background
retry loop is installed. Archive hashes are checked again before cache reuse.
A complete bundled JAR and a verified cache work offline.

An OS file lock serializes installation in one game directory. The installer rejects path traversal, absolute/Windows paths, aliases,
duplicate entries, symlinks, special permissions, oversized archives and
expansion bombs. Exact file inventories, metadata, ELF/PE machines and JVM JNI
descriptors are checked before SDK publication. New files are written into a
fresh staging directory. A failed SDK rename restores the previous SDK before
reporting failure; the marker is written only after installation completes and
binds mod version, platform, C API, bridge ABI and bundle digest.

The existing shipped-file history continues to preserve author edits under
`rtx-remix/`. `rtx.conf`, the SDK, and the two shipped DDS files are mod-owned;
other files in the shared `textures/` directory remain untouched. Settings and
external resource packs are not deleted. Pixlli, Simplista and the optional
NVIDIA material-pack archive stay local and never enter this JAR. The renderer's
own assets and baseline textures remain included.

## Maintainer producer sequence

Only maintainers/CI compile native code. Builds must run one heavy job at a time
with the project's resource guard. No GPU work is part of packaging or the JNI
loader probe. On the shared development host, use `resource-run.sh compile`
for native compilation and `resource-run.sh build` plus the Gradle lock for
JVM tasks; never raise the configured memory/swap limits.

1. Finalize the runtime source/API with both owners. Record exact source
   revisions and copy the canonical `public/include/remix/remix_c.h` into the
   mod. Build the own runtime and all target dependencies against that source.
   Old NVIDIA API 0.16.2 runtime packages are invalid inputs.
2. Build the JNI bridge with the same copied header: explicit
   `compileNativeLinux`, `compileNativeLinuxArm64`, `compileNativeWin64`, or
   `compileNativeArm64`. Linux cross builds need target OpenGL development
   headers/libraries, not host binaries. Windows uses the JDK's **Windows** JNI
   headers; pure ARM64 uses the MSVC ARM64 toolset. ARM64EC is a different target.
   `./gradlew classes` generates current JNI headers under `build/generated/jni/`.
3. Stage a **real directory** with runtime and bridge, all required dependency
   libraries/resources and a `licenses/` tree. Do not stage through writable
   links into SDK caches. Preserve proprietary NVIDIA licensing; do not relicense
   or redistribute a standalone SDK as Apache-2.0. Include the exact miniz license.
4. Run the runtime/API compatibility gate on the actual producer result and
   record `build-provenance.json` with fields `platform`, `api`, `runtimeSource`,
   `headerSha256`, `runtimeSha256`, `bridgeSourceSha256`, `bridgeSha256`,
   `interfacePointers`, `cpuBaseline`, `compilerCommands`, and
   `apiCompatibilityTest: "passed"`.
   This is the producer's evidence assertion; merely writing this field does
   not run a compatibility test. Source/header hashes and binary identities are
   checked, but they do not replace runtime/hardware tests. The bridge source
   hash algorithm is shared by `NativeBundles.bridgeSourceHash` and the producer.
5. Audit and seal each platform with the standard-library-only producer:

   ```sh
   python3 packaging/native-bundles/produce.py produce \
     --platform linux64 --mod-source . --runtime-source /path/to/runtime \
     --payload /path/to/staged-payload --built-runtime /path/to/libremixapi.so \
     --built-bridge build/native/linux64/remixbridge.so \
     --provenance /path/to/build-provenance.json --jni-headers build/generated/jni \
     --license-inventory /path/to/license-assignments.json \
     --minimum-glibc 2.44 --minimum-vulkan '<validated-policy>' \
     --minimum-driver '<validated-policy>' --url '<actual-versioned-HTTPS-asset>' \
     --output /path/to/bundles
   ```

   The placeholders must be replaced by validated release facts. `produce`
   checks real ELF/PE machines, imports/exports, every current JNI symbol,
   the canonical API header, source/binary provenance, license inventory, CPU
   flags and ELF glibc symbol requirements. Missing dependencies fail. It uses
   `llvm-readobj` and emits a deterministic ZIP, `audit.json` with imports,
   exports/version requirements/JNI descriptors and a `.pin.properties` file.
   `license-assignments.json` maps every binary's relative path to a nonempty
   list of included `licenses/...` texts, including the applicable proprietary
   SDK notices. The installer verifies these assignments as well as checksums.
   This records the producer's reviewed licensing; it does not infer legal
   redistribution rights from a filename. It never fetches or compiles anything. Library availability is not proof of
   compatibility: the Linux/Windows AArch64 DLSS SDK payloads have been inspected,
   but a matching own runtime still needs producing.
6. Collect all four ZIPs/pins. Run `produce.py freeze --pins /path/to/bundles
   --manifest packaging/native-bundles/manifest.properties`. Cross-platform
   source/API hashes must agree. Review and, when authorized, publish genuine
   immutable assets with their associated licenses. No release publication is
   performed by this change, and SSH push authorization does not itself supply
   a release-upload API token.
7. Run `./gradlew verifyNativeDistribution -PnativeBundlesDir=/path/to/bundles`, then the manual
   `native distribution gate` CI workflow. It covers all four machine types,
   current JVM descriptors, and CPU-only JNI loading on matching Java 25
   runners. The final job runs the unqualified universal build. A pending
   manifest fails this gate. It does not perform GPU or game tests. Run the
   release's separate hardware/driver tests before making support claims.

The current change deliberately supplies neither forged binaries nor invented
release URLs. The remaining release work is the concrete four-platform native
producer/API/hardware validation and publication described above.
