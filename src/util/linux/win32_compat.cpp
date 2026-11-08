// Native Linux implementations of the Win32 API subset declared in include/native/windows/remix_win32.h.
// Included first: windows_base.h defines `interface`, which the Wayland headers use as a parameter name.
#include <wayland-client-core.h>
#include <map>
#include <mutex>
#include <algorithm>
#include <cstring>

#include <windows.h>

#include <dlfcn.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/sysinfo.h>
#include <algorithm>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <locale>
#include <codecvt>
#include <thread>
#include <mutex>
#include <atomic>

static thread_local DWORD g_lastError = 0;

static std::string narrow(const wchar_t* w) {
  std::string out;
  for (; w && *w; ++w) {
    uint32_t c = (uint32_t)*w;
    if (c < 0x80) { out += (char)c; }
    else if (c < 0x800) { out += (char)(0xC0 | (c >> 6)); out += (char)(0x80 | (c & 0x3F)); }
    else if (c < 0x10000) { out += (char)(0xE0 | (c >> 12)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
    else { out += (char)(0xF0 | (c >> 18)); out += (char)(0x80 | ((c >> 12) & 0x3F)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
  }
  return out;
}

HMODULE LoadLibraryA(const char* n) { return dlopen(n, RTLD_NOW | RTLD_LOCAL); }
HMODULE LoadLibraryW(const wchar_t* n) { return LoadLibraryA(narrow(n).c_str()); }
HMODULE LoadLibraryExW(const wchar_t* n, HANDLE, DWORD) { return LoadLibraryW(n); }
HMODULE LoadLibraryExA(const char* n, HANDLE, DWORD) { return LoadLibraryA(n); }
HMODULE GetModuleHandleA(const char* n) { return n ? dlopen(n, RTLD_NOW | RTLD_NOLOAD) : dlopen(nullptr, RTLD_NOW); }
HMODULE GetModuleHandleW(const wchar_t* n) { return n ? GetModuleHandleA(narrow(n).c_str()) : GetModuleHandleA(nullptr); }
BOOL GetModuleHandleExW(DWORD flags, const wchar_t* n, HMODULE* out) {
  if (flags & GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS) {
    Dl_info info;
    if (!dladdr((const void*)n, &info)) { return FALSE; }
    *out = dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
    return *out != nullptr;
  }
  *out = GetModuleHandleW(n);
  return *out != nullptr;
}
FARPROC GetProcAddress(HMODULE m, const char* n) { return (FARPROC)dlsym(m ? m : RTLD_DEFAULT, n); }
BOOL FreeLibrary(HMODULE m) { return m ? dlclose(m) == 0 : FALSE; }
DWORD GetModuleFileNameA(HMODULE m, char* buf, DWORD size) {
  std::string path;
  if (m == nullptr) {
    char tmp[4096]; ssize_t n = readlink("/proc/self/exe", tmp, sizeof(tmp) - 1);
    if (n > 0) { path.assign(tmp, n); }
  } else {
    Dl_info info;
    if (dladdr(dlsym(m, "remixapi_InitializeLibrary"), &info) && info.dli_fname) { path = info.dli_fname; }
  }
  if (size == 0) { return 0; }
  strncpy(buf, path.c_str(), size - 1); buf[size - 1] = 0;
  return (DWORD)strlen(buf);
}
DWORD GetModuleFileNameW(HMODULE m, wchar_t* buf, DWORD size) {
  char tmp[4096]; DWORD n = GetModuleFileNameA(m, tmp, sizeof(tmp));
  DWORD i = 0; for (; i < n && i + 1 < size; ++i) { buf[i] = (unsigned char)tmp[i]; }
  if (size) { buf[i] = 0; }
  return i;
}
DWORD GetLastError() { return g_lastError; }
void SetLastError(DWORD e) { g_lastError = e; }
DWORD GetCurrentThreadId() { return (DWORD)gettid(); }
DWORD GetCurrentProcessId() { return (DWORD)getpid(); }
HANDLE GetCurrentProcess() { return (HANDLE)(LONG_PTR)-1; }
HANDLE GetCurrentThread() { return (HANDLE)(LONG_PTR)-2; }
void YieldProcessor() { __builtin_ia32_pause(); }
void Sleep(DWORD ms) { struct timespec ts{ (time_t)(ms / 1000), (long)(ms % 1000) * 1000000L }; nanosleep(&ts, nullptr); }
BOOL SwitchToThread() { return sched_yield() == 0; }
void DebugBreak() { __builtin_trap(); }
BOOL IsDebuggerPresent() { return FALSE; }
void OutputDebugStringA(const char* s) { if (s) { fputs(s, stderr); } }
void OutputDebugStringW(const wchar_t* s) { OutputDebugStringA(narrow(s).c_str()); }

int MultiByteToWideChar(UINT, DWORD, const char* src, int srcLen, wchar_t* dst, int dstLen) {
  if (srcLen < 0) { srcLen = (int)strlen(src) + 1; }
  std::wstring out;
  for (int i = 0; i < srcLen;) {
    unsigned char c = (unsigned char)src[i];
    uint32_t cp; int extra;
    if (c < 0x80) { cp = c; extra = 0; } else if ((c >> 5) == 6) { cp = c & 0x1F; extra = 1; }
    else if ((c >> 4) == 14) { cp = c & 0x0F; extra = 2; } else if ((c >> 3) == 30) { cp = c & 0x07; extra = 3; } else { cp = c; extra = 0; }
    for (int k = 1; k <= extra && i + k < srcLen; ++k) { cp = (cp << 6) | ((unsigned char)src[i + k] & 0x3F); }
    i += 1 + extra;
    out += (wchar_t)cp;
  }
  if (dstLen == 0) { return (int)out.size(); }
  // Like Windows, fill as much of a too small buffer as fits and report the failure.
  const size_t count = std::min((size_t)dstLen, out.size());
  memcpy(dst, out.data(), count * sizeof(wchar_t));
  return (int)out.size() > dstLen ? 0 : (int)out.size();
}
int WideCharToMultiByte(UINT, DWORD, const wchar_t* src, int srcLen, char* dst, int dstLen, const char*, BOOL*) {
  std::wstring w = srcLen < 0 ? std::wstring(src) + L'\0' : std::wstring(src, srcLen);
  std::string out;
  for (wchar_t wc : w) { wchar_t t[2] = { wc, 0 }; if (wc == 0) { out += '\0'; } else { out += narrow(t); } }
  if (dstLen == 0) { return (int)out.size(); }
  const size_t count = std::min((size_t)dstLen, out.size());
  memcpy(dst, out.data(), count);
  return (int)out.size() > dstLen ? 0 : (int)out.size();
}

DWORD GetEnvironmentVariableA(const char* n, char* buf, DWORD size) {
  const char* v = getenv(n);
  if (!v) { SetLastError(ERROR_ENVVAR_NOT_FOUND); return 0; }
  DWORD len = (DWORD)strlen(v);
  if (len + 1 > size) { return len + 1; }
  memcpy(buf, v, len + 1);
  return len;
}
DWORD GetEnvironmentVariableW(const wchar_t* n, wchar_t* buf, DWORD size) {
  char tmp[8192]; DWORD r = GetEnvironmentVariableA(narrow(n).c_str(), tmp, sizeof(tmp));
  if (r == 0 || r >= sizeof(tmp)) { return r; }
  int len = MultiByteToWideChar(CP_UTF8, 0, tmp, (int)r, nullptr, 0);
  if ((DWORD)len + 1 > size) { return len + 1; }
  MultiByteToWideChar(CP_UTF8, 0, tmp, (int)r, buf, len); buf[len] = 0;
  return len;
}
BOOL SetEnvironmentVariableA(const char* n, const char* v) { return v ? setenv(n, v, 1) == 0 : unsetenv(n) == 0; }
BOOL SetEnvironmentVariableW(const wchar_t* n, const wchar_t* v) { return SetEnvironmentVariableA(narrow(n).c_str(), v ? narrow(v).c_str() : nullptr); }
DWORD GetCurrentDirectoryA(DWORD size, char* buf) { return getcwd(buf, size) ? (DWORD)strlen(buf) : 0; }
DWORD GetTempPathA(DWORD size, char* buf) { const char* t = "/tmp/"; strncpy(buf, t, size); return (DWORD)strlen(t); }
DWORD GetTempPathW(DWORD size, wchar_t* buf) { const wchar_t* t = L"/tmp/"; wcsncpy(buf, t, size); return (DWORD)wcslen(t); }
DWORD GetFileAttributesA(const char* p) { struct stat st; if (stat(p, &st) != 0) { return INVALID_FILE_ATTRIBUTES; } return S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL; }
DWORD GetFileAttributesW(const wchar_t* p) { return GetFileAttributesA(narrow(p).c_str()); }
BOOL CreateDirectoryA(const char* p, void*) { return mkdir(p, 0777) == 0; }
BOOL CreateDirectoryW(const wchar_t* p, void*) { return CreateDirectoryA(narrow(p).c_str(), nullptr); }
BOOL DeleteFileA(const char* p) { return unlink(p) == 0; }
BOOL DeleteFileW(const wchar_t* p) { return DeleteFileA(narrow(p).c_str()); }
BOOL CloseHandle(HANDLE h);
BOOL QueryPerformanceCounter(LARGE_INTEGER* c) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); c->QuadPart = (LONGLONG)ts.tv_sec * 1000000000LL + ts.tv_nsec; return TRUE; }
BOOL QueryPerformanceFrequency(LARGE_INTEGER* f) { f->QuadPart = 1000000000LL; return TRUE; }
ULONGLONG GetTickCount64() { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (ULONGLONG)ts.tv_sec * 1000 + ts.tv_nsec / 1000000; }
DWORD GetTickCount() { return (DWORD)GetTickCount64(); }
void GetSystemTimeAsFileTime(FILETIME* ft) {
  struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
  ULONGLONG t = ((ULONGLONG)ts.tv_sec + 11644473600ULL) * 10000000ULL + ts.tv_nsec / 100;
  ft->dwLowDateTime = (DWORD)t; ft->dwHighDateTime = (DWORD)(t >> 32);
}
static void fillSystemTime(SYSTEMTIME* st, struct tm* tmv, long ms) {
  st->wYear = tmv->tm_year + 1900; st->wMonth = tmv->tm_mon + 1; st->wDayOfWeek = tmv->tm_wday; st->wDay = tmv->tm_mday;
  st->wHour = tmv->tm_hour; st->wMinute = tmv->tm_min; st->wSecond = tmv->tm_sec; st->wMilliseconds = ms;
}
void GetLocalTime(SYSTEMTIME* st) { struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); struct tm t; localtime_r(&ts.tv_sec, &t); fillSystemTime(st, &t, ts.tv_nsec / 1000000); }
void GetSystemTime(SYSTEMTIME* st) { struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); struct tm t; gmtime_r(&ts.tv_sec, &t); fillSystemTime(st, &t, ts.tv_nsec / 1000000); }
void GetSystemInfo(SYSTEM_INFO* si) {
  memset(si, 0, sizeof(*si)); si->wProcessorArchitecture = PROCESSOR_ARCHITECTURE_AMD64; si->dwPageSize = (DWORD)sysconf(_SC_PAGESIZE);
  si->dwNumberOfProcessors = (DWORD)sysconf(_SC_NPROCESSORS_ONLN); si->dwAllocationGranularity = si->dwPageSize;
}
void GetNativeSystemInfo(SYSTEM_INFO* si) { GetSystemInfo(si); }
BOOL GlobalMemoryStatusEx(MEMORYSTATUSEX* ms) {
  struct sysinfo si; if (sysinfo(&si) != 0) { return FALSE; }
  ms->ullTotalPhys = (ULONGLONG)si.totalram * si.mem_unit; ms->ullAvailPhys = (ULONGLONG)si.freeram * si.mem_unit;
  ms->ullTotalPageFile = ms->ullTotalPhys; ms->ullAvailPageFile = ms->ullAvailPhys; ms->ullTotalVirtual = ms->ullTotalPhys; ms->ullAvailVirtual = ms->ullAvailPhys;
  ms->dwMemoryLoad = (DWORD)(100 - (ms->ullAvailPhys * 100 / (ms->ullTotalPhys ? ms->ullTotalPhys : 1)));
  return TRUE;
}

