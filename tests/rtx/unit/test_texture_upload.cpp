/*
* Copyright (c) 2025, NVIDIA CORPORATION. All rights reserved.
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

#include <cstring>
#include <iostream>
#include <vector>

#include "../../../src/dxvk/rtx_render/rtx_texture_upload.h"

using dxvk::TextureUploadLayout;

namespace {
  void check(bool condition, const char* message) {
    if (!condition) {
      throw std::runtime_error(message);
    }
  }

  void verify(uint32_t width, uint32_t height, uint32_t depth, uint32_t blockWidth,
              uint32_t blockHeight, uint32_t blockDepth, uint32_t elementBytes, size_t budget,
              bool copy = false) {
    const auto layout = TextureUploadLayout::make(width, height, depth, blockWidth, blockHeight, blockDepth, elementBytes);
    size_t consumed = 0;
    size_t peak = 0;
    size_t bands = 0;
    std::vector<uint8_t> source(copy ? layout.layerBytes : 0);
    std::vector<uint8_t> result(source.size(), 0);
    for (size_t i = 0; i < source.size(); ++i) {
      source[i] = uint8_t(i * 17 + i / 11);
    }
    for (uint32_t z = 0; z < layout.slices; ++z) {
      for (uint32_t row = 0; row < layout.rows;) {
        const auto band = layout.band(row, z, budget);
        check(band.rows && band.bytes <= budget, "Band must make progress within budget");
        check(band.offset == consumed, "Band bytes must cover the mip exactly once");
        check(band.offset <= layout.layerBytes && band.bytes <= layout.layerBytes - band.offset, "Band exceeds source bounds");
        check(band.y % blockHeight == 0 && band.z % blockDepth == 0, "Band offset must be block aligned");
        check(band.y < height && band.height <= height - band.y, "Band exceeds image height");
        check(band.z < depth && band.depth <= depth - band.z, "Band exceeds image depth");
        check(band.y + band.height == height || band.height % blockHeight == 0, "Only the final row may contain a partial block");
        if (copy) {
          std::vector<uint8_t> staging(band.bytes);
          std::memcpy(staging.data(), source.data() + band.offset, band.bytes);
          std::memcpy(result.data() + band.offset, staging.data(), band.bytes);
        }
        consumed += band.bytes;
        peak = std::max(peak, band.bytes);
        ++bands;
        row += band.rows;
      }
    }
    check(consumed == layout.layerBytes, "Incomplete mip coverage");
    check(result == source, "Banded copy changed pixel/block contents");
    check(!layout.band(layout.rows, 0, budget).bytes, "Invalid row must not produce a band");
    check(!layout.band(0, layout.slices, budget).bytes, "Invalid depth slice must not produce a band");
    check(!layout.band(0, 0, layout.rowBytes - 1).bytes, "A row larger than budget must not overcommit");
    std::cout << width << "x" << height << "x" << depth << ": bytes=" << consumed
      << " bands=" << bands << " planned-peak=" << peak << '\n';
  }
}

int main() {
  try {
    constexpr size_t budget = 8 * 1024 * 1024;
    // Planning only: no giant pixel arrays or Vulkan allocations.
    for (const auto bytes : { 1u, 4u }) {
      verify(12288, 10240, 1, 1, 1, 1, bytes, budget);
      verify(16384, 16384, 1, 1, 1, 1, bytes, budget);
      verify(24576, 16384, 1, 1, 1, 1, bytes, budget);
    }
    verify(24576, 16384, 1, 4, 4, 1, 16, budget);
    // Uneven compressed edges, depth slices and tiny budgets with real byte copies.
    for (unsigned layer = 0; layer < 3; ++layer) {
      verify(13, 11, 5, 4, 4, 2, 16, 129, true);
      verify(7, 5, 3, 1, 1, 1, 4, 57, true);
      verify(1, 1, 1, 4, 4, 1, 16, 16, true);
    }
    const auto huge = TextureUploadLayout::make(UINT32_MAX, 1, 1, 4, 1, 1, 16);
    check(huge.rowBytes == size_t(17179869184ull), "Block rounding overflowed at UINT32_MAX");
    bool overflow = false;
    try {
      TextureUploadLayout::make(UINT32_MAX, UINT32_MAX, UINT32_MAX, 1, 1, 1, 16);
    } catch (const std::overflow_error&) {
      overflow = true;
    }
    check(overflow, "Huge dimensions must reject multiplication overflow");
    overflow = false;
    try {
      TextureUploadLayout::add(SIZE_MAX, 1);
    } catch (const std::overflow_error&) {
      overflow = true;
    }
    check(overflow, "Mip-chain accumulation must reject addition overflow");
    bool invalid = false;
    try {
      TextureUploadLayout::make(0, 1, 1, 1, 1, 1, 4);
    } catch (const std::invalid_argument&) {
      invalid = true;
    }
    check(invalid, "Empty dimensions must be rejected");
    std::cout << "texture upload planning and bounded copies passed\n";
  } catch (const std::exception& e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
