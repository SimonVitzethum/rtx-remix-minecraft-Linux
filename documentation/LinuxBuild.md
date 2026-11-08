# Native Linux Build

The Remix runtime builds and runs natively on Linux x64; no Proton or Wine is
involved. The build is the same Meson project as the Windows one, but compiles
with GCC and links the runtime as `libremixapi.so`.

## Configure And Build

From the checkout root, after `git submodule update --init --recursive`:

```bash
meson setup _CompLinuxRelease .. -Denable_rtxio=false
ninja -j6
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

- `GetInteropInfo` hands out the interop image and the two timeline
  semaphores as FDs, duplicating a fresh FD per call; the client owns it. GL
  imports them with `GL_EXT_memory_object_fd` and `GL_EXT_semaphore_fd`.
- `interopFlipY` flips the presented image vertically.

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

Both log to stderr/stdout and write no files.

## Known Limitations

- DLSS frame generation and neural reconstruction, XeSS, NRC, Reflex,
  Aftermath, and NVAPI are compiled out on POSIX (`REMIX_NO_NRC`,
  `REMIX_NO_XESS`, `REMIX_NO_NVAPI`, `REMIX_NO_REFLEX`,
  `REMIX_NO_AFTERMATH`).
- RTXIO is disabled (`-Denable_rtxio=false`).
- The build targets Linux x64 only.
