/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

#include "voxel_dag.h"

#ifndef MINIZ_NO_ARCHIVE_APIS
#define MINIZ_NO_ARCHIVE_APIS
#endif
#ifndef MINIZ_NO_STDIO
#define MINIZ_NO_STDIO
#endif
#ifndef MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#define MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#endif
#include "third_party/miniz/miniz.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <chrono>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <system_error>

namespace rtx::voxel {
namespace {
  std::mutex budgetMutex;
  uint64_t reserved = 0, reservedPeak = 0, builders = 0;
  std::atomic<uint64_t> used {0}, usedPeak {0};
  uint64_t ioCurrent = 0, ioPeak = 0;
  constexpr uint64_t kBuilderBytes = 1024 * 1024;
  std::atomic<uint64_t> nodeChunks {0}, hashRehashes {0}, growthBytes {0}, rehashNanos {0};
  std::atomic<uint64_t> capacityRejections {0}, copyBytes {0}, copyNanos {0}, nodeCapacity {0}, nodeCapacityPeak {0};

  void reserveBytes(uint64_t bytes) {
    std::lock_guard<std::mutex> lock(budgetMutex);
    if (reserved + bytes > kMemoryLimit) {
      ++capacityRejections;
      throw std::runtime_error("Native DAG capacity growth deferred by the 64 MiB global budget");
    }
    reserved += bytes;
    reservedPeak = std::max(reservedPeak, reserved);
  }

  void releaseBytes(uint64_t bytes) {
    std::lock_guard<std::mutex> lock(budgetMutex);
    reserved -= bytes;
  }

  void addNodeCapacity(uint64_t bytes) {
    const uint64_t current = nodeCapacity.fetch_add(bytes, std::memory_order_relaxed) + bytes;
    uint64_t peak = nodeCapacityPeak.load(std::memory_order_relaxed);
    while (peak < current && !nodeCapacityPeak.compare_exchange_weak(peak, current, std::memory_order_relaxed)) {
    }
  }

  void reserveBuilder() {
    std::lock_guard<std::mutex> lock(budgetMutex);
    if (builders >= 2 || reserved + kBuilderBytes > kMemoryLimit) {
      ++capacityRejections;
      throw std::runtime_error("Native DAG workspace admission rejected (64 MiB global, two builders)");
    }
    reserved += kBuilderBytes;
    reservedPeak = std::max(reservedPeak, reserved);
    ++builders;
  }

  void releaseBuilder(uint64_t capacity, uint64_t output) {
    std::lock_guard<std::mutex> lock(budgetMutex);
    reserved -= capacity;
    used.fetch_sub(output, std::memory_order_relaxed);
    --builders;
  }

  void addOutput(uint64_t bytes) {
    const uint64_t current = used.fetch_add(bytes, std::memory_order_relaxed) + bytes;
    uint64_t peak = usedPeak.load(std::memory_order_relaxed);
    while (peak < current && !usedPeak.compare_exchange_weak(peak, current, std::memory_order_relaxed)) {
    }
  }

  void relocate(void* output, uint32_t count, uint32_t offset, uint32_t nodeCount) {
    auto p = static_cast<uint8_t*>(output);
    for (uint32_t i = 0; i < count; ++i) {
      uint32_t value;
      std::memcpy(&value, p + size_t(i) * 4, 4);
      if (value && !(value & kLeaf)) {
        if (value >= nodeCount || offset > kLeaf - 1 - value) {
          throw std::invalid_argument("Invalid DAG node reference or relocation overflow");
        }
        value += offset;
        std::memcpy(p + size_t(i) * 4, &value, 4);
      }
    }
  }

