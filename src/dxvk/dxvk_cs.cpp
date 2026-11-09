#include "dxvk_cs.h"
#include <cxxabi.h>
#include <typeinfo>
#include <unordered_map>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <string>
#include <vector>
#include "dxvk_scoped_annotation.h"

// NV-DXVK start: notify user and kill process on exception in CS thread to avoid silent hangs
#include "rtx_render/rtx_env.h"
// NV-DXVK end

#include "../tracy/TracyC.h"

#ifndef _WIN32
#include <unistd.h>
#endif
static bool remixProfileFlag(const char* name) {
  // Environment variable, or a marker file ~/.remix_<name> (a game started through an already running Prism instance does not see the shell's environment).
  if (std::getenv(name)) return true;
  const char* home = std::getenv("HOME");
#ifndef _WIN32
  return home && access((std::string(home) + "/.remix_" + name).c_str(), F_OK) == 0;
#else
  return false;
#endif
}

namespace dxvk {
  
  DxvkCsChunk::DxvkCsChunk() {
    
  }
  
  
  DxvkCsChunk::~DxvkCsChunk() {
    this->reset();
  }
  
  
  void DxvkCsChunk::init(DxvkCsChunkFlags flags) {
    m_flags = flags;
  }


  // REMIX_PROFILE_CS=1: time every command executed on the CS thread, grouped by command type (one lambda type per
  // EmitCs call site), and log the top entries every 10 seconds together with the idle time of the thread.
  namespace {
    struct CsProfile {
      bool enabled = remixProfileFlag("REMIX_PROFILE_CS");
      std::unordered_map<const char*, std::pair<double, uint64_t>> perType;
      double busyMs = 0;
      std::chrono::steady_clock::time_point windowStart = std::chrono::steady_clock::now();
      void report() {
        const auto now = std::chrono::steady_clock::now();
        const double windowMs = std::chrono::duration<double, std::milli>(now - windowStart).count();
        if (windowMs < 10000.0) return;
        std::vector<std::pair<double, const char*>> v;
        for (auto& kv : perType) v.emplace_back(kv.second.first, kv.first);
        std::sort(v.rbegin(), v.rend());
        std::string line = str::format("[CsProfile] window ", windowMs, " ms, cs busy ", busyMs, " ms (", 100.0 * busyMs / windowMs, "%)");
        for (size_t i = 0; i < v.size() && i < 14; i++) {
          int st = 0;
          char* dm = abi::__cxa_demangle(v[i].second, nullptr, nullptr, &st);
          std::string n = dm ? dm : v[i].second;
          free(dm);
          if (n.size() > 150) n.resize(150);
          line += str::format("\n    ", v[i].first, " ms  x", perType[v[i].second].second, "  ", n);
        }
        Logger::info(line);
        perType.clear(); busyMs = 0; windowStart = now;
      }
    };
    CsProfile g_csProfile;
  }