BOOL GetMonitorInfoW(HMONITOR, MONITORINFO* mi) { mi->rcMonitor = { 0, 0, 1920, 1080 }; mi->rcWork = mi->rcMonitor; mi->dwFlags = 1; if (mi->cbSize >= sizeof(MONITORINFOEXW)) { wcscpy(((MONITORINFOEXW*)mi)->szDevice, L"DISPLAY1"); } return TRUE; }
BOOL EnumDisplaySettingsW(const wchar_t*, DWORD, DEVMODEW* dm) { memset(dm, 0, sizeof(*dm)); dm->dmPelsWidth = 1920; dm->dmPelsHeight = 1080; dm->dmBitsPerPel = 32; dm->dmDisplayFrequency = 60; dm->dmSize = sizeof(*dm); return TRUE; }
HMONITOR MonitorFromWindow(HWND, DWORD) { return (HMONITOR)(uintptr_t)1; }
HMONITOR MonitorFromPoint(POINT, DWORD) { return (HMONITOR)(uintptr_t)1; }
BOOL EnumDisplayMonitors(HDC, const RECT*, MONITORENUMPROC cb, LPARAM p) { RECT r{ 0, 0, 1920, 1080 }; return cb((HMONITOR)(uintptr_t)1, nullptr, &r, p); }
// ---- XCB: the window handle on Linux is an X11 window id ----
namespace {
  struct XcbApi {
    struct GeometryCookie { unsigned int sequence; };
    struct GeometryReply { uint8_t response_type, depth; uint16_t sequence; uint32_t length; uint32_t root; int16_t x, y; uint16_t width, height, border_width; };
    void* (*connect)(const char*, int*) = nullptr;
    int (*hasError)(void*) = nullptr;
    GeometryCookie (*getGeometry)(void*, uint32_t) = nullptr;
    GeometryReply* (*getGeometryReply)(void*, GeometryCookie, void**) = nullptr;
    void* connection = nullptr;
  };