  void checkSlice(uint32_t first, uint32_t count, uint32_t nodes) {
    if (first > uint64_t(nodes) * 8 || count > uint64_t(nodes) * 8 - first) {
      throw std::invalid_argument("DAG word slice is outside the page");
    }
  }
}

std::array<uint64_t, 16> metrics() {
  std::lock_guard<std::mutex> lock(budgetMutex);
  return {kMemoryLimit, reserved, reservedPeak, builders, used.load(std::memory_order_relaxed), usedPeak.load(std::memory_order_relaxed), ioCurrent, ioPeak,
    nodeChunks.load(), hashRehashes.load(), growthBytes.load(), rehashNanos.load(), capacityRejections.load(),
    copyBytes.load(), copyNanos.load(), nodeCapacityPeak.load()};
}

Builder::Builder(uint32_t lg, uint32_t slabs)
: m_lg(lg), m_slabs(slabs), m_n(0), m_cells(0) {
  if (lg < 1 || lg > 4 || slabs < 1 || slabs > 16) {
    throw std::invalid_argument("DAG lg must be 1..4 and slabs 1..16");
  }
  reserveBuilder();
  m_reserved = kBuilderBytes;
  try {
    m_n = 256 >> lg;
    m_cells = m_n * m_n;
    growNodes();
    std::fill_n(node(0), 8, 0);
    m_tableCapacity = 2048;
    reserveBytes(uint64_t(m_tableCapacity) * 4);
    m_reserved += uint64_t(m_tableCapacity) * 4;
    m_table.reset(new uint32_t[m_tableCapacity] {});
    growthBytes.fetch_add(uint64_t(m_tableCapacity) * 4, std::memory_order_relaxed);
    m_exact.reset(new uint32_t[size_t(256) * slabs * 16] {});
    m_exactSupplied.reset(new uint8_t[size_t(256) * slabs * 16] {});
    m_top.fill(std::numeric_limits<int16_t>::min());
    addOutput(32);
  } catch (...) {
    for (auto& chunk : m_nodeChunks) {
      chunk.reset();
    }
    m_table.reset();
    m_exact.reset();
    m_exactSupplied.reset();
    nodeCapacity.fetch_sub(uint64_t(m_nodeCapacity) * 32, std::memory_order_relaxed);
    releaseBuilder(m_reserved, 0);
    m_reserved = 0;
    throw;
  }
}

Builder::~Builder() {
  // Release capacity only after every allocation has actually been freed.
  for (auto& chunk : m_nodeChunks) {
    chunk.reset();
  }
  nodeCapacity.fetch_sub(uint64_t(m_nodeCapacity) * 32, std::memory_order_relaxed);
  m_table.reset();
  m_exact.reset();
  m_exactSupplied.reset();
  if (m_reserved) {
    releaseBuilder(m_reserved, uint64_t(m_nodeCount) * 32);
  }
}

void Builder::writable() const {
  if (m_finished) {
    throw std::logic_error("DAG page is already finished");
  }
}

void Builder::surface(uint32_t firstCell, uint32_t count, const void* packed) {
  writable();
  if (firstCell > m_cells || count > m_cells - firstCell || (count && !packed)) {
    throw std::invalid_argument("Invalid DAG surface sample range");
  }
  auto input = static_cast<const uint8_t*>(packed);
  for (uint32_t i = 0; i < count; ++i) {
    uint32_t value;
    std::memcpy(&value, input + size_t(i) * 4, 4);
    const int16_t top = int16_t(value >> 16);
    if (top != std::numeric_limits<int16_t>::min() && (top < 0 || uint32_t(top) > m_slabs * 256)) {
      throw std::invalid_argument("DAG surface height is outside its vertical slabs");
    }
    m_top[firstCell + i] = top;
    m_rgb[firstCell + i] = uint16_t(value);
    m_supplied[firstCell + i] = 1;
  }
}

uint32_t* Builder::node(uint32_t index) const {
  uint32_t chunk = m_chunkCount - 1;
  while (index < m_chunkFirst[chunk]) {
    --chunk;
  }
  return m_nodeChunks[chunk].get() + size_t(index - m_chunkFirst[chunk]) * 8;
}

void Builder::growNodes() {
  const uint32_t add = m_nodeCapacity ? m_nodeCapacity : 1024;
  if (m_nodeCapacity + add > kMaxNodes || m_chunkCount == m_nodeChunks.size()) {
    throw std::length_error("Native DAG page exceeds its node bound; partition without reducing detail");
  }
  const uint64_t bytes = uint64_t(add) * 32;
  reserveBytes(bytes);
  try {
    auto chunk = std::unique_ptr<uint32_t[]>(new uint32_t[size_t(add) * 8]);
    m_chunkFirst[m_chunkCount] = m_nodeCapacity;
    m_chunkNodes[m_chunkCount] = add;
    m_nodeChunks[m_chunkCount++] = std::move(chunk);
  } catch (...) {
    releaseBytes(bytes);
    throw;
  }
  m_nodeCapacity += add;
  m_reserved += bytes;
  ++nodeChunks;
  growthBytes.fetch_add(bytes, std::memory_order_relaxed);
  addNodeCapacity(bytes);
}

void Builder::growHash() {
  const auto begin = std::chrono::steady_clock::now();
  const uint32_t capacity = m_tableCapacity * 2;
  const uint64_t bytes = uint64_t(capacity) * 4;
  reserveBytes(bytes);
  std::unique_ptr<uint32_t[]> replacement;
  try {
    replacement = std::make_unique<uint32_t[]>(capacity);
    for (uint32_t oldSlot = 0; oldSlot < m_tableCapacity; ++oldSlot) {
      const uint32_t index = m_table[oldSlot];
      if (!index) {
        continue;
      }
      const uint32_t* children = node(index);
      uint32_t hash = 0;
      for (uint32_t i = 0; i < 8; ++i) {
        hash = hash * 0x9E3779B1u + children[i] + (hash >> 15);
      }
      hash ^= hash >> 16;
      uint32_t slot = hash & (capacity - 1);
      while (replacement[slot]) {
        slot = (slot + 1) & (capacity - 1);
      }
      replacement[slot] = index;
    }
  } catch (...) {
    replacement.reset();
    releaseBytes(bytes);
    throw;
  }
  const uint64_t oldBytes = uint64_t(m_tableCapacity) * 4;
  m_table.swap(replacement);
  replacement.reset();
  releaseBytes(oldBytes);
  m_reserved += bytes - oldBytes;
  m_tableCapacity = capacity;
  ++hashRehashes;
  growthBytes.fetch_add(bytes, std::memory_order_relaxed);
  rehashNanos.fetch_add(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count(), std::memory_order_relaxed);
}

uint32_t Builder::make(const std::array<uint32_t, 8>& children) {
  if ((children[0] == 0 || (children[0] & kLeaf)) &&
      std::all_of(children.begin() + 1, children.end(), [&](uint32_t c) { return c == children[0]; })) {
    return children[0];
  }
  uint32_t hash = 0;
  for (uint32_t value : children) {
    hash = hash * 0x9E3779B1u + value + (hash >> 15);
  }
  hash ^= hash >> 16;
  uint32_t slot = hash & (m_tableCapacity - 1);
  while (m_table[slot]) {
    const uint32_t index = m_table[slot];
    if (std::memcmp(node(index), children.data(), 32) == 0) {
      return index;
    }
    slot = (slot + 1) & (m_tableCapacity - 1);
  }
  if (m_nodeCount >= kMaxNodes) {
    ++capacityRejections;
    throw std::length_error("Native DAG page exceeds the fixed node workspace; partition the page without reducing detail");
  }
  if (m_nodeCount * 2 > m_tableCapacity) {
    growHash();
    slot = hash & (m_tableCapacity - 1);
    while (m_table[slot]) {
      slot = (slot + 1) & (m_tableCapacity - 1);
    }
  }
  if (m_nodeCount == m_nodeCapacity) {
    growNodes();
  }
  const uint32_t index = m_nodeCount++;
  std::memcpy(node(index), children.data(), 32);
  m_table[slot] = index;
  addOutput(32);
  return index;
}

uint32_t Builder::section(const uint8_t* voxels, uint32_t level, uint32_t x, uint32_t y, uint32_t z) {
  if (!level) {
    uint16_t value;
    std::memcpy(&value, voxels + size_t((y << 8) | (z << 4) | x) * 2, 2);
    return value ? kLeaf | value : 0;
  }
  const uint32_t half = 1u << (level - 1);
  std::array<uint32_t, 8> children;
  for (uint32_t i = 0; i < 8; ++i) {
    children[i] = section(voxels, level - 1, x + (i & 1) * half,
      y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half);
  }
  return make(children);
}

void Builder::exact(uint32_t cx, uint32_t sy, uint32_t cz, const void* voxels) {
  writable();
  if (cx >= 16 || cz >= 16 || sy >= m_slabs * 16 || !voxels) {
    throw std::invalid_argument("Invalid exact DAG section coordinates");
  }
  const uint32_t index = (cz * 16 + cx) * m_slabs * 16 + sy;
  if (m_exactSupplied[index]) {
    throw std::invalid_argument("Exact DAG section was supplied twice");
  }
  m_exact[index] = section(static_cast<const uint8_t*>(voxels), 4, 0, 0, 0);
  m_exactSupplied[index] = 1;
  m_exactColumns[cz * 16 + cx] = true;
}

uint32_t Builder::region(uint32_t level, uint32_t x, uint32_t y, uint32_t z) {
  const uint32_t size = 1u << level;
  const uint32_t yAbs = m_slabBase + y;
  bool splitExact = false;
  if (level >= 4) {
    const uint32_t cx = x >> 4, cz = z >> 4, x1 = cx + (size >> 4), z1 = cz + (size >> 4);
    const uint32_t count = m_exactPrefix[z1 * 17 + x1] - m_exactPrefix[cz * 17 + x1]
      - m_exactPrefix[z1 * 17 + cx] + m_exactPrefix[cz * 17 + cx];
    if (count) {
      if (level == 4) {
        return m_exact[(cz * 16 + cx) * m_slabs * 16 + (yAbs >> 4)];
      }
      splitExact = true;
    }
  }
  if (!splitExact) {
    const uint32_t k = level > m_lg ? level - m_lg : 0;
    const uint32_t shift = std::max(level, m_lg);
    const uint32_t index = m_levelOffset[k] + (z >> shift) * (m_n >> k) + (x >> shift);
    const int32_t low = m_min[index], high = m_max[index];
    if (high <= int32_t(yAbs)) {
      return 0;
    }
    if (low - int32_t(yAbs + size) >= int32_t(1u << m_lg)) {
      return kLeaf | 0x6B4Du;
    }
    if (level <= m_lg && low >= int32_t(yAbs + size)) {
      return kLeaf | m_rgb[(z >> m_lg) * m_n + (x >> m_lg)];
    }
  }
  if (!level) {
    throw std::logic_error("Invalid DAG surface subdivision");
  }
  const uint32_t half = size >> 1;
  std::array<uint32_t, 8> children;
  for (uint32_t i = 0; i < 8; ++i) {
    children[i] = region(level - 1, x + (i & 1) * half,
      y + ((i >> 1) & 1) * half, z + ((i >> 2) & 1) * half);
  }
  return make(children);
}

uint32_t Builder::finish() {
  if (m_finished) {
    return m_nodeCount;
  }
  writable();
  if (std::find(m_supplied.begin(), m_supplied.begin() + m_cells, uint8_t(0)) != m_supplied.begin() + m_cells) {
    throw std::logic_error("DAG surface samples are incomplete");
  }
  for (uint32_t i = 0; i < m_cells; ++i) {
    m_min[i] = m_max[i] = m_top[i] == std::numeric_limits<int16_t>::min() ? -1000000 : m_top[i];
  }
  uint32_t previousSize = m_n, offset = 0;
  for (uint32_t level = 1; previousSize > 1; ++level) {
    const uint32_t nextSize = previousSize >> 1;
    m_levelOffset[level] = offset + previousSize * previousSize;
    for (uint32_t z = 0; z < nextSize; ++z) {
      for (uint32_t x = 0; x < nextSize; ++x) {
        int32_t low = std::numeric_limits<int32_t>::max(), high = std::numeric_limits<int32_t>::min();
        for (uint32_t i = 0; i < 4; ++i) {
          const uint32_t index = offset + (z * 2 + (i >> 1)) * previousSize + x * 2 + (i & 1);
          low = std::min(low, m_min[index]);
          high = std::max(high, m_max[index]);
        }
        m_min[m_levelOffset[level] + z * nextSize + x] = low;
        m_max[m_levelOffset[level] + z * nextSize + x] = high;
      }
    }
    offset = m_levelOffset[level];
    previousSize = nextSize;
  }
  for (uint32_t z = 0; z < 16; ++z) {
    for (uint32_t x = 0; x < 16; ++x) {
      m_exactPrefix[(z + 1) * 17 + x + 1] = uint32_t(m_exactColumns[z * 16 + x])
        + m_exactPrefix[z * 17 + x + 1] + m_exactPrefix[(z + 1) * 17 + x] - m_exactPrefix[z * 17 + x];
    }
  }
  for (uint32_t slab = 0; slab < m_slabs; ++slab) {
    m_slabBase = slab * 256;
    m_roots[slab] = region(8, 0, 0, 0);
  }
  m_finished = true;
  return m_nodeCount;
}

uint32_t Builder::nodeCount() const {
  if (!m_finished) {
    throw std::logic_error("DAG page is not finished");
  }
  return m_nodeCount;
}

uint32_t Builder::root(uint32_t slab) const {
  nodeCount();
  if (slab >= m_slabs) {
    throw std::invalid_argument("DAG slab is outside the page");
  }
  return m_roots[slab];
}

void Builder::copy(uint32_t firstWord, uint32_t count, uint32_t offset, void* destination) {
  checkSlice(firstWord, count, nodeCount());
  if (count && !destination) {
    throw std::invalid_argument("DAG copy destination is null");
  }
  if (count) {
    const auto begin = std::chrono::steady_clock::now();
    uint32_t copied = 0;
    while (copied < count) {
      const uint32_t word = firstWord + copied;
      const uint32_t index = word / 8;
      uint32_t chunk = m_chunkCount - 1;
      while (index < m_chunkFirst[chunk]) {
        --chunk;
      }
      const uint32_t available = (m_chunkFirst[chunk] + m_chunkNodes[chunk]) * 8 - word;
      const uint32_t slice = std::min(available, count - copied);
      std::memcpy(static_cast<uint8_t*>(destination) + size_t(copied) * 4, node(index) + word % 8, size_t(slice) * 4);
      copied += slice;
    }
    relocate(destination, count, offset, m_nodeCount);
    copyBytes.fetch_add(uint64_t(count) * 4, std::memory_order_relaxed);
    copyNanos.fetch_add(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count(), std::memory_order_relaxed);
  }
}

} // namespace rtx::voxel

