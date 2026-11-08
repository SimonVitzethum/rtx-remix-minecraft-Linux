// Native Linux replacement for the Win32 Dear ImGui platform backend.
// There is no game window message pump, so the client forwards input through the exported remixapi_linux_Input* functions.
// Key codes use the Windows virtual key numbering.
#include "imgui.h"
#include "imgui_impl_win32.h"

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

// Arbitrary value for the keypad enter key, which has no distinct virtual key.
#define IM_VK_KEYPAD_ENTER      (VK_RETURN + 256)

ImGuiKey ImGui_ImplWin32_VirtualKeyToImGuiKey(WPARAM wParam);

namespace {
  enum class EventType : uint8_t { MousePos, MouseButton, MouseWheel, Key, Char, Focus };

  struct InputEvent {
    EventType type;
    int a;
    float x;
    float y;
  };

  std::mutex g_eventMutex;
  std::vector<InputEvent> g_events;
  std::atomic<uint8_t> g_keyState[256];
  std::atomic<int> g_cursorX{0};
  std::atomic<int> g_cursorY{0};

  void pushEvent(const InputEvent& event) {
    std::lock_guard<std::mutex> lock(g_eventMutex);
    if (g_events.size() < 4096) {
      g_events.push_back(event);
    }
  }

  // Expands the generic modifier codes to the left-hand variants ImGui tracks.
  int normalizeVirtualKey(int vk) {
    switch (vk) {
    case VK_SHIFT: return VK_LSHIFT;
    case VK_CONTROL: return VK_LCONTROL;
    case VK_MENU: return VK_LMENU;
    default: return vk;
    }
  }

  void setKeyState(int vk, bool down) {
    if (vk < 0 || vk >= 256) {
      return;
    }
    g_keyState[vk] = down ? 0x80 : 0;
    // Keep the generic modifier entries in sync for GetAsyncKeyState.
    auto sync = [](int generic, int left, int right) {
      g_keyState[generic] = (g_keyState[left] | g_keyState[right]) ? 0x80 : 0;
    };
    sync(VK_SHIFT, VK_LSHIFT, VK_RSHIFT);
    sync(VK_CONTROL, VK_LCONTROL, VK_RCONTROL);
    sync(VK_MENU, VK_LMENU, VK_RMENU);
  }
}

short remixLinuxGetKeyState(int vk) {
  if (vk < 0 || vk >= 256) {
    return 0;
  }
  return static_cast<short>(g_keyState[vk] ? 0x8000 : 0);
}

void remixLinuxGetCursorPos(long* x, long* y) {
  *x = g_cursorX;
  *y = g_cursorY;
}

extern "C" {
  __attribute__((visibility("default"))) void remixapi_linux_InputMouseMove(float x, float y) {
    g_cursorX = static_cast<int>(x);
    g_cursorY = static_cast<int>(y);
    pushEvent({ EventType::MousePos, 0, x, y });
  }

  // Buttons are numbered 0 left, 1 right, 2 middle, 3 and 4 the extra buttons.
  __attribute__((visibility("default"))) void remixapi_linux_InputMouseButton(int button, int down) {
    static const int kButtonVk[5] = { VK_LBUTTON, VK_RBUTTON, VK_MBUTTON, VK_XBUTTON1, VK_XBUTTON2 };
    if (button >= 0 && button < 5) {
      setKeyState(kButtonVk[button], down != 0);
    }
    pushEvent({ EventType::MouseButton, button, down ? 1.0f : 0.0f, 0.0f });
  }

  __attribute__((visibility("default"))) void remixapi_linux_InputMouseWheel(float dx, float dy) {
    pushEvent({ EventType::MouseWheel, 0, dx, dy });
  }

  __attribute__((visibility("default"))) void remixapi_linux_InputKey(int vk, int down) {
    vk = normalizeVirtualKey(vk);
    setKeyState(vk, down != 0);
    pushEvent({ EventType::Key, vk, down ? 1.0f : 0.0f, 0.0f });
  }

  __attribute__((visibility("default"))) void remixapi_linux_InputChar(uint32_t codepoint) {
    pushEvent({ EventType::Char, static_cast<int>(codepoint), 0.0f, 0.0f });
  }

  __attribute__((visibility("default"))) void remixapi_linux_InputFocus(int focused) {
    if (!focused) {
      for (int i = 0; i < 256; ++i) {
        g_keyState[i] = 0;
      }
    }
    pushEvent({ EventType::Focus, focused ? 1 : 0, 0.0f, 0.0f });
  }
}