  XcbApi& xcbApi() {
    static XcbApi api;
    static std::once_flag once;
    std::call_once(once, [] {
      void* lib = dlopen("libxcb.so.1", RTLD_NOW | RTLD_GLOBAL);
      if (!lib) { return; }
      api.connect = (void* (*)(const char*, int*))dlsym(lib, "xcb_connect");
      api.hasError = (int (*)(void*))dlsym(lib, "xcb_connection_has_error");
      api.getGeometry = (XcbApi::GeometryCookie (*)(void*, uint32_t))dlsym(lib, "xcb_get_geometry");
      api.getGeometryReply = (XcbApi::GeometryReply* (*)(void*, XcbApi::GeometryCookie, void**))dlsym(lib, "xcb_get_geometry_reply");
      if (api.connect && api.hasError) {
        void* c = api.connect(nullptr, nullptr);
        if (c && !api.hasError(c)) { api.connection = c; }
      }
    });
    return api;
  }

  bool queryWindowSize(HWND hwnd, uint32_t& w, uint32_t& h) {
    XcbApi& api = xcbApi();
    if (!api.connection || !api.getGeometry || !api.getGeometryReply || (uintptr_t)hwnd <= 1) { return false; }
    auto* reply = api.getGeometryReply(api.connection, api.getGeometry(api.connection, (uint32_t)(uintptr_t)hwnd), nullptr);
    if (!reply) { return false; }
    w = reply->width; h = reply->height;
    free(reply);
    return true;
  }
}

void* RemixGetXcbConnection() { return xcbApi().connection; }

