// XeSS has no Linux binary in this build, every entry point reports an unsupported driver.
#include <vulkan/vulkan.h>
#include "xess/xess.h"
#include "xess/xess_vk.h"
#include "xess/xess_debug.h"

XESS_API xess_result_t xessDestroyContext(xess_context_handle_t hContext) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessGetJitterScale(xess_context_handle_t hContext, float* pX, float* pY) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessGetOptimalInputResolution(xess_context_handle_t hContext, const xess_2d_t* pOutputResolution, xess_quality_settings_t qualitySettings, xess_2d_t* pInputResolutionOptimal, xess_2d_t* pInputResolutionMin, xess_2d_t* pInputResolutionMax) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessGetVersion(xess_version_t* pVersion) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessIsOptimalDriver(xess_context_handle_t hContext) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessSelectNetworkModel(xess_context_handle_t hContext, xess_network_model_t network) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessSetMaxResponsiveMaskValue(xess_context_handle_t hContext, float value) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessVKBuildPipelines(xess_context_handle_t hContext, VkPipelineCache pipelineCache, bool blocking, uint32_t initFlags) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessVKCreateContext(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, xess_context_handle_t* phContext) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessVKExecute(xess_context_handle_t hContext, VkCommandBuffer commandBuffer, const xess_vk_execute_params_t* pExecParams) { return XESS_RESULT_ERROR_UNSUPPORTED; }
XESS_API xess_result_t xessVKInit(xess_context_handle_t hContext, const xess_vk_init_params_t* pInitParams) { return XESS_RESULT_ERROR_UNSUPPORTED; }
