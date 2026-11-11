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

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>

namespace dxvk {

  // Checked, tightly packed storage for one mip and one array layer.
  // Rows and slices are measured in format blocks, including partial edge blocks.
  struct TextureUploadBand {
    size_t offset;
    size_t bytes;
    uint32_t rows;
    uint32_t y;
    uint32_t z;
    uint32_t height;
    uint32_t depth;
  };

  struct TextureUploadLayout {
    size_t rowBytes;
    size_t sliceBytes;
    size_t layerBytes;
    uint32_t rows;
    uint32_t slices;
    uint32_t height;
    uint32_t depth;
    uint32_t blockHeight;
    uint32_t blockDepth;

    static size_t multiply(size_t a, size_t b) {
      if (b && a > std::numeric_limits<size_t>::max() / b) {
        throw std::overflow_error("Texture upload size overflow");
      }
      return a * b;
    }

    static size_t add(size_t a, size_t b) {
      if (a > std::numeric_limits<size_t>::max() - b) {
        throw std::overflow_error("Texture upload size overflow");
      }
      return a + b;
    }

    static TextureUploadLayout make(uint32_t width, uint32_t height, uint32_t depth,
                                   uint32_t blockWidth, uint32_t blockHeight, uint32_t blockDepth,
                                   uint32_t elementBytes) {
      if (!width || !height || !depth || !blockWidth || !blockHeight || !blockDepth || !elementBytes) {
        throw std::invalid_argument("Empty texture upload layout");
      }
      const auto blocks = [](uint32_t extent, uint32_t block) { return 1 + (extent - 1) / block; };
      const size_t rowBytes = multiply(blocks(width, blockWidth), elementBytes);
      const uint32_t rows = blocks(height, blockHeight);
      const uint32_t slices = blocks(depth, blockDepth);
      const size_t sliceBytes = multiply(rowBytes, rows);
      return { rowBytes, sliceBytes, multiply(sliceBytes, slices), rows, slices, height, depth, blockHeight, blockDepth };
    }

    // A zero-sized band means that the position is invalid or a whole block row cannot fit.
    TextureUploadBand band(uint32_t firstRow, uint32_t slice, size_t budget) const {
      if (firstRow >= rows || slice >= slices) {
        return {};
      }
      const uint32_t count = uint32_t(std::min<size_t>(rows - firstRow, budget / rowBytes));
      const uint32_t y = firstRow * blockHeight;
      const uint32_t z = slice * blockDepth;
      return { size_t(slice) * sliceBytes + size_t(firstRow) * rowBytes, size_t(count) * rowBytes,
        count, y, z, uint32_t(std::min<size_t>(height - y, size_t(count) * blockHeight)),
        std::min(depth - z, blockDepth) };
    }
  };

} // namespace dxvk