// ---- Wayland: the window handle is a wl_surface*, the client passes its wl_display and the surface size ----
namespace {
  std::atomic<void*> g_waylandDisplay { nullptr };
  std::atomic<uint32_t> g_waylandWidth { 1280 };
  std::atomic<uint32_t> g_waylandHeight { 720 };
}

void* RemixGetWaylandDisplay() { return g_waylandDisplay.load(); }

// ---- Wayland subsurface ----
// A wl_surface that already has an OpenGL (EGL) window cannot also be a Vulkan swap chain target, so Remix presents into
// a child subsurface of the client's surface. It is created on a private event queue so the client's own dispatching
// is not disturbed. The wayland-client library is loaded at runtime, no link dependency is needed.

namespace {
  struct WaylandApi {
    void* lib = nullptr;
    wl_event_queue* (*display_create_queue)(wl_display*) = nullptr;
    void* (*proxy_create_wrapper)(void*) = nullptr;
    void (*proxy_set_queue)(wl_proxy*, wl_event_queue*) = nullptr;
    void (*proxy_wrapper_destroy)(void*) = nullptr;
    int (*display_roundtrip_queue)(wl_display*, wl_event_queue*) = nullptr;
    int (*display_flush)(wl_display*) = nullptr;
    wl_proxy* (*proxy_marshal_flags)(wl_proxy*, uint32_t, const wl_interface*, uint32_t, uint32_t, ...) = nullptr;
    int (*proxy_add_listener)(wl_proxy*, void (**)(void), void*) = nullptr;
    uint32_t (*proxy_get_version)(wl_proxy*) = nullptr;
    const wl_interface* registry_if = nullptr;
    const wl_interface* compositor_if = nullptr;
    const wl_interface* subcompositor_if = nullptr;
    const wl_interface* surface_if = nullptr;
    const wl_interface* subsurface_if = nullptr;
    const wl_interface* region_if = nullptr;
    bool ok = false;
  };

  WaylandApi& waylandApi() {
    static WaylandApi api = [] {
      WaylandApi a;
      a.lib = dlopen("libwayland-client.so.0", RTLD_NOW | RTLD_GLOBAL);
      if (!a.lib) { return a; }
      auto sym = [&](const char* name) { return dlsym(a.lib, name); };
      a.display_create_queue = reinterpret_cast<decltype(a.display_create_queue)>(sym("wl_display_create_queue"));
      a.proxy_create_wrapper = reinterpret_cast<decltype(a.proxy_create_wrapper)>(sym("wl_proxy_create_wrapper"));
      a.proxy_set_queue = reinterpret_cast<decltype(a.proxy_set_queue)>(sym("wl_proxy_set_queue"));
      a.proxy_wrapper_destroy = reinterpret_cast<decltype(a.proxy_wrapper_destroy)>(sym("wl_proxy_wrapper_destroy"));
      a.display_roundtrip_queue = reinterpret_cast<decltype(a.display_roundtrip_queue)>(sym("wl_display_roundtrip_queue"));
      a.display_flush = reinterpret_cast<decltype(a.display_flush)>(sym("wl_display_flush"));
      a.proxy_marshal_flags = reinterpret_cast<decltype(a.proxy_marshal_flags)>(sym("wl_proxy_marshal_flags"));
      a.proxy_add_listener = reinterpret_cast<decltype(a.proxy_add_listener)>(sym("wl_proxy_add_listener"));
      a.proxy_get_version = reinterpret_cast<decltype(a.proxy_get_version)>(sym("wl_proxy_get_version"));
      a.registry_if = static_cast<const wl_interface*>(sym("wl_registry_interface"));
      a.compositor_if = static_cast<const wl_interface*>(sym("wl_compositor_interface"));
      a.subcompositor_if = static_cast<const wl_interface*>(sym("wl_subcompositor_interface"));
      a.surface_if = static_cast<const wl_interface*>(sym("wl_surface_interface"));
      a.subsurface_if = static_cast<const wl_interface*>(sym("wl_subsurface_interface"));
      a.region_if = static_cast<const wl_interface*>(sym("wl_region_interface"));
      a.ok = a.display_create_queue && a.proxy_create_wrapper && a.proxy_set_queue && a.proxy_wrapper_destroy
          && a.display_roundtrip_queue && a.display_flush && a.proxy_marshal_flags && a.proxy_add_listener
          && a.proxy_get_version && a.registry_if && a.compositor_if && a.subcompositor_if && a.surface_if
          && a.subsurface_if && a.region_if;
      return a;
    }();
    return api;
  }

  struct WaylandGlobals {
    wl_proxy* compositor = nullptr;
    wl_proxy* subcompositor = nullptr;
    uint32_t compositorName = 0, compositorVersion = 0;
    uint32_t subcompositorName = 0, subcompositorVersion = 0;
  };

  void waylandGlobal(void* data, wl_proxy*, uint32_t name, const char* iface, uint32_t version) {
    auto* g = static_cast<WaylandGlobals*>(data);
    if (!strcmp(iface, "wl_compositor")) { g->compositorName = name; g->compositorVersion = std::min(version, 4u); }
    else if (!strcmp(iface, "wl_subcompositor")) { g->subcompositorName = name; g->subcompositorVersion = 1; }
  }
  void waylandGlobalRemove(void*, wl_proxy*, uint32_t) { }

  std::mutex g_waylandChildMutex;
  std::map<void*, void*> g_waylandChildren;
}