namespace rtx::voxel {
namespace {
  // Covers codec state, stream buffers and fixed records; no complete region is materialized.
  class IoLease {
  public:
    explicit IoLease(uint64_t bytes = 1u << 20) : m_bytes(bytes) {
      std::lock_guard<std::mutex> lock(budgetMutex);
      if (reserved + bytes > kMemoryLimit) {
        ++capacityRejections;
        throw std::runtime_error("Native DAG I/O scratch admission rejected (64 MiB global)");
      }
      reserved += bytes;
      reservedPeak = std::max(reservedPeak, reserved);
      ioCurrent += bytes;
      ioPeak = std::max(ioPeak, ioCurrent);
    }
    ~IoLease() {
      std::lock_guard<std::mutex> lock(budgetMutex);
      reserved -= m_bytes;
      ioCurrent -= m_bytes;
    }
  private:
    uint64_t m_bytes;
  };

  uint32_t little32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
  }
  void putLittle32(uint8_t* p, uint32_t value) {
    for (uint32_t i = 0; i < 4; ++i) {
      p[i] = uint8_t(value >> (i * 8));
    }
  }
  int16_t bigShort(const uint8_t* p) {
    return int16_t(uint16_t(p[0]) << 8 | p[1]);
  }
  void putBigShort(uint8_t* p, uint16_t value) {
    p[0] = uint8_t(value >> 8);
    p[1] = uint8_t(value);
  }
  uint64_t checksum(uint64_t hash, const uint8_t* p, size_t count) {
    for (size_t i = 0; i < count; ++i) {
      hash = (hash ^ p[i]) * 1099511628211ull;
    }
    return hash;
  }

