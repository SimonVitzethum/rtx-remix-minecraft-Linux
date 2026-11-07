// Native Linux replacement for the Win32 Dear ImGui platform backend.
// Remix on Linux has no game window message pump, input reaches the overlay through the Remix API only.
#include "imgui.h"
#include "imgui_impl_win32.h"

#include <windows.h>

IMGUI_IMPL_API bool ImGui_ImplWin32_Init(void* hwnd) {
  ImGuiIO& io = ImGui::GetIO();
  io.BackendPlatformName = "imgui_impl_linux_stub";
  io.DisplaySize = ImVec2(1920.0f, 1080.0f);
  return true;
}

IMGUI_IMPL_API void ImGui_ImplWin32_Shutdown() { }

IMGUI_IMPL_API void ImGui_ImplWin32_NewFrame() {
  ImGuiIO& io = ImGui::GetIO();
  if (io.DisplaySize.x <= 0.0f || io.DisplaySize.y <= 0.0f) {
    io.DisplaySize = ImVec2(1920.0f, 1080.0f);
  }
  io.DeltaTime = io.DeltaTime > 0.0f ? io.DeltaTime : 1.0f / 60.0f;
}

IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM) { return 0; }
ImGuiKey ImGui_ImplWin32_VirtualKeyToImGuiKey(WPARAM) { return ImGuiKey_None; }
IMGUI_IMPL_API void ImGui_ImplWin32_EnableDpiAwareness() { }
IMGUI_IMPL_API float ImGui_ImplWin32_GetDpiScaleForHwnd(void*) { return 1.0f; }
IMGUI_IMPL_API float ImGui_ImplWin32_GetDpiScaleForMonitor(void*) { return 1.0f; }
IMGUI_IMPL_API void ImGui_ImplWin32_EnableAlphaCompositing(void*) { }