void* RemixGetWaylandChildSurface(void* parentSurface) {
  std::lock_guard<std::mutex> lock(g_waylandChildMutex);
  auto it = g_waylandChildren.find(parentSurface);
  if (it != g_waylandChildren.end()) { return it->second; }

  void* result = parentSurface;
  WaylandApi& api = waylandApi();
  auto* display = static_cast<wl_display*>(g_waylandDisplay.load());
  if (api.ok && display != nullptr) {
    wl_event_queue* queue = api.display_create_queue(display);
    void* wrapper = api.proxy_create_wrapper(display);
    api.proxy_set_queue(static_cast<wl_proxy*>(wrapper), queue);

    // wl_display.get_registry is request 1.
    wl_proxy* registry = api.proxy_marshal_flags(static_cast<wl_proxy*>(wrapper), 1, api.registry_if,
      api.proxy_get_version(static_cast<wl_proxy*>(wrapper)), 0, nullptr);
    WaylandGlobals globals;
    static void (*listener[2])(void) = {
      reinterpret_cast<void (*)(void)>(waylandGlobal), reinterpret_cast<void (*)(void)>(waylandGlobalRemove) };
    api.proxy_add_listener(registry, listener, &globals);
    api.display_roundtrip_queue(display, queue);

    auto bind = [&](uint32_t name, const wl_interface* iface, uint32_t version) {
      // wl_registry.bind is request 0, with (name, interface name, version, new id).
      return api.proxy_marshal_flags(registry, 0, iface, version, 0, name, iface->name, version, nullptr);
    };
    fprintf(stderr, "Remix Wayland globals: compositor=%u subcompositor=%u\n", globals.compositorName, globals.subcompositorName);
    if (globals.compositorName && globals.subcompositorName) {
      wl_proxy* compositor = bind(globals.compositorName, api.compositor_if, globals.compositorVersion);
      wl_proxy* subcompositor = bind(globals.subcompositorName, api.subcompositor_if, globals.subcompositorVersion);
      // wl_compositor.create_surface is request 0, create_region is request 1.
      wl_proxy* child = api.proxy_marshal_flags(compositor, 0, api.surface_if, api.proxy_get_version(compositor), 0, nullptr);
      wl_proxy* emptyRegion = api.proxy_marshal_flags(compositor, 1, api.region_if, api.proxy_get_version(compositor), 0, nullptr);
      // wl_subcompositor.get_subsurface is request 1, wl_subsurface.set_desync is request 5.
      wl_proxy* sub = api.proxy_marshal_flags(subcompositor, 1, api.subsurface_if, api.proxy_get_version(subcompositor), 0,
        nullptr, child, static_cast<wl_proxy*>(parentSurface));
      api.proxy_marshal_flags(sub, 5, nullptr, api.proxy_get_version(sub), 0);
      // wl_surface.set_input_region is request 5 and commit is request 6: pointer input stays with the parent.
      api.proxy_marshal_flags(child, 5, nullptr, api.proxy_get_version(child), 0, emptyRegion);
      api.proxy_marshal_flags(child, 6, nullptr, api.proxy_get_version(child), 0);
      api.display_roundtrip_queue(display, queue);
      result = child;
    }
    api.proxy_wrapper_destroy(wrapper);
  }

  fprintf(stderr, "Remix Wayland subsurface: api=%d display=%p parent=%p result=%p\n", (int) api.ok, (void*) display, parentSurface, result);
  g_waylandChildren[parentSurface] = result;
  return result;
}

extern "C" {
  // Selects Wayland presentation. 'display' is the client's wl_display, the window handle given to Startup must be its wl_surface.
  __attribute__((visibility("default"))) void remixapi_linux_SetWaylandDisplay(void* display) { g_waylandDisplay = display; }
  // Wayland surfaces have no queryable size, the client reports it whenever it changes.
  __attribute__((visibility("default"))) void remixapi_linux_SetWindowSize(uint32_t width, uint32_t height) {
    if (width > 0 && height > 0) { g_waylandWidth = width; g_waylandHeight = height; }
  }
}

