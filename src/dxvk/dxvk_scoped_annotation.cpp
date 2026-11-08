/*
* Copyright (c) 2021-2022, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
#pragma once

#include "dxvk_scoped_annotation.h"
#include "dxvk_context.h"
#include "dxvk_device.h"
#include "client/TracyProfiler.hpp"
#include <map>
#include <string>
#include <vector>
#include <algorithm>
#include <cstdlib>

// Global overload
TRACY_OBJECT_MEMORY_PROFILING

namespace dxvk {

  // REMIX_PROFILE_GPU=1: every GPU profile zone writes a begin and an end timestamp; the averaged inclusive time per zone
  // name is logged every 120 frames (a frame starts at the "InjectRTX" zone). Results are read back lazily (no CPU wait).
  namespace {
    struct GpuZoneProfiler {
      static constexpr uint32_t kSegments = 4, kPerSegment = 1024;
      struct Zone { const char* name; uint32_t begin; uint32_t end; };
      bool enabled = std::getenv("REMIX_PROFILE_GPU") != nullptr;
      VkQueryPool pool = VK_NULL_HANDLE;
      uint32_t segment = 0, used = 0;
      bool segmentValid[kSegments] = {};
      std::vector<Zone> zones[kSegments];
      std::vector<uint32_t> stack;
      std::map<std::string, double> sumMs;
      uint32_t frames = 0;
      uint32_t skipped = 0;
      bool active = false;

      void collect(DxvkDevice* dev, uint32_t seg) {
        if (!segmentValid[seg]) return;
        auto& zs = zones[seg];
        std::vector<uint64_t> res(size_t(used > 0 ? kPerSegment : kPerSegment) * 2);
        const uint32_t count = kPerSegment;
        VkResult r = dev->vkd()->vkGetQueryPoolResults(dev->handle(), pool, seg * kPerSegment, count, res.size() * 8, res.data(), 16,
          VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        (void) r;
        const double period = dev->adapter()->deviceProperties().limits.timestampPeriod;
        bool ok = true;
        for (auto& z : zs) {
          if (!res[(z.begin - seg * kPerSegment) * 2 + 1] || !res[(z.end - seg * kPerSegment) * 2 + 1]) { ok = false; break; }
        }
        if (!ok) { skipped++; return; }
        for (auto& z : zs) {
          const uint64_t b = res[(z.begin - seg * kPerSegment) * 2], e = res[(z.end - seg * kPerSegment) * 2];
          sumMs[z.name] += double(e - b) * period * 1e-6;
        }
        if (++frames == 120) {
          std::vector<std::pair<double, std::string>> v;
          for (auto& kv : sumMs) v.emplace_back(kv.second / frames, kv.first);
          std::sort(v.rbegin(), v.rend());
          std::string line = "[GpuProfile] avg ms/frame (inclusive, nested zones overlap):";
          for (auto& p : v) line += str::format("\n    ", p.second, ": ", p.first);
          Logger::info(line);
          sumMs.clear(); frames = 0;
        }
      }

      void init(DxvkDevice* dev) {
        if (pool) return;
        VkQueryPoolCreateInfo ci = { VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
        ci.queryType = VK_QUERY_TYPE_TIMESTAMP;
        ci.queryCount = kSegments * kPerSegment;
        dev->vkd()->vkCreateQueryPool(dev->handle(), &ci, nullptr, &pool);
        dev->vkd()->vkResetQueryPool(dev->handle(), pool, 0, ci.queryCount);
      }

      // Returns the begin query index or ~0u when not recording.
      uint32_t begin(DxvkContext* ctx, const char* name) {
        DxvkDevice* dev = ctx->getDevice().ptr();
        init(dev);
        if (!strcmp(name, "InjectRTX")) {
          const uint32_t next = (segment + 1) % kSegments;
          collect(dev, next);
          segmentValid[next] = false;
          zones[next].clear();
          dev->vkd()->vkResetQueryPool(dev->handle(), pool, next * kPerSegment, kPerSegment);
          segment = next; used = 0; active = true;
          zones[segment].clear();
          stack.clear();
        }
        if (!active || used + 2 > kPerSegment) return ~0u;
        const uint32_t idx = segment * kPerSegment + used;
        used += 2;
        zones[segment].push_back({ name, idx, idx + 1 });
        dev->vkd()->vkCmdWriteTimestamp(ctx->getCmdBuffer(DxvkCmdBuffer::ExecBuffer), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, pool, idx);
        segmentValid[segment] = true;
        return idx;
      }
      void end(DxvkContext* ctx, uint32_t idx) {
        if (idx == ~0u) return;
        ctx->getDevice()->vkd()->vkCmdWriteTimestamp(ctx->getCmdBuffer(DxvkCmdBuffer::ExecBuffer), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, pool, idx + 1);
      }
    };
    GpuZoneProfiler g_gpuProfiler;
    thread_local std::vector<uint32_t> t_zoneStack;
  }

  __ScopedAnnotation::__ScopedAnnotation(Rc<DxvkContext> ctx, const char* name) : m_ctx(ctx) {
    if (g_gpuProfiler.enabled) t_zoneStack.push_back(g_gpuProfiler.begin(ctx.ptr(), name));
    VkDebugUtilsLabelEXT info = {
      VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT, nullptr, name, { 1.0f, 0.0f, 0.0f, 1.0f } // Red color
    };
    m_ctx->beginDebugLabel(&info);
    // NV-DXVK start: Integrate Aftermath
    m_ctx->deviceDiagnosticCheckpoint(name);
    // NV-DXVK end
  }

  __ScopedAnnotation::~__ScopedAnnotation() {
    if (g_gpuProfiler.enabled && !t_zoneStack.empty()) { g_gpuProfiler.end(m_ctx.ptr(), t_zoneStack.back()); t_zoneStack.pop_back(); }
    m_ctx->endDebugLabel();
  }

  __ScopedQueueAnnotation::__ScopedQueueAnnotation(DxvkDevice* dev, VkCommandBuffer cmdBuf, const char* name)
    : m_device(dev)
    , m_cmdBuf(cmdBuf) {
    VkDebugUtilsLabelEXT info = {
      VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT, nullptr, name, { 1.0f, 0.0f, 0.0f, 1.0f } // Red color
    };

    if (m_device->instance()->extensions().extDebugUtils) {
      m_device->vkd()->vkCmdBeginDebugUtilsLabelEXT(m_cmdBuf, &info);
    }

    if (m_device->extensions().nvDeviceDiagnosticCheckpoints) {
      m_device->vkd()->vkCmdSetCheckpointNV(m_cmdBuf, (const void*)name);
    }
  }

  __ScopedQueueAnnotation::~__ScopedQueueAnnotation() {
    if (m_device->instance()->extensions().extDebugUtils) {
      m_device->vkd()->vkCmdEndDebugUtilsLabelEXT(m_cmdBuf);
    }
  }
}