  void DxvkCsChunk::executeAll(DxvkContext* ctx) {
    ScopedCpuProfileZone();
    auto cmd = m_head;

    if (g_csProfile.enabled) {
      // Profiling path (CS thread only): same command order and lifetime handling as below.
      const bool singleUse = m_flags.test(DxvkCsChunkFlag::SingleUse);
      if (singleUse) m_commandOffset = 0;
      while (cmd != nullptr) {
        auto next = cmd->next();
        const auto t0 = std::chrono::steady_clock::now();
        cmd->exec(ctx);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        auto& e = g_csProfile.perType[typeid(*cmd).name()];
        e.first += ms; e.second++;
        g_csProfile.busyMs += ms;
        if (singleUse) cmd->~DxvkCsCmd();
        cmd = next;
      }
      if (singleUse) { m_head = nullptr; m_tail = nullptr; }
      g_csProfile.report();
      return;
    }
    
    if (m_flags.test(DxvkCsChunkFlag::SingleUse)) {
      m_commandOffset = 0;
      
      while (cmd != nullptr) {
        auto next = cmd->next();
        cmd->exec(ctx);
        cmd->~DxvkCsCmd();
        cmd = next;
      }

      m_head = nullptr;
      m_tail = nullptr;
    } else {
      while (cmd != nullptr) {
        cmd->exec(ctx);
        cmd = cmd->next();
      }
    }
  }
  
  
  void DxvkCsChunk::reset() {
    auto cmd = m_head;

    while (cmd != nullptr) {
      auto next = cmd->next();
      cmd->~DxvkCsCmd();
      cmd = next;
    }
    
    m_head = nullptr;
    m_tail = nullptr;

    m_commandOffset = 0;
  }
  
  
  DxvkCsChunkPool::DxvkCsChunkPool() {
    
  }
  
  
  DxvkCsChunkPool::~DxvkCsChunkPool() {
    for (DxvkCsChunk* chunk : m_chunks)
      delete chunk;
  }
  
  
  DxvkCsChunk* DxvkCsChunkPool::allocChunk(DxvkCsChunkFlags flags) {
    ScopedCpuProfileZone();
    DxvkCsChunk* chunk = nullptr;

    { std::lock_guard<sync::Spinlock> lock(m_mutex);
      
      if (m_chunks.size() != 0) {
        chunk = m_chunks.back();
        m_chunks.pop_back();
      }
    }
    
    if (!chunk)
      chunk = new DxvkCsChunk();
    
    chunk->init(flags);
    return chunk;
  }
  
  
  void DxvkCsChunkPool::freeChunk(DxvkCsChunk* chunk) {
    ScopedCpuProfileZone();
    chunk->reset();
    
    std::lock_guard<sync::Spinlock> lock(m_mutex);
    m_chunks.push_back(chunk);
  }
  
  
  DxvkCsThread::DxvkCsThread(
    const Rc<DxvkDevice>&   device,
    const Rc<DxvkContext>&  context)
  : m_device(device), m_context(context),
    m_thread([this] { threadFunc(); }) {
    
  }
  
  
  DxvkCsThread::~DxvkCsThread() {
    { std::unique_lock<dxvk::mutex> lock(m_mutex);
      m_stopped.store(true);
    }
    
    m_condOnAdd.notify_one();
    m_thread.join();
  }
  
  
  uint64_t DxvkCsThread::dispatchChunk(DxvkCsChunkRef&& chunk) {
    ScopedCpuProfileZone();

    uint64_t seq;

    { std::unique_lock<dxvk::mutex> lock(m_mutex);
      seq = ++m_chunksDispatched;
      m_chunksQueued.push(std::move(chunk));
    }
    
    m_condOnAdd.notify_one();
    return seq;
  }
  
  
  void DxvkCsThread::synchronize(uint64_t seq) {
    ScopedCpuProfileZone();

    // Avoid locking if we know the sync is a no-op, may
    // reduce overhead if this is being called frequently
    if (seq > m_chunksExecuted.load(std::memory_order_acquire)) {
      std::unique_lock<dxvk::mutex> lock(m_mutex);

      if (seq == SynchronizeAll)
        seq = m_chunksDispatched.load();

      auto t0 = dxvk::high_resolution_clock::now();
      m_condOnSync.wait(lock, [this, seq] {
        return m_chunksExecuted.load() >= seq;
      });
      auto t1 = dxvk::high_resolution_clock::now();
      auto ticks = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0);

      m_device->addStatCtr(DxvkStatCounter::CsSyncCount, 1);
      m_device->addStatCtr(DxvkStatCounter::CsSyncTicks, ticks.count());
    }
  }
  
  
  void DxvkCsThread::threadFunc() {
    ScopedCpuProfileZone();

    env::setThreadName("dxvk-cs");

    DxvkCsChunkRef chunk;

    try {
      while (!m_stopped.load()) {
        { 
          ScopedCpuProfileZoneN("waiting for work");
          std::unique_lock<dxvk::mutex> lock(m_mutex);
          if (chunk) {
            m_chunksExecuted++;
            m_condOnSync.notify_one();
            
            chunk = DxvkCsChunkRef();
          }
          
          if (m_chunksQueued.size() == 0) {
            m_condOnAdd.wait(lock, [this] {
              return (m_chunksQueued.size() != 0)
                  || (m_stopped.load());
            });
          }
          
          if (m_chunksQueued.size() != 0) {
            chunk = std::move(m_chunksQueued.front());
            m_chunksQueued.pop();
          }
        }
        
        if (chunk) {
          m_context->addStatCtr(DxvkStatCounter::CsChunkCount, 1);
          chunk->executeAll(m_context.ptr());
        }
      }
    } catch (const DxvkError& e) {
      Logger::err("Exception on CS thread!");
      Logger::err(e.message());

      // NV-DXVK start: notify user and kill process on exception in CS thread to avoid silent hangs
      char buf[2048];
      snprintf(buf, sizeof(buf), "Exception on CS thread: %s. The game will exit now.", e.message().c_str());
      messageBox(buf, "RTX Remix", MB_OK);
      exit(1);
      // NV-DXVK end
    }
  }
  
}