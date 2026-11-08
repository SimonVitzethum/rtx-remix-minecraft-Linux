#include "../util_reflex.h"

NVLLVK_API NvLL_VK_Status NvLL_VK_Initialize() { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_Unload() { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_InitLowLatencyDevice(HANDLE vkDevice, HANDLE* signalSemaphoreHandle) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_DestroyLowLatencyDevice(HANDLE vkDevice) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_Sleep(HANDLE vkDevice, NvVKU64 signalValue) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_GetLatency(HANDLE vkDevice, NVLL_VK_LATENCY_RESULT_PARAMS* pGetLatencyResultParams) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_SetSleepMode(HANDLE vkDevice, NVLL_VK_SET_SLEEP_MODE_PARAMS* pSetSleepModeParams) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_SetLatencyMarker(HANDLE vkDevice, NVLL_VK_LATENCY_MARKER_PARAMS* pSetLatencyMarkerParams) { return NVLL_VK_ERROR; }
NVLLVK_API NvLL_VK_Status NvLL_VK_NotifyOutOfBandQueue(HANDLE vkDevice, HANDLE queueHandle, NVLL_VK_OUT_OF_BAND_QUEUE_TYPE queueType) { return NVLL_VK_ERROR; }

// ---------------------------------------------------------------------------------------------------------------------
// Debug aid (Linux only): `kill -RTMIN+5 <pid>` writes the native stack of every thread to $TMPDIR/remix_stacks.txt, with the
// load address of libremixapi.so first, so a hang can be diagnosed without ptrace (resolve with addr2line -f -C -e libremixapi.so).
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <execinfo.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
  int g_stackFd = -1;

  void threadStackHandler(int) {
    char header[64];
    int n = snprintf(header, sizeof(header), "--- thread %ld\n", long(syscall(SYS_gettid)));
    if (g_stackFd >= 0) {
      (void) !write(g_stackFd, header, n);
      void* frames[48];
      int count = backtrace(frames, 48);
      backtrace_symbols_fd(frames, count, g_stackFd);
    }
  }

  void dumpTrigger(int) {
    const char* dir = getenv("TMPDIR");
    char path[256];
    snprintf(path, sizeof(path), "%s/remix_stacks.txt", dir ? dir : "/tmp");
    g_stackFd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (g_stackFd < 0)
      return;
    FILE* maps = fopen("/proc/self/maps", "r");
    char line[512];
    while (maps && fgets(line, sizeof(line), maps)) {
      if (strstr(line, "libremixapi.so") && strstr(line, "r-xp"))
        (void) !write(g_stackFd, line, strlen(line));
    }
    if (maps)
      fclose(maps);
    const pid_t self = getpid();
    const pid_t me = pid_t(syscall(SYS_gettid));
    if (DIR* tasks = opendir("/proc/self/task")) {
      while (dirent* e = readdir(tasks)) {
        const pid_t tid = pid_t(atoi(e->d_name));
        if (tid <= 0 || tid == me)
          continue;
        syscall(SYS_tgkill, self, tid, SIGRTMIN + 6);
        usleep(20000);
      }
      closedir(tasks);
    }
    close(g_stackFd);
    g_stackFd = -1;
  }

  struct StackDumpInstaller {
    StackDumpInstaller() {
      struct sigaction sa;
      memset(&sa, 0, sizeof(sa));
      sa.sa_handler = threadStackHandler;
      sigemptyset(&sa.sa_mask);
      sigaction(SIGRTMIN + 6, &sa, nullptr);
      sa.sa_handler = dumpTrigger;
      sigaction(SIGRTMIN + 5, &sa, nullptr);
    }
  } g_stackDumpInstaller;
}
