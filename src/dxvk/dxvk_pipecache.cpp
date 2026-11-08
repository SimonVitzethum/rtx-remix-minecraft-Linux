#include "dxvk_pipecache.h"

#ifdef REMIX_LINUX_NATIVE
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#endif

namespace dxvk {

#ifdef REMIX_LINUX_NATIVE
  namespace {
    // The cache lives beside the runtime library, so a standalone precompile run and the game share it
    // regardless of their working directories. REMIX_PIPELINE_CACHE_FILE overrides the location.
    std::string pipelineCachePath() {
      if (const char* overridePath = std::getenv("REMIX_PIPELINE_CACHE_FILE")) {
        return overridePath;
      }

      Dl_info info = {};
      if (dladdr(reinterpret_cast<void*>(&pipelineCachePath), &info) == 0 || info.dli_fname == nullptr) {
        return std::string();
      }

      std::string path = info.dli_fname;
      const size_t slash = path.find_last_of('/');
      return (slash == std::string::npos ? std::string(".") : path.substr(0, slash)) + "/remix-pipeline.cache";
    }

    std::vector<char> readFile(const std::string& path) {
      std::vector<char> data;
      if (FILE* file = std::fopen(path.c_str(), "rb")) {
        char buffer[1 << 16];
        size_t count;
        while ((count = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
          data.insert(data.end(), buffer, buffer + count);
        }
        std::fclose(file);
      }
      return data;
    }
  }
#endif
  
  DxvkPipelineCache::DxvkPipelineCache(
    const Rc<vk::DeviceFn>& vkd)
  : m_vkd(vkd) {
    VkPipelineCacheCreateInfo info;
    info.sType            = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    info.pNext            = nullptr;
    info.flags            = 0;
    info.initialDataSize  = 0;
    info.pInitialData     = nullptr;

#ifdef REMIX_LINUX_NATIVE
    // The driver validates the blob against its own version and ignores it when stale.
    const std::vector<char> initialData = readFile(pipelineCachePath());
    if (!initialData.empty()) {
      info.initialDataSize = initialData.size();
      info.pInitialData    = initialData.data();
      Logger::info(str::format("DxvkPipelineCache: Loaded ", initialData.size(), " bytes from ", pipelineCachePath()));
    }
#endif
    
    if (m_vkd->vkCreatePipelineCache(m_vkd->device(),
        &info, nullptr, &m_handle) != VK_SUCCESS)
      throw DxvkError("DxvkPipelineCache: Failed to create cache");
  }
  
  
  DxvkPipelineCache::~DxvkPipelineCache() {
#ifdef REMIX_LINUX_NATIVE
    save();
#endif
    m_vkd->vkDestroyPipelineCache(
      m_vkd->device(), m_handle, nullptr);
  }


#ifdef REMIX_LINUX_NATIVE
  void DxvkPipelineCache::save() const {
    const std::string path = pipelineCachePath();
    if (path.empty()) {
      return;
    }

    size_t size = 0;
    if (m_vkd->vkGetPipelineCacheData(m_vkd->device(), m_handle, &size, nullptr) != VK_SUCCESS || size == 0) {
      return;
    }

    std::vector<char> data(size);
    if (m_vkd->vkGetPipelineCacheData(m_vkd->device(), m_handle, &size, data.data()) != VK_SUCCESS) {
      return;
    }

    // Write to a temporary file first so a killed process never leaves a truncated cache behind.
    const std::string tempPath = path + ".tmp";
    if (FILE* file = std::fopen(tempPath.c_str(), "wb")) {
      const bool written = std::fwrite(data.data(), 1, size, file) == size;
      std::fclose(file);
      if (written && std::rename(tempPath.c_str(), path.c_str()) == 0) {
        Logger::info(str::format("DxvkPipelineCache: Saved ", size, " bytes to ", path));
      }
    }
  }
#endif
  
}
