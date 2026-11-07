#include "../dxvk_platform_exts.h"

namespace dxvk {

  DxvkPlatformExts DxvkPlatformExts::s_instance;

  std::string_view DxvkPlatformExts::getName() {
#ifdef REMIX_LINUX_NATIVE
    return "XCB/Wayland WSI";
#else
    return "Win32 WSI";
#endif
  }


  DxvkNameSet DxvkPlatformExts::getInstanceExtensions() {
    DxvkNameSet names;
#ifdef REMIX_LINUX_NATIVE
    names.add(VK_KHR_XCB_SURFACE_EXTENSION_NAME);
    names.add(VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME);
#else
    names.add(VK_KHR_WIN32_SURFACE_EXTENSION_NAME);
#endif

    return names;
  }


  DxvkNameSet DxvkPlatformExts::getDeviceExtensions(
          uint32_t      adapterId) {
    return DxvkNameSet();
  }


  void DxvkPlatformExts::initInstanceExtensions() {

  }


  void DxvkPlatformExts::initDeviceExtensions(
    const DxvkInstance* instance) {

  }

}