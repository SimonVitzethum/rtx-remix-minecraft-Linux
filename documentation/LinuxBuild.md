# Native Linux Build

The Remix runtime builds and runs natively on Linux x64; no Proton or Wine is
involved. The build is the same Meson project as the Windows one, but compiles
with GCC and links the runtime as `libremixapi.so`.

## Configure And Build

From the checkout root, after `git submodule update --init --recursive`:

```bash
meson setup _CompLinuxRelease -Dbuildtype=release -Denable_rtxio=false -Ddownload_apics=false
ninja -C _CompLinuxRelease -j1 src/d3d9/libremixapi.so
```

- The `slangc` Meson option selects the shader compiler. On non-Windows hosts
  it defaults to the native Linux slangc release at
  `../dxvk-tools/slang-2026/bin/slangc` (Slang 2026.21), relative to the
  dxvk-remix checkout; pass `-Dslangc=/path/to/slangc` to override.
- `-Denable_rtxio=false` disables the RTXIO dependency, which has no Linux
  binaries.
- glslang (`glslangValidator`) and spirv-val (`spirv-val`) come from the
  system.
- The output library is `_CompLinuxRelease/src/d3d9/libremixapi.so`: the
  d3d9 target names its shared library `remixapi` on Linux (d3d9 on Windows).
- Start with one native compilation job on memory-constrained systems. Increase
  parallelism only after measuring compiler and shader-worker memory use.

Native ELF builds embed Slang SPIR-V through assembler `incbin` sections.
The generated declarations retain the array extent and byte count, but GCC
does not build syntax trees for millions of integer literals in shader variant
matrices. Hidden COMDAT symbols deduplicate data included by multiple source
files. The header includes a content digest so compiler caches invalidate when
shader bytes change. Other targets use the generated integer arrays.

After changing `scripts-common/shader_xxd.py`, refresh existing cached Slang
headers without recompiling the shaders:

```bash
for shader in _CompLinuxRelease/src/dxvk/rtx_shaders/*.spv; do
  python3 scripts-common/shader_xxd.py -i "$shader" -o "${shader%.spv}.h"
done
```

## Dependencies

- OpenUSD built without Python and imaging (`dxvk-deps/usd-install`), with its
  plugin descriptors under `usd/` beside the library.
- NRD 4.14.3 built natively (`dxvk-deps/NRD/build_linux/libNRD.so`), placed
  beside `libremixapi.so`.
- DLSS Linux libraries from `submodules/dlss/lib/Linux_x86_64/rel`
  (`libnvidia-ngx-dlss*.so`).
- glslang, spirv-val, and Slang, as above.

## Exported Functions

The Linux runtime exports the Remix C API plus these Linux helpers:

| Function | Purpose |
|---|---|
| `remixapi_linux_SetWaylandDisplay(void* display)` | Selects Wayland presentation; `display` is the client's `wl_display` |
| `remixapi_linux_SetWindowSize(uint32_t width, uint32_t height)` | Reports the window size; Wayland surfaces have no queryable size |
| `remixapi_linux_InputMouseMove(float x, float y)` | Forwards mouse motion to the overlay |
| `remixapi_linux_InputMouseButton(int button, int down)` | Mouse buttons: 0 left, 1 right, 2 middle, 3 and 4 extra |
| `remixapi_linux_InputMouseWheel(float dx, float dy)` | Mouse wheel |
| `remixapi_linux_InputKey(int vk, int down)` | Key events, numbered with Windows virtual keys |
| `remixapi_linux_InputChar(uint32_t codepoint)` | Text input |
| `remixapi_linux_InputFocus(int focused)` | Focus changes; clears key state when unfocused |

The input functions feed an ImGui platform backend
(`src/dxvk/imgui/imgui_impl_linux_stub.cpp`) that also backs
`GetAsyncKeyState`, `GetKeyState`, and `GetCursorPos`.

## Interop Model

The runtime and the game exchange the rendered image over opaque file
descriptors:

- `GetInteropInfo` reports opaque image-memory and timeline-semaphore FDs.
  The client imports each reported object once and consumes its FD. Repeated
  reports of the same object retain the descriptor number; they are not new
  descriptors to import. GL uses `GL_EXT_memory_object_fd` and
  `GL_EXT_semaphore_fd`.
- `interopFlipY` flips the presented image vertically.

Both exported color and optional depth use exclusive ownership and remain in
`GENERAL` at the device boundary. Submit copies the outputs, releases their
graphics-queue ownership to `VK_QUEUE_FAMILY_EXTERNAL`, and signals `dxvkDone`.
The client waits for that value and acquires both images before its first use.
After composition, weather and the final color copy, it releases both images
and signals `clientDone`. Present waits for that value and acquires them before
runtime use. A menu frame still needs this handoff when it skips world rendering.
The CPU-visible submit completion does not replace either GPU semaphore wait.

### Neural rendering synchronization

