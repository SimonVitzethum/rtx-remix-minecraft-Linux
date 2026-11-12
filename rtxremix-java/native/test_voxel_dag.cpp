/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#include "voxel_dag.h"

#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <random>
#include <thread>
#include <vector>

using namespace rtx::voxel;

namespace {
  template<typename Function> void rejects(Function fn) {
    bool rejected = false;
    try { fn(); } catch (const std::exception&) { rejected = true; }
    assert(rejected);
  }
  std::vector<uint32_t> read(Page& page, uint32_t relocation = 0) {
    std::vector<uint32_t> words(size_t(page.nodeCount()) * 8);
    for (uint32_t first = 0; first < words.size();) {
      const uint32_t count = std::min<uint32_t>(16384, uint32_t(words.size()) - first);
      page.copy(first, count, relocation, words.data() + first);
      first += count;
    }
    return words;
  }
  uint32_t value(Page& page, const std::vector<uint32_t>& words, uint32_t x, uint32_t y, uint32_t z) {
    uint32_t ref = page.root(y >> 8);
    for (uint32_t level = 8; ref && !(ref & kLeaf);) {
      assert(level);
      --level;
      const uint32_t child = ((x >> level) & 1) | (((y >> level) & 1) << 1) | (((z >> level) & 1) << 2);
      ref = words.at(size_t(ref) * 8 + child);
    }
    return ref;
  }
  void fill(Builder& page, uint32_t lg, uint32_t top = 100, uint32_t rgb = 0x1234) {
    std::vector<uint32_t> samples(size_t(256 >> lg) * (256 >> lg), top << 16 | rgb);
    page.surface(0, uint32_t(samples.size()), samples.data());
  }
}

int main(int argc, char** argv) {
  assert(argc == 2);
  const std::filesystem::path directory = argv[1];
  std::filesystem::create_directories(directory);
  assert(metrics()[1] == 0);
  rejects([] { Builder invalid(0, 2); });
  {
    auto first = std::make_shared<Builder>(4, 2);
    auto second = std::make_shared<Builder>(4, 2);
    rejects([] { Builder rejected(4, 2); });
    assert(metrics()[3] == 2 && metrics()[1] < kMemoryLimit);
    auto retained = first;
    first.reset();
    assert(metrics()[3] == 2);
    retained.reset();
    assert(metrics()[3] == 1);
  }
  assert(metrics()[1] == 0 && metrics()[4] == 0);
  for (uint32_t lg = 1; lg <= 4; ++lg) {
    Builder page(lg, 2);
    rejects([&] { page.root(0); });
    rejects([&] { page.finish(); });
    fill(page, lg);
    std::array<uint16_t, 4096> exact {};
    exact[0] = 0xF800;
    page.exact(1, 0, 2, exact.data());
    rejects([&] { page.exact(1, 0, 2, exact.data()); });
    const uint32_t nodes = page.finish();
    assert(page.finish() == nodes && nodes < kMaxNodes);
    const auto words = read(page);
    assert(value(page, words, 16, 0, 32) == (kLeaf | 0xF800));
    // Explicit exact air overrides every higher generated section in this column.
    assert(value(page, words, 16, 99, 32) == 0);
    assert(value(page, words, 0, 99, 0) == (kLeaf | 0x1234));
    assert(value(page, words, 255, 100, 255) == 0);
    assert(page.root(1) == 0);
    rejects([&] { fill(page, lg); });
    rejects([&] { page.exact(0, 0, 0, exact.data()); });
    rejects([&] { page.root(2); });
    uint32_t output = 0;
    rejects([&] { page.copy(nodes * 8, 1, 0, &output); });
    const auto relocated = read(page, 200);
    for (size_t i = 0; i < words.size(); ++i) {
      assert(relocated[i] == (words[i] && !(words[i] & kLeaf) ? words[i] + 200 : words[i]));
    }
    const auto file = directory / ("page-" + std::to_string(lg));
    save(page, file);
    auto disk = rtx::voxel::open(file);
    assert(disk->nodeCount() == nodes && disk->root(0) == page.root(0));
    assert(read(*disk) == words && read(*disk, 200) == relocated);
    disk.reset();
    // Corrupt content is rejected before a page can be published or uploaded.
    {
      std::fstream corrupt(file, std::ios::binary | std::ios::in | std::ios::out);
      corrupt.seekp(96 + 32);
      corrupt.put(char(0x7F));
    }
    rejects([&] { rtx::voxel::open(file); });
    std::filesystem::remove(file);
  }
  {
    // Surface boundaries preserve exact integer heights at every horizontal LOD.
    std::mt19937 random(9876);
    Builder page(1, 2);
    std::vector<uint32_t> surface(128 * 128);
    for (auto& sample : surface) { sample = (20 + random() % 360) << 16 | 0x07E0; }
    page.surface(0, uint32_t(surface.size()), surface.data());
    page.finish();
    const auto words = read(page);
    for (uint32_t z = 0; z < 256; z += 7) {
      for (uint32_t x = 0; x < 256; x += 7) {
        const uint32_t height = surface[(z >> 1) * 128 + (x >> 1)] >> 16;
        assert(value(page, words, x, height - 1, z) == (kLeaf | 0x07E0));
        assert(value(page, words, x, height, z) == 0);
      }
    }
  }
  {
    const auto received = directory / "received";
    std::array<uint32_t, 256> samples;
    samples.fill(uint32_t(uint16_t(-20)) << 16 | 0x07E0);
    recordSurface(received, -1, -1, samples.data());
    samples.fill(uint32_t(uint16_t(-18)) << 16 | 0xF800);
    recordSurface(received, -2, -1, samples.data());
    // Rewriting a record preserves its neighbours and empty cells override procedural terrain.
    samples.fill(0x80000000u);
    recordSurface(received, -1, -1, samples.data());
    Builder page(1, 2);
    fill(page, 1);
    page.overlay(received, -1, -1, -64);
    page.finish();
    const auto words = read(page);
    assert(value(page, words, 255, 0, 255) == 0);
    assert(value(page, words, 224, 45, 255) == (kLeaf | 0xF800));
    assert(value(page, words, 224, 46, 255) == 0);
    assert(value(page, words, 0, 99, 0) == (kLeaf | 0x1234));
    assert(metrics()[6] == 0 && metrics()[7] <= 1u << 20);
    std::filesystem::remove_all(received);
  }
  {
    // Adversarial exact data must hit an explicit bound and release all partially built nodes.
    Builder page(4, 2);
    fill(page, 4);
    std::mt19937 random(42);
    std::array<uint16_t, 4096> data;
    bool bounded = false;
    try {
      for (uint32_t chunk = 0; chunk < 256; ++chunk) {
        for (uint32_t sy = 0; sy < 32; ++sy) {
          for (auto& item : data) { item = uint16_t(random()); }
          page.exact(chunk & 15, sy, chunk >> 4, data.data());
        }
      }
    } catch (const std::length_error&) { bounded = true; }
    assert(bounded);
  }
  const auto final = metrics();
  assert(final[1] == 0 && final[3] == 0 && final[4] == 0 && final[6] == 0 && final[2] <= kMemoryLimit);
  std::cout << "Native DAG: exact/LOD/relocation/disk/overlay/admission/lifetime bounds PASS; reserved peak=" << final[2] << '\n';
}
