/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>

namespace rtx::voxel {

constexpr uint32_t kLeaf = 0x80000000u;
constexpr uint32_t kMaxNodes = 1u << 19;
constexpr uint64_t kMemoryLimit = 64ull << 20;

// Metrics count owned capacity separately from the used portion of builder output.
std::array<uint64_t, 8> metrics();

class Page {
public:
  virtual ~Page() = default;
  virtual uint32_t nodeCount() const = 0;
  virtual uint32_t root(uint32_t slab) const = 0;
  virtual void copy(uint32_t firstWord, uint32_t count, uint32_t offset, void* destination) = 0;
  virtual uint32_t lg() const = 0;
  virtual uint32_t slabs() const = 0;
  std::mutex mutex;
};

class Builder final : public Page {
public:
  Builder(uint32_t lg, uint32_t slabs);
  ~Builder() override;
  void surface(uint32_t firstCell, uint32_t count, const void* packed);
  void exact(uint32_t cx, uint32_t sy, uint32_t cz, const void* voxels);
  uint32_t finish();
  uint32_t nodeCount() const override;
  uint32_t root(uint32_t slab) const override;
  void copy(uint32_t firstWord, uint32_t count, uint32_t offset, void* destination) override;
  uint32_t lg() const override { return m_lg; }
  uint32_t slabs() const override { return m_slabs; }
  void overlay(const std::filesystem::path& directory, int32_t sx, int32_t sz, int32_t minY);

private:
  void writable() const;
  uint32_t make(const std::array<uint32_t, 8>& children);
  uint32_t region(uint32_t level, uint32_t x, uint32_t y, uint32_t z);
  uint32_t section(const uint8_t* voxels, uint32_t level, uint32_t x, uint32_t y, uint32_t z);
  uint32_t m_lg, m_slabs, m_n, m_cells, m_nodeCount = 1;
  bool m_finished = false;
  uint64_t m_reserved = 0;
  std::unique_ptr<uint32_t[]> m_nodes, m_table;
  std::array<int16_t, 16384> m_top {};
  std::array<uint16_t, 16384> m_rgb {};
  std::array<uint8_t, 16384> m_supplied {};
  std::array<int32_t, 21845> m_min {}, m_max {};
  std::array<uint32_t, 8> m_levelOffset {};
  std::array<uint32_t, 16> m_roots {};
  std::unique_ptr<uint32_t[]> m_exact;
  std::unique_ptr<uint8_t[]> m_exactSupplied;
  std::array<uint32_t, 17 * 17> m_exactPrefix {};
  std::array<bool, 256> m_exactColumns {};
  uint32_t m_slabBase = 0;
};

// Disk pages retain only a descriptor and roots. Node validation and copies use bounded scratch.
std::shared_ptr<Page> open(const std::filesystem::path& path);
void save(Page& page, const std::filesystem::path& path);
void recordSurface(const std::filesystem::path& directory, int32_t cx, int32_t cz, const void* packed);

} // namespace rtx::voxel
