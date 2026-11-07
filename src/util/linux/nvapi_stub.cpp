// NVAPI is unavailable on Linux: every entry point reports that the library was not found.
#include "../util_nvapi.h"

NVAPI_INTERFACE NvAPI_Initialize() { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_EnumPhysicalGPUs(NvPhysicalGpuHandle nvGPUHandle[NVAPI_MAX_PHYSICAL_GPUS], NvU32 *pGpuCount) { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_GetLogicalGPUFromPhysicalGPU(NvPhysicalGpuHandle hPhysicalGPU, NvLogicalGpuHandle *pLogicalGPU) { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_GPU_GetArchInfo(NvPhysicalGpuHandle hPhysicalGpu, NV_GPU_ARCH_INFO *pGpuArchInfo) { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_GPU_GetGpuCoreCount(NvPhysicalGpuHandle hPhysicalGpu,NvU32 *pCount) { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_GPU_GetGPUType(NvPhysicalGpuHandle hPhysicalGpu, NV_GPU_TYPE *pGpuType) { return NVAPI_LIBRARY_NOT_FOUND; }
NVAPI_INTERFACE NvAPI_GPU_GetLogicalGpuInfo(NvLogicalGpuHandle hLogicalGpu, NV_LOGICAL_GPU_DATA *pLogicalGpuData) { return NVAPI_LIBRARY_NOT_FOUND; }