BOOL GetClientRect(HWND hwnd, RECT* r) {
  uint32_t w = 1920, h = 1080;
  if (g_waylandDisplay.load() != nullptr) {
    w = g_waylandWidth; h = g_waylandHeight;
  } else {
    queryWindowSize(hwnd, w, h);
  }
  *r = { 0, 0, (LONG)w, (LONG)h };
  return TRUE;
}
BOOL GetWindowRect(HWND hwnd, RECT* r) { return GetClientRect(hwnd, r); }
BOOL IsWindow(HWND h) { uint32_t w, h2; return h != nullptr && (g_waylandDisplay.load() != nullptr || (uintptr_t)h <= 1 || queryWindowSize(h, w, h2)); }
int GetSystemMetrics(int i) { return i == SM_CXSCREEN ? 1920 : i == SM_CYSCREEN ? 1080 : 0; }
HWND GetDesktopWindow() { return (HWND)(uintptr_t)1; }
BOOL AllocateLocallyUniqueId(LUID* l) { static uint64_t n = 1000; uint64_t v = __atomic_fetch_add(&n, 1, __ATOMIC_RELAXED); l->LowPart = (DWORD)v; l->HighPart = (LONG)(v >> 32); return TRUE; }
BOOL DeviceIoControl(HANDLE, DWORD, void*, DWORD, void*, DWORD, DWORD*, void*) { return FALSE; }
LONG ChangeDisplaySettingsExW(const wchar_t*, DEVMODEW*, HWND, DWORD, void*) { return DISP_CHANGE_SUCCESSFUL; }
UINT RegisterWindowMessageA(const char*) { static UINT n = 0xC000; return n++; }
BOOL PostThreadMessageA(DWORD, UINT, WPARAM, LPARAM) { return FALSE; }
BOOL PostMessageA(HWND, UINT, WPARAM, LPARAM) { return FALSE; }
LRESULT SendMessageTimeoutA(HWND, UINT, WPARAM, LPARAM, UINT, UINT, DWORD_PTR*) { return 0; }
BOOL GetMessageA(MSG*, HWND, UINT, UINT) { return FALSE; }
BOOL PeekMessageA(MSG*, HWND, UINT, UINT, UINT) { return FALSE; }
BOOL TranslateMessage(const MSG*) { return FALSE; }
LRESULT DispatchMessageA(const MSG*) { return 0; }
DWORD GetThreadId(HANDLE) { return GetCurrentThreadId(); }
HANDLE CreateToolhelp32Snapshot(DWORD, DWORD) { return INVALID_HANDLE_VALUE; }
BOOL Process32FirstW(HANDLE, PROCESSENTRY32W*) { return FALSE; }
BOOL Process32NextW(HANDLE, PROCESSENTRY32W*) { return FALSE; }
HANDLE OpenProcess(DWORD, BOOL, DWORD) { return nullptr; }
DWORD GetModuleFileNameExW(HANDLE, HMODULE m, wchar_t* b, DWORD n) { return GetModuleFileNameW(m, b, n); }
BOOL PathRemoveFileSpecW(wchar_t* p) { wchar_t* s = wcsrchr(p, L'/'); if (!s) { return FALSE; } *s = 0; return TRUE; }
BOOL TerminateProcess(HANDLE, UINT c) { _exit(c); }
void ExitProcess(UINT c) { exit(c); }
HINSTANCE ShellExecuteA(HWND, const char*, const char*, const char*, const char*, int) { return nullptr; }
BOOL GetModuleHandleExA(DWORD f, const char* n, HMODULE* o) { if (f & GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS) { Dl_info i; if (!dladdr((const void*)n, &i)) return FALSE; *o = dlopen(i.dli_fname, RTLD_NOW | RTLD_NOLOAD); return *o != nullptr; } *o = GetModuleHandleA(n); return *o != nullptr; }

// ---- Handles: files are encoded as (fd + 1), events and mappings are heap objects ----
#include <sys/mman.h>
#include <mutex>
#include <map>
#include <condition_variable>

namespace {
  struct HandleObject { int kind; };
  struct EventObject : HandleObject {
    std::mutex m; std::condition_variable cv; bool signaled = false; bool manual = false;
    EventObject() { kind = 1; }
  };
  struct MappingObject : HandleObject {
    int fd = -1; size_t size = 0; bool owns = false;
    MappingObject() { kind = 2; }
  };
  constexpr uintptr_t kMaxFdHandle = 1u << 20;
  bool isFdHandle(HANDLE h) { uintptr_t v = (uintptr_t)h; return v > 0 && v < kMaxFdHandle; }
  int toFd(HANDLE h) { return (int)((uintptr_t)h - 1); }
  HANDLE fromFd(int fd) { return (HANDLE)(uintptr_t)(fd + 1); }
  std::mutex g_mapMutex;
  std::map<const void*, size_t> g_maps;
}

HANDLE CreateFileA(const char* path, DWORD access, DWORD, void*, DWORD disp, DWORD, HANDLE) {
  int flags = (access & GENERIC_WRITE) ? ((access & GENERIC_READ) ? O_RDWR : O_WRONLY) : O_RDONLY;
  if (disp == CREATE_ALWAYS) { flags |= O_CREAT | O_TRUNC; } else if (disp == OPEN_ALWAYS) { flags |= O_CREAT; } else if (disp == CREATE_NEW) { flags |= O_CREAT | O_EXCL; }
  int fd = open(path, flags | O_CLOEXEC, 0666);
  if (fd < 0) { SetLastError(errno == ENOENT ? ERROR_FILE_NOT_FOUND : ERROR_ACCESS_DENIED); return INVALID_HANDLE_VALUE; }
  return fromFd(fd);
}
HANDLE CreateFileW(const wchar_t* path, DWORD a, DWORD s, void* sa, DWORD d, DWORD f, HANDLE t) { return CreateFileA(narrow(path).c_str(), a, s, sa, d, f, t); }
BOOL ReadFile(HANDLE h, void* buf, DWORD n, DWORD* read, void*) { if (!isFdHandle(h)) return FALSE; ssize_t r = ::read(toFd(h), buf, n); if (r < 0) return FALSE; if (read) *read = (DWORD)r; return TRUE; }
BOOL WriteFile(HANDLE h, const void* buf, DWORD n, DWORD* wr, void*) { if (!isFdHandle(h)) return FALSE; ssize_t r = ::write(toFd(h), buf, n); if (r < 0) return FALSE; if (wr) *wr = (DWORD)r; return TRUE; }
BOOL GetFileSizeEx(HANDLE h, LARGE_INTEGER* sz) { struct stat st; if (!isFdHandle(h) || fstat(toFd(h), &st) != 0) return FALSE; sz->QuadPart = st.st_size; return TRUE; }
DWORD GetFileSize(HANDLE h, DWORD* high) { LARGE_INTEGER s; if (!GetFileSizeEx(h, &s)) return 0xFFFFFFFFu; if (high) *high = (DWORD)(s.QuadPart >> 32); return (DWORD)s.QuadPart; }
BOOL SetFilePointerEx(HANDLE h, LARGE_INTEGER d, LARGE_INTEGER* np, DWORD m) { if (!isFdHandle(h)) return FALSE; off_t r = lseek(toFd(h), d.QuadPart, m == FILE_BEGIN ? SEEK_SET : m == FILE_CURRENT ? SEEK_CUR : SEEK_END); if (r < 0) return FALSE; if (np) np->QuadPart = r; return TRUE; }
BOOL FlushFileBuffers(HANDLE h) { return isFdHandle(h) && fsync(toFd(h)) == 0; }

