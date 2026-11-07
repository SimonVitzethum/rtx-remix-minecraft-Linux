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