IMGUI_IMPL_API bool ImGui_ImplWin32_Init(void* hwnd) {
  ImGuiIO& io = ImGui::GetIO();
  io.BackendPlatformName = "imgui_impl_linux";
  io.BackendFlags |= ImGuiBackendFlags_HasMouseCursors;
  io.DisplaySize = ImVec2(1920.0f, 1080.0f);
  return true;
}

IMGUI_IMPL_API void ImGui_ImplWin32_Shutdown() { }

IMGUI_IMPL_API void ImGui_ImplWin32_NewFrame() {
  ImGuiIO& io = ImGui::GetIO();
  if (io.DisplaySize.x <= 0.0f || io.DisplaySize.y <= 0.0f) {
    io.DisplaySize = ImVec2(1920.0f, 1080.0f);
  }

  static uint64_t lastTime = 0;
  const uint64_t now = GetTickCount64();
  io.DeltaTime = lastTime ? std::max(0.001f, static_cast<float>(now - lastTime) / 1000.0f) : 1.0f / 60.0f;
  lastTime = now;

  std::vector<InputEvent> events;
  {
    std::lock_guard<std::mutex> lock(g_eventMutex);
    events.swap(g_events);
  }

  for (const InputEvent& e : events) {
    switch (e.type) {
    case EventType::MousePos:
      io.AddMousePosEvent(e.x, e.y);
      break;
    case EventType::MouseButton:
      io.AddMouseButtonEvent(e.a, e.x != 0.0f);
      break;
    case EventType::MouseWheel:
      io.AddMouseWheelEvent(e.x, e.y);
      break;
    case EventType::Key: {
      const ImGuiKey key = ImGui_ImplWin32_VirtualKeyToImGuiKey(static_cast<WPARAM>(e.a));
      if (key != ImGuiKey_None) {
        io.AddKeyEvent(key, e.x != 0.0f);
      }
      // ImGui 1.87 reads modifiers from dedicated mod key events.
      io.AddKeyEvent(ImGuiKey_ModCtrl, g_keyState[VK_CONTROL] != 0);
      io.AddKeyEvent(ImGuiKey_ModShift, g_keyState[VK_SHIFT] != 0);
      io.AddKeyEvent(ImGuiKey_ModAlt, g_keyState[VK_MENU] != 0);
      io.AddKeyEvent(ImGuiKey_ModSuper, g_keyState[VK_LWIN] != 0 || g_keyState[VK_RWIN] != 0);
      break;
    }
    case EventType::Char:
      io.AddInputCharacter(static_cast<unsigned int>(e.a));
      break;
    case EventType::Focus:
      io.AddFocusEvent(e.a != 0);
      break;
    }
  }
}

IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM) { return 0; }
IMGUI_IMPL_API void ImGui_ImplWin32_EnableDpiAwareness() { }
IMGUI_IMPL_API float ImGui_ImplWin32_GetDpiScaleForHwnd(void*) { return 1.0f; }
IMGUI_IMPL_API float ImGui_ImplWin32_GetDpiScaleForMonitor(void*) { return 1.0f; }
IMGUI_IMPL_API void ImGui_ImplWin32_EnableAlphaCompositing(void*) { }