NGX receives application-supplied Vulkan procedure resolvers on Linux. They
retain the loader's availability checks and dispatch, while wrapping SDK image
clears and buffer fills with range-specific dependencies from all earlier accesses
to transfer writes. Each dependency is recorded immediately before the write inside the SDK command
sequence; a barrier outside `EvaluateFeature` cannot order two internal commands.
Validation remains in the dispatch chain. The vendor binaries are unmodified.

Frame generation keeps its depth and motion-vector input ring until the
presenter's completion timeline releases the corresponding input sets. Each
batch signals once after all its generated frames, including discarded jobs.
Completion values are ordered across ordinary and frame-generation queues when
presentation modes change. Presentation binary semaphores belong to acquired
swapchain images, whose reacquisition proves the earlier presentation wait has
finished.

## Presentation Modes

- X11: the window handle passed to startup is an X11 window id; the runtime
  presents through a dedicated XCB connection.
- Wayland: call `remixapi_linux_SetWaylandDisplay` with the `wl_display` and
  pass the `wl_surface` as the window handle.
- Headless: pass a null `hwnd`; the back buffer size comes from
  `REMIX_HEADLESS_WIDTH`/`REMIX_HEADLESS_HEIGHT` (default 1280x720).

## Pipeline Cache

The Vulkan pipeline cache lives at `remix-pipeline.cache` beside the runtime
library, so a standalone precompile run and the game share it regardless of
their working directories. `REMIX_PIPELINE_CACHE_FILE` overrides the location.

## Debug Environment Variables

- `REMIX_DUMP_INTEROP=1` logs pixel statistics of every 30th rendered frame.
- `REMIX_DUMP_PRESENT=1` logs pixel statistics of every 20th presented frame.
- `RTX_TEXTURE_STAGING_DIAGNOSTICS=1` logs staging pool allocation, idle resize
  and release, image admission, obsolete-request cancellation and dirty-memory
  reconciliation. Set it before process startup. Pool events identify the pool
  by its byte budget. Releases occur after queued leases and GPU-use references
  have retired. Upload recording times are CPU measurements, not GPU timings.

Both log to stderr/stdout and write no files.

## Known Limitations

- XeSS, NRC, Reflex, Aftermath, and NVAPI are compiled out on POSIX (`REMIX_NO_NRC`,
  `REMIX_NO_XESS`, `REMIX_NO_NVAPI`, `REMIX_NO_REFLEX`,
  `REMIX_NO_AFTERMATH`).
- RTXIO is disabled (`-Denable_rtxio=false`).
- The build targets Linux x64 only.

### Native texture file notifications

The native Linux file watcher uses inotify for asset directories and registered
file-backed texture parents. It coalesces close-write and rename events, follows
new subdirectories without following directory symlinks, and re-registers watches
after an event queue overflow. Watching a parent keeps atomic file replacement
working. Texture dispatch is synchronized with texture-manager teardown; generic
asset callbacks run outside the collector mutex. Enable texture reloads explicitly
with `DXVK_TEXTURES_HOTRELOAD=1`. Java resource reload and native file-backed asset
reload are separate paths. `test_file_watch_linux` exercises the production event
collector with real temporary files, including replacement and subtree moves.

### Passive submission diagnostics

`REMIX_TRACE_SUBMIT=1` measures the existing CPU submission path without adding
GPU waits. Every 120 interop submissions it logs mean and maximum milliseconds
for six phases: 0 queues `EndFrame`, 1 records interop copies, 2 flushes the CS
chunk, 3 waits for the CS thread, 4 waits for queue submission and the presenter's
existing synchronization, and 5 performs an explicitly requested device-idle wait.
`SubmitTraceSlow` records all six phases for submissions lasting at least 100 ms.
`ApiSubmit` separates retained-instance command emission from the complete interop
call, including device-lock acquisition before its individual phase timers.
`SubmissionWait` separates time waiting for queued submissions from presenter
synchronization when their combined duration reaches 100 ms. `DriverSubmit`
records existing driver submission calls taking at least 100 ms.
`SubmissionRetire` separates existing fence waits from command-list resource
retirement when their combined duration reaches 100 ms. These are CPU wall
durations and include host scheduling or memory pressure. Frame identifiers
are runtime present counters and can advance for generated frames.

`StagingCapacity` counts general DXVK staging requests, allocation bytes, wraps,
large dedicated spills, maximum request and offset, and allocation CPU time.
These counters are cumulative per CS thread, not live/in-flight byte estimates.
This allocator creates referenced chunks on wrap without forcing a flush or
wait; its zero forced-operation fields describe this allocator only. Texture
streaming uses its separate bounded lease pool and unchanged byte budgets.

`SubmitMemory` reports allocated and assigned bytes per runtime Vulkan heap,
including buffer, acceleration-structure and replacement-geometry categories.
Assigned memory includes references held by in-flight work. These counters do
not include the client's Vulkan device, Java heap, driver host allocations or
process RSS. Correlate log timestamps with process and cgroup memory samples.
The measurements are CPU wall durations, not isolated GPU execution times.

`REMIX_PROFILE_SUBMIT=1` is a separate diagnostic that adds a CPU wait for the
interop completion semaphore. Leave it unset for passive measurements.