  std::atomic<uint64_t> tempId {0};
  std::filesystem::path tempPath(const std::filesystem::path& path) {
    auto temporary = path;
    temporary += std::filesystem::path(".tmp-" + std::to_string(++tempId));
    return temporary;
  }

  void replaceFile(const std::filesystem::path& from, const std::filesystem::path& to) {
    // The OS replacement must preserve the earlier file if the new write fails.
#ifdef _WIN32
    if (!MoveFileExW(from.c_str(), to.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
      throw std::runtime_error("Cannot atomically replace native DAG cache file");
    }
#else
    std::filesystem::rename(from, to);
#endif
  }

  constexpr size_t kHeaderBytes = 96;
  constexpr uint32_t kDiskMagic = 0x47414456;
  constexpr uint64_t kHashStart = 14695981039346656037ull;

  class DiskPage final : public Page {
  public:
    explicit DiskPage(const std::filesystem::path& path) : m_file(path, std::ios::binary) {
      if (!m_file) {
        throw std::runtime_error("Cannot open native DAG cache: " + path.string());
      }
      IoLease scratch;
      std::array<uint8_t, kHeaderBytes> header;
      m_file.read(reinterpret_cast<char*>(header.data()), header.size());
      if (!m_file || little32(header.data()) != kDiskMagic || little32(header.data() + 4) != 1) {
        throw std::runtime_error("Invalid native DAG cache header");
      }
      m_lg = little32(header.data() + 8);
      m_slabs = little32(header.data() + 12);
      m_count = little32(header.data() + 16);
      if (m_lg < 1 || m_lg > 4 || !m_slabs || m_slabs > 16 || !m_count || m_count > kMaxNodes || little32(header.data() + 28)) {
        throw std::runtime_error("Invalid native DAG cache dimensions");
      }
      for (uint32_t i = 0; i < 16; ++i) {
        m_roots[i] = little32(header.data() + 32 + i * 4);
        checkReference(m_roots[i], m_count);
        if (i >= m_slabs && m_roots[i]) {
          throw std::runtime_error("Invalid unused native DAG cache root");
        }
      }
      uint64_t hash = kHashStart;
      std::array<uint8_t, 65536> buffer;
      uint64_t word = 0;
      for (uint64_t remaining = uint64_t(m_count) * 32; remaining;) {
        const size_t bytes = size_t(std::min<uint64_t>(remaining, buffer.size()));
        m_file.read(reinterpret_cast<char*>(buffer.data()), bytes);
        if (!m_file) {
          throw std::runtime_error("Truncated native DAG cache");
        }
        hash = checksum(hash, buffer.data(), bytes);
        for (size_t i = 0; i < bytes; i += 4, ++word) {
          const uint32_t value = little32(buffer.data() + i);
          checkReference(value, uint32_t(word / 8));
          if (word < 8 && value) {
            throw std::runtime_error("Native DAG cache node zero is not empty");
          }
        }
        remaining -= bytes;
      }
      const uint64_t expected = uint64_t(little32(header.data() + 20)) | uint64_t(little32(header.data() + 24)) << 32;
      if (hash != expected || m_file.peek() != std::char_traits<char>::eof()) {
        throw std::runtime_error("Native DAG cache checksum or length mismatch");
      }
      m_file.clear();
    }
    uint32_t nodeCount() const override { return m_count; }
    uint32_t lg() const override { return m_lg; }
    uint32_t slabs() const override { return m_slabs; }
    uint32_t root(uint32_t slab) const override {
      if (slab >= m_slabs) {
        throw std::invalid_argument("DAG slab is outside the disk page");
      }
      return m_roots[slab];
    }
    void copy(uint32_t first, uint32_t count, uint32_t offset, void* destination) override {
      checkSlice(first, count, m_count);
      if (count && !destination) {
        throw std::invalid_argument("DAG disk copy destination is null");
      }
      if (!count) {
        return;
      }
      const auto begin = std::chrono::steady_clock::now();
      m_file.clear();
      m_file.seekg(kHeaderBytes + uint64_t(first) * 4);
      m_file.read(static_cast<char*>(destination), size_t(count) * 4);
      if (!m_file) {
        throw std::runtime_error("DAG cache changed or failed while reading upload slice");
      }
      // Supported JNI targets are little-endian; the disk format is explicitly little-endian.
      relocate(destination, count, offset, m_count);
      copyBytes.fetch_add(uint64_t(count) * 4, std::memory_order_relaxed);
      copyNanos.fetch_add(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count(), std::memory_order_relaxed);
    }
  private:
    static void checkReference(uint32_t value, uint32_t beforeNode) {
      if ((value & kLeaf) ? (value & 0x7FFF0000u) != 0 : value && value >= beforeNode) {
        throw std::runtime_error("Invalid or cyclic native DAG cache reference");
      }
    }
    std::ifstream m_file;
    uint32_t m_lg = 0, m_slabs = 0, m_count = 0;
    std::array<uint32_t, 16> m_roots {};
  };

  std::mutex sourceIoMutex;
  // One streaming codec owns a 64 KiB input/output buffer and its bounded miniz state.
  class ZReader {
  public:
    explicit ZReader(const std::filesystem::path& path) : m_file(path, std::ios::binary) {
      if (!m_file || mz_inflateInit(&m_stream) != MZ_OK) {
        throw std::runtime_error("Cannot initialize received-surface cache reader");
      }
    }
    ~ZReader() { mz_inflateEnd(&m_stream); }
    void read(void* destination, uint32_t bytes) {
      m_stream.next_out = static_cast<unsigned char*>(destination);
      m_stream.avail_out = bytes;
      while (m_stream.avail_out) {
        if (m_done) {
          throw std::runtime_error("Truncated received-surface cache");
        }
        if (!m_stream.avail_in) {
          m_file.read(reinterpret_cast<char*>(m_input.data()), m_input.size());
          m_stream.next_in = m_input.data();
          m_stream.avail_in = unsigned(m_file.gcount());

        }
        const int status = mz_inflate(&m_stream, MZ_NO_FLUSH);
        m_done = status == MZ_STREAM_END;
        if (status != MZ_OK && !m_done) {
          throw std::runtime_error("Invalid compressed received-surface cache");
        }
      }
    }
    void finish() {
      uint8_t extra;
      m_stream.next_out = &extra;
      m_stream.avail_out = 1;
      while (!m_done) {
        if (!m_stream.avail_in) {
          m_file.read(reinterpret_cast<char*>(m_input.data()), m_input.size());
          m_stream.next_in = m_input.data();
          m_stream.avail_in = unsigned(m_file.gcount());

        }
        const int status = mz_inflate(&m_stream, MZ_NO_FLUSH);
        if (!m_stream.avail_out || (status != MZ_OK && status != MZ_STREAM_END)) {
          throw std::runtime_error("Trailing or corrupt received-surface data");
        }
        m_done = status == MZ_STREAM_END;
      }
      if (m_stream.avail_in || m_file.peek() != std::char_traits<char>::eof()) {
        throw std::runtime_error("Trailing compressed received-surface data");
      }
    }
  private:
    std::ifstream m_file;
    mz_stream m_stream {};
    std::array<uint8_t, 65536> m_input;
    bool m_done = false;
  };

  class ZWriter {
  public:
    explicit ZWriter(const std::filesystem::path& path) : m_file(path, std::ios::binary | std::ios::trunc) {
      if (!m_file || mz_deflateInit(&m_stream, MZ_DEFAULT_COMPRESSION) != MZ_OK) {
        throw std::runtime_error("Cannot initialize received-surface cache writer");
      }
    }
    ~ZWriter() { mz_deflateEnd(&m_stream); }
    void write(const void* source, uint32_t bytes, bool last = false) {
      m_stream.next_in = static_cast<const unsigned char*>(source);
      m_stream.avail_in = bytes;
      int status;
      do {
        m_stream.next_out = m_output.data();
        m_stream.avail_out = unsigned(m_output.size());
        status = mz_deflate(&m_stream, last ? MZ_FINISH : MZ_NO_FLUSH);
        if (status != MZ_OK && status != MZ_STREAM_END) {
          throw std::runtime_error("Cannot compress received-surface cache");
        }
        m_file.write(reinterpret_cast<const char*>(m_output.data()), m_output.size() - m_stream.avail_out);
        if (!m_file) {
          throw std::runtime_error("Cannot write received-surface cache");
        }
      } while (m_stream.avail_in || (last && status != MZ_STREAM_END));
      if (last) {
        m_file.flush();
        if (!m_file) {
          throw std::runtime_error("Cannot flush received-surface cache");
        }
        m_file.close();
        if (!m_file) {
          throw std::runtime_error("Cannot close received-surface cache");
        }
      }
    }
  private:
    std::ofstream m_file;
    mz_stream m_stream {};
    std::array<uint8_t, 65536> m_output;
  };

  constexpr std::array<uint8_t, 8> kSurfaceHeader {0x46, 0x43, 0x43, 0x48, 0, 0, 0, 1};
  void readPresence(ZReader& reader, std::array<uint8_t, 1024>& present) {
    std::array<uint8_t, 8> header;
    reader.read(header.data(), unsigned(header.size()));
    if (header != kSurfaceHeader) {
      throw std::runtime_error("Invalid received-surface cache header");
    }
    reader.read(present.data(), unsigned(present.size()));
    if (std::any_of(present.begin(), present.end(), [](uint8_t value) { return value > 1; })) {
      throw std::runtime_error("Invalid received-surface presence table");
    }
  }
  int32_t floorDiv32(int32_t value) {
    const int32_t divided = value / 32;
    return divided - (value < 0 && value % 32 ? 1 : 0);
  }
  std::filesystem::path sourceFile(const std::filesystem::path& directory, int32_t rx, int32_t rz) {
    return directory / ("r_" + std::to_string(rx) + "_" + std::to_string(rz) + ".bin");
  }
}

std::shared_ptr<Page> open(const std::filesystem::path& path) {
  return std::make_shared<DiskPage>(path);
}

void save(Page& page, const std::filesystem::path& path) {
  IoLease scratch;
  const auto temporary = tempPath(path);
  if (!path.parent_path().empty()) {
    std::filesystem::create_directories(path.parent_path());
  }
  try {
    std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
    std::array<uint8_t, kHeaderBytes> header {};
    putLittle32(header.data(), kDiskMagic);
    putLittle32(header.data() + 4, 1);
    putLittle32(header.data() + 8, page.lg());
    putLittle32(header.data() + 12, page.slabs());
    const uint32_t count = page.nodeCount();
    putLittle32(header.data() + 16, count);
    for (uint32_t i = 0; i < page.slabs(); ++i) {
      putLittle32(header.data() + 32 + i * 4, page.root(i));
    }
    output.write(reinterpret_cast<const char*>(header.data()), header.size());
    std::array<uint8_t, 65536> buffer;
    uint64_t hash = kHashStart;
    for (uint32_t word = 0; word < count * 8;) {
      const uint32_t slice = std::min<uint32_t>(unsigned(buffer.size() / 4), count * 8 - word);
      page.copy(word, slice, 0, buffer.data());
      hash = checksum(hash, buffer.data(), size_t(slice) * 4);
      output.write(reinterpret_cast<const char*>(buffer.data()), size_t(slice) * 4);
      word += slice;
    }
    putLittle32(header.data() + 20, uint32_t(hash));
    putLittle32(header.data() + 24, uint32_t(hash >> 32));
    output.seekp(0);
    output.write(reinterpret_cast<const char*>(header.data()), header.size());
    output.flush();
    if (!output) {
      throw std::runtime_error("Cannot write native DAG disk page");
    }
    output.close();
    if (!output) {
      throw std::runtime_error("Cannot close native DAG disk page");
    }
    replaceFile(temporary, path);
  } catch (...) {
    std::error_code ignored;
    std::filesystem::remove(temporary, ignored);
    throw;
  }
}

namespace {
template<typename Consumer>
void overlayCells(const std::filesystem::path& directory, int32_t sx, int32_t sz, int32_t minY,
    uint32_t lg, uint32_t slabs, Consumer consume) {
  std::lock_guard<std::mutex> lock(sourceIoMutex);
  IoLease scratch;
  const int64_t minCx = int64_t(sx) * 16, minCz = int64_t(sz) * 16;
  if (minCx < std::numeric_limits<int32_t>::min() || minCx + 15 > std::numeric_limits<int32_t>::max() ||
      minCz < std::numeric_limits<int32_t>::min() || minCz + 15 > std::numeric_limits<int32_t>::max()) {
    throw std::invalid_argument("Received-surface page coordinates overflow");
  }
  // A 256-block page is aligned to half of a 512-block source region.
  const int32_t rx = floorDiv32(int32_t(minCx)), rz = floorDiv32(int32_t(minCz));
  const auto path = sourceFile(directory, rx, rz);
  if (!std::filesystem::exists(path)) {
    return;
  }
  ZReader reader(path);
  std::array<uint8_t, 1024> present;
  readPresence(reader, present);
  std::array<uint8_t, 1024> record;
  const uint32_t size = 1u << lg;
  for (uint32_t slot = 0; slot < 1024; ++slot) {
    if (!present[slot]) {
      continue;
    }
    reader.read(record.data(), unsigned(record.size()));
    const int64_t cx = int64_t(rx) * 32 + (slot & 31), cz = int64_t(rz) * 32 + (slot >> 5);
    if (cx < minCx || cx >= minCx + 16 || cz < minCz || cz >= minCz + 16) {
      continue;
    }
    for (uint32_t z = 0; z < 16; z += size) {
      for (uint32_t x = 0; x < 16; x += size) {
        int32_t n = 0, topSum = 0, r = 0, g = 0, b = 0;
        for (uint32_t dz = 0; dz < size; ++dz) {
          for (uint32_t dx = 0; dx < size; ++dx) {
            const uint32_t index = (z + dz) * 16 + x + dx;
            const int32_t top = bigShort(record.data() + index * 2);
            if (top == std::numeric_limits<int16_t>::min()) {
              continue;
            }
            const uint16_t color = uint16_t(bigShort(record.data() + 512 + index * 2));
            topSum += top;
            r += ((color >> 11) & 31) * 255 / 31;
            g += ((color >> 5) & 63) * 255 / 63;
            b += (color & 31) * 255 / 31;
            ++n;
          }
        }
        int32_t top = std::numeric_limits<int16_t>::min();
        uint32_t color = 0;
        if (n) {
          // Java Math.round(float) uses floor(v + .5), including negative world heights.
          const int64_t sum = int64_t(topSum) * 2 + n;
          const int64_t divisor = int64_t(n) * 2;
          const int64_t rounded = sum / divisor - (sum < 0 && sum % divisor ? 1 : 0);
          const int64_t relative = rounded - minY;
          if (relative < 0 || relative > int64_t(slabs) * 256) {
            throw std::runtime_error("Received-surface height is outside the DAG page");
          }
          top = int32_t(relative);
          color = (uint32_t(r / n) >> 3) << 11 | (uint32_t(g / n) >> 2) << 5 | (uint32_t(b / n) >> 3);
        }
        const uint32_t index = uint32_t(((cz - minCz) * 16 + z) >> lg) * (256u >> lg)
          + uint32_t(((cx - minCx) * 16 + x) >> lg);
        const uint32_t packed = uint32_t(uint16_t(top)) << 16 | color;
        consume(index, packed);
      }
    }
  }
  reader.finish();
}

} // namespace

void Builder::overlay(const std::filesystem::path& directory, int32_t sx, int32_t sz, int32_t minY) {
  writable();
  overlayCells(directory, sx, sz, minY, m_lg, m_slabs, [&](uint32_t index, uint32_t packed) {
    surface(index, 1, &packed);
  });
}

void overlaySurface(const std::filesystem::path& directory, int32_t sx, int32_t sz, int32_t minY,
    uint32_t lg, uint32_t slabs, void* samples) {
  if (lg < 1 || lg > 4 || slabs < 1 || slabs > 16 || !samples) {
    throw std::invalid_argument("Invalid streaming surface dimensions or destination");
  }
  auto output = static_cast<uint8_t*>(samples);
  const uint32_t cells = (256u >> lg) * (256u >> lg);
  for (uint32_t i = 0; i < cells; ++i) {
    uint32_t packed;
    std::memcpy(&packed, output + size_t(i) * 4, 4);
    const int16_t height = int16_t(packed >> 16);
    if (height != std::numeric_limits<int16_t>::min() && (height < 0 || uint32_t(height) > slabs * 256)) {
      throw std::invalid_argument("Streaming surface height is outside its vertical slabs");
    }
  }
  overlayCells(directory, sx, sz, minY, lg, slabs, [&](uint32_t index, uint32_t packed) {
    std::memcpy(output + size_t(index) * 4, &packed, 4);
  });
}

void recordSurface(const std::filesystem::path& directory, int32_t cx, int32_t cz, const void* packed) {
  if (!packed) {
    throw std::invalid_argument("Received-surface record is null");
  }
  std::lock_guard<std::mutex> lock(sourceIoMutex);
  IoLease scratch;
  std::filesystem::create_directories(directory);
  const int32_t rx = floorDiv32(cx), rz = floorDiv32(cz);
  const uint32_t target = uint32_t(int64_t(cz) - int64_t(rz) * 32) * 32 + uint32_t(int64_t(cx) - int64_t(rx) * 32);
  const auto path = sourceFile(directory, rx, rz), temporary = tempPath(path);
  try {
    std::unique_ptr<ZReader> input;
    std::array<uint8_t, 1024> before {};
    if (std::filesystem::exists(path)) {
      input = std::make_unique<ZReader>(path);
      readPresence(*input, before);
    }
    auto after = before;
    after[target] = 1;
    ZWriter output(temporary);
    output.write(kSurfaceHeader.data(), unsigned(kSurfaceHeader.size()));
    output.write(after.data(), unsigned(after.size()));
    std::array<uint8_t, 1024> record;
    for (uint32_t slot = 0; slot < 1024; ++slot) {
      if (before[slot]) {
        input->read(record.data(), unsigned(record.size()));
      }
      if (slot == target) {
        for (uint32_t i = 0; i < 256; ++i) {
          uint32_t value;
          std::memcpy(&value, static_cast<const uint8_t*>(packed) + size_t(i) * 4, 4);
          putBigShort(record.data() + i * 2, uint16_t(value >> 16));
          putBigShort(record.data() + 512 + i * 2, uint16_t(value));
        }
      }
      if (after[slot]) {
        output.write(record.data(), unsigned(record.size()));
      }
    }
    if (input) {
      input->finish();
      input.reset();
    }
    output.write(nullptr, 0, true);
    replaceFile(temporary, path);
  } catch (...) {
    std::error_code ignored;
    std::filesystem::remove(temporary, ignored);
    throw;
  }
}

} // namespace rtx::voxel