ImGuiKey ImGui_ImplWin32_VirtualKeyToImGuiKey(WPARAM wParam)
{
    switch (wParam)
    {
        case VK_TAB: return ImGuiKey_Tab;
        case VK_LEFT: return ImGuiKey_LeftArrow;
        case VK_RIGHT: return ImGuiKey_RightArrow;
        case VK_UP: return ImGuiKey_UpArrow;
        case VK_DOWN: return ImGuiKey_DownArrow;
        case VK_PRIOR: return ImGuiKey_PageUp;
        case VK_NEXT: return ImGuiKey_PageDown;
        case VK_HOME: return ImGuiKey_Home;
        case VK_END: return ImGuiKey_End;
        case VK_INSERT: return ImGuiKey_Insert;
        case VK_DELETE: return ImGuiKey_Delete;
        case VK_BACK: return ImGuiKey_Backspace;
        case VK_SPACE: return ImGuiKey_Space;
        case VK_RETURN: return ImGuiKey_Enter;
        case VK_ESCAPE: return ImGuiKey_Escape;
        case VK_OEM_7: return ImGuiKey_Apostrophe;
        case VK_OEM_COMMA: return ImGuiKey_Comma;
        case VK_OEM_MINUS: return ImGuiKey_Minus;
        case VK_OEM_PERIOD: return ImGuiKey_Period;
        case VK_OEM_2: return ImGuiKey_Slash;
        case VK_OEM_1: return ImGuiKey_Semicolon;
        case VK_OEM_PLUS: return ImGuiKey_Equal;
        case VK_OEM_4: return ImGuiKey_LeftBracket;
        case VK_OEM_5: return ImGuiKey_Backslash;
        case VK_OEM_6: return ImGuiKey_RightBracket;
        case VK_OEM_3: return ImGuiKey_GraveAccent;
        case VK_CAPITAL: return ImGuiKey_CapsLock;
        case VK_SCROLL: return ImGuiKey_ScrollLock;
        case VK_NUMLOCK: return ImGuiKey_NumLock;
        case VK_SNAPSHOT: return ImGuiKey_PrintScreen;
        case VK_PAUSE: return ImGuiKey_Pause;
        case VK_NUMPAD0: return ImGuiKey_Keypad0;
        case VK_NUMPAD1: return ImGuiKey_Keypad1;
        case VK_NUMPAD2: return ImGuiKey_Keypad2;
        case VK_NUMPAD3: return ImGuiKey_Keypad3;
        case VK_NUMPAD4: return ImGuiKey_Keypad4;
        case VK_NUMPAD5: return ImGuiKey_Keypad5;
        case VK_NUMPAD6: return ImGuiKey_Keypad6;
        case VK_NUMPAD7: return ImGuiKey_Keypad7;
        case VK_NUMPAD8: return ImGuiKey_Keypad8;
        case VK_NUMPAD9: return ImGuiKey_Keypad9;
        case VK_DECIMAL: return ImGuiKey_KeypadDecimal;
        case VK_DIVIDE: return ImGuiKey_KeypadDivide;
        case VK_MULTIPLY: return ImGuiKey_KeypadMultiply;
        case VK_SUBTRACT: return ImGuiKey_KeypadSubtract;
        case VK_ADD: return ImGuiKey_KeypadAdd;
        case IM_VK_KEYPAD_ENTER: return ImGuiKey_KeypadEnter;
        case VK_LSHIFT: return ImGuiKey_LeftShift;
        case VK_LCONTROL: return ImGuiKey_LeftCtrl;
        case VK_LMENU: return ImGuiKey_LeftAlt;
        case VK_LWIN: return ImGuiKey_LeftSuper;
        case VK_RSHIFT: return ImGuiKey_RightShift;
        case VK_RCONTROL: return ImGuiKey_RightCtrl;
        case VK_RMENU: return ImGuiKey_RightAlt;
        case VK_RWIN: return ImGuiKey_RightSuper;
        case VK_APPS: return ImGuiKey_Menu;
        case '0': return ImGuiKey_0;
        case '1': return ImGuiKey_1;
        case '2': return ImGuiKey_2;
        case '3': return ImGuiKey_3;
        case '4': return ImGuiKey_4;
        case '5': return ImGuiKey_5;
        case '6': return ImGuiKey_6;
        case '7': return ImGuiKey_7;
        case '8': return ImGuiKey_8;
        case '9': return ImGuiKey_9;
        case 'A': return ImGuiKey_A;
        case 'B': return ImGuiKey_B;
        case 'C': return ImGuiKey_C;
        case 'D': return ImGuiKey_D;
        case 'E': return ImGuiKey_E;
        case 'F': return ImGuiKey_F;
        case 'G': return ImGuiKey_G;
        case 'H': return ImGuiKey_H;
        case 'I': return ImGuiKey_I;
        case 'J': return ImGuiKey_J;
        case 'K': return ImGuiKey_K;
        case 'L': return ImGuiKey_L;
        case 'M': return ImGuiKey_M;
        case 'N': return ImGuiKey_N;
        case 'O': return ImGuiKey_O;
        case 'P': return ImGuiKey_P;
        case 'Q': return ImGuiKey_Q;
        case 'R': return ImGuiKey_R;
        case 'S': return ImGuiKey_S;
        case 'T': return ImGuiKey_T;
        case 'U': return ImGuiKey_U;
        case 'V': return ImGuiKey_V;
        case 'W': return ImGuiKey_W;
        case 'X': return ImGuiKey_X;
        case 'Y': return ImGuiKey_Y;
        case 'Z': return ImGuiKey_Z;
        case VK_F1: return ImGuiKey_F1;
        case VK_F2: return ImGuiKey_F2;
        case VK_F3: return ImGuiKey_F3;
        case VK_F4: return ImGuiKey_F4;
        case VK_F5: return ImGuiKey_F5;
        case VK_F6: return ImGuiKey_F6;
        case VK_F7: return ImGuiKey_F7;
        case VK_F8: return ImGuiKey_F8;
        case VK_F9: return ImGuiKey_F9;
        case VK_F10: return ImGuiKey_F10;
        case VK_F11: return ImGuiKey_F11;
        case VK_F12: return ImGuiKey_F12;
        default: return ImGuiKey_None;
    }
}