HANDLE CreateEventA(void*, BOOL manual, BOOL initial, const char*) { auto* e = new EventObject(); e->manual = manual; e->signaled = initial; return e; }
HANDLE CreateEventW(void* a, BOOL m, BOOL i, const wchar_t*) { return CreateEventA(a, m, i, nullptr); }
BOOL SetEvent(HANDLE h) { auto* e = (EventObject*)h; { std::lock_guard<std::mutex> l(e->m); e->signaled = true; } e->cv.notify_all(); return TRUE; }
BOOL ResetEvent(HANDLE h) { auto* e = (EventObject*)h; std::lock_guard<std::mutex> l(e->m); e->signaled = false; return TRUE; }
DWORD WaitForSingleObject(HANDLE h, DWORD ms) {
  if (h == nullptr || isFdHandle(h) || h == INVALID_HANDLE_VALUE) { return WAIT_FAILED; }
  auto* e = (EventObject*)h;
  std::unique_lock<std::mutex> l(e->m);
  if (ms == INFINITE) { e->cv.wait(l, [&] { return e->signaled; }); }
  else if (!e->cv.wait_for(l, std::chrono::milliseconds(ms), [&] { return e->signaled; })) { return WAIT_TIMEOUT; }
  if (!e->manual) { e->signaled = false; }
  return WAIT_OBJECT_0;
}
DWORD WaitForSingleObjectEx(HANDLE h, DWORD ms, BOOL) { return WaitForSingleObject(h, ms); }
DWORD WaitForMultipleObjects(DWORD n, const HANDLE* hs, BOOL all, DWORD ms) {
  auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms == INFINITE ? 0x7fffffff : ms);
  for (;;) {
    for (DWORD i = 0; i < n; ++i) { if (WaitForSingleObject(hs[i], 0) == WAIT_OBJECT_0) { return WAIT_OBJECT_0 + i; } }
    if (std::chrono::steady_clock::now() >= deadline) { return WAIT_TIMEOUT; }
    Sleep(1);
  }
}
HANDLE CreateFileMappingA(HANDLE file, void*, DWORD, DWORD hi, DWORD lo, const char*) {
  auto* m = new MappingObject();
  if (isFdHandle(file)) { m->fd = toFd(file); struct stat st; if (fstat(m->fd, &st) == 0) { m->size = st.st_size; } }
  if (hi || lo) { m->size = ((size_t)hi << 32) | lo; }
  return m;
}
HANDLE CreateFileMappingW(HANDLE f, void* a, DWORD p, DWORD h, DWORD l, const wchar_t*) { return CreateFileMappingA(f, a, p, h, l, nullptr); }
void* MapViewOfFile(HANDLE h, DWORD, DWORD, DWORD, SIZE_T n) {
  auto* m = (MappingObject*)h; size_t len = n ? n : m->size;
  void* p = mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_PRIVATE, m->fd, 0);
  if (p == MAP_FAILED) return nullptr;
  std::lock_guard<std::mutex> l(g_mapMutex); g_maps[p] = len; return p;
}
BOOL UnmapViewOfFile(const void* p) { std::lock_guard<std::mutex> l(g_mapMutex); auto it = g_maps.find(p); if (it == g_maps.end()) return FALSE; munmap((void*)p, it->second); g_maps.erase(it); return TRUE; }
int MessageBoxA(HWND, const char* text, const char* caption, UINT) { fprintf(stderr, "[%s] %s\n", caption ? caption : "", text ? text : ""); return 1; }
errno_t localtime_s(struct tm* t, const time_t* tt) { return localtime_r(tt, t) ? 0 : errno; }
errno_t gmtime_s(struct tm* t, const time_t* tt) { return gmtime_r(tt, t) ? 0 : errno; }
const char* GetCommandLineA() {
  static std::string cmd = [] { std::string out; FILE* f = fopen("/proc/self/cmdline", "rb"); if (f) { int c; while ((c = fgetc(f)) != EOF) { out += c == 0 ? ' ' : (char)c; } fclose(f); } return out; }();
  return cmd.c_str();
}
int ShowCursor(BOOL show) { static int count = 0; count += show ? 1 : -1; return count; }
short remixLinuxGetKeyState(int);
void remixLinuxGetCursorPos(long*, long*);
BOOL GetCursorPos(POINT* p) { long x = 0, y = 0; remixLinuxGetCursorPos(&x, &y); p->x = x; p->y = y; return TRUE; }
BOOL GetPhysicalCursorPos(POINT* p) { return GetCursorPos(p); }
BOOL SetCursorPos(int, int) { return TRUE; }
BOOL ClientToScreen(HWND, POINT*) { return TRUE; }
BOOL ScreenToClient(HWND, POINT*) { return TRUE; }
HWND GetForegroundWindow() { return (HWND)(uintptr_t)1; }
DWORD GetWindowThreadProcessId(HWND, DWORD* pid) { if (pid) *pid = getpid(); return (DWORD)gettid(); }
SHORT GetAsyncKeyState(int vk) { return remixLinuxGetKeyState(vk); }
SHORT GetKeyState(int vk) { return remixLinuxGetKeyState(vk); }

