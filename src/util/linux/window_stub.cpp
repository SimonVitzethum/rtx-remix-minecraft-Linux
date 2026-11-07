// The Win32 window helpers do not exist on native Linux.
#include <string>
#include <windows.h>

#include "../util_window.h"

namespace dxvk::window {
  const std::string getWindowTitle(HWND) { return {}; }
  void saveWindowIconToFile(const std::string&, HWND) { }
}

extern "C" WINBOOL SetProcessDPIAware() { return TRUE; }
