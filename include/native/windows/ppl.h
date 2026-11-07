#pragma once
// Minimal replacement for the Microsoft PPL parallel_for used by the runtime.
#include <algorithm>
#include <atomic>
#include <thread>
#include <vector>

namespace concurrency {
  template<typename T, typename F>
  void parallel_for(T first, T last, F&& fn) {
    if (last <= first) { return; }
    const unsigned workers = std::max(1u, std::thread::hardware_concurrency());
    std::atomic<T> next(first);
    std::vector<std::thread> threads;
    for (unsigned i = 0; i < workers; ++i) {
      threads.emplace_back([&] { for (T k = next++; k < last; k = next++) { fn(k); } });
    }
    for (auto& t : threads) { t.join(); }
  }
}