BOOL CloseHandle(HANDLE h) {
  if (h == nullptr || h == INVALID_HANDLE_VALUE) { return FALSE; }
  if (isFdHandle(h)) { return ::close(toFd(h)) == 0; }
  auto* o = (HandleObject*)h;
  if (o->kind == 1) { delete (EventObject*)h; } else if (o->kind == 2) { delete (MappingObject*)h; }
  return TRUE;
}

BOOL EnumDisplayDevicesA(const char*, DWORD i, DISPLAY_DEVICEA* d, DWORD) { if (i != 0) return FALSE; memset(d->DeviceName, 0, sizeof(d->DeviceName)); strcpy(d->DeviceName, "DISPLAY1"); strcpy(d->DeviceString, "Remix Display"); d->StateFlags = DISPLAY_DEVICE_ATTACHED_TO_DESKTOP | DISPLAY_DEVICE_PRIMARY_DEVICE; return TRUE; }
HCURSOR SetCursor(HCURSOR c) { return c; }
HBITMAP CreateBitmap(int, int, UINT, UINT, const void*) { return nullptr; }
BOOL DestroyCursor(HCURSOR) { return TRUE; }
HICON CreateIconIndirect(ICONINFO*) { return nullptr; }
BOOL DeleteObject(HGDIOBJ) { return TRUE; }
BOOL GetCursorInfo(CURSORINFO* ci) { ci->flags = 0; ci->hCursor = nullptr; ci->ptScreenPos = { 0, 0 }; return TRUE; }
BOOL SetPhysicalCursorPos(int, int) { return TRUE; }
HDC CreateCompatibleDC(HDC) { return nullptr; }
BOOL DeleteDC(HDC) { return TRUE; }
LONG_PTR GetWindowLongPtrA(HWND, int) { return 0; }
LONG_PTR GetWindowLongPtrW(HWND, int) { return 0; }
LONG_PTR SetWindowLongPtrA(HWND, int, LONG_PTR) { return 0; }
LONG_PTR SetWindowLongPtrW(HWND, int, LONG_PTR) { return 0; }
LONG GetWindowLongA(HWND, int) { return 0; }
LONG SetWindowLongA(HWND, int, LONG) { return 0; }
BOOL PostMessageW(HWND, UINT, WPARAM, LPARAM) { return FALSE; }
BOOL SetWindowPos(HWND, HWND, int, int, int, int, UINT) { return TRUE; }
LSTATUS RegOpenKeyExA(HKEY, const char*, DWORD, DWORD, HKEY*) { return ERROR_FILE_NOT_FOUND; }
LSTATUS RegQueryValueExA(HKEY, const char*, DWORD*, DWORD*, BYTE*, DWORD*) { return ERROR_FILE_NOT_FOUND; }
LSTATUS RegCloseKey(HKEY) { return ERROR_SUCCESS; }
LSTATUS RegNotifyChangeKeyValue(HKEY, BOOL, DWORD, HANDLE, BOOL) { return ERROR_FILE_NOT_FOUND; }
BOOL IsWindowUnicode(HWND) { return TRUE; }
LONG GetWindowLongW(HWND, int) { return 0; }
LONG SetWindowLongW(HWND, int, LONG) { return 0; }
LRESULT CallWindowProcA(WNDPROC p, HWND h, UINT m, WPARAM w, LPARAM l) { return p ? p(h, m, w, l) : 0; }
LRESULT CallWindowProcW(WNDPROC p, HWND h, UINT m, WPARAM w, LPARAM l) { return p ? p(h, m, w, l) : 0; }
LRESULT DefWindowProcA(HWND, UINT, WPARAM, LPARAM) { return 0; }
LRESULT DefWindowProcW(HWND, UINT, WPARAM, LPARAM) { return 0; }
BOOL IsIconic(HWND) { return FALSE; }
BOOL IsWindowVisible(HWND) { return TRUE; }
BOOL ShowWindow(HWND, int) { return TRUE; }
HWND GetWindow(HWND, UINT) { return nullptr; }
HWND GetParent(HWND) { return nullptr; }
BOOL SetRect(RECT* r, int l, int t, int rr, int b) { r->left = l; r->top = t; r->right = rr; r->bottom = b; return TRUE; }
BOOL OffsetRect(RECT* r, int dx, int dy) { r->left += dx; r->right += dx; r->top += dy; r->bottom += dy; return TRUE; }
BOOL AdjustWindowRectEx(RECT*, DWORD, BOOL, DWORD) { return TRUE; }
int MapWindowPoints(HWND, HWND, POINT*, UINT) { return 0; }
