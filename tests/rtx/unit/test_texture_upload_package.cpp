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

#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>

#include "../../../src/dxvk/rtx_render/rtx_asset_package.h"

// This standalone I/O test needs no renderer or logger instance.
namespace dxvk {
  void Logger::err(const std::string& message) { std::cerr << message << '\n'; }
  void Logger::info(const std::string&) { }
}

int main() {
  using Package = dxvk::AssetPackage;
  const auto filename = std::filesystem::temp_directory_path()
    / ("texture-upload-package-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + ".bin");
  try {
    std::array<uint8_t, 256> pixels;
    for (size_t i = 0; i < pixels.size(); ++i) {
      pixels[i] = uint8_t(i * 13 + 7);
    }
    {
      Package::Header header{ Package::kMagic, Package::kVersion, sizeof(Package::Header) + pixels.size() };
      Package::AssetDesc asset{};
      Package::BlobDesc blob{};
      blob.offset = sizeof(Package::Header);
      blob.size = uint32_t(pixels.size());
      const uint16_t count = 1;
      std::ofstream file(filename, std::ios::binary);
      const auto write = [&file](const auto& value) { file.write(reinterpret_cast<const char*>(&value), sizeof(value)); };
      write(header);
      file.write(reinterpret_cast<const char*>(pixels.data()), pixels.size());
      write(count);
      write(count);
      write(asset);
      write(blob);
      file.write("atlas", 6);
    }
    {
      Package package(filename.string());
      if (!package.initialize()) {
        throw std::runtime_error("Package fixture did not initialize");
      }
      std::array<uint8_t, 34> guard;
      guard.fill(0xce);
      if (package.readDataBlob(0, guard.data() + 1, 17, 53) != 17
          || guard.front() != 0xce || guard[18] != 0xce
          || std::memcmp(guard.data() + 1, pixels.data() + 53, 17)) {
        throw std::runtime_error("Partial blob read changed bytes or exceeded destination bounds");
      }
      guard.fill(0xce);
      if (package.readDataBlob(0, guard.data() + 1, 32, 250) != 6
          || guard.front() != 0xce || guard[7] != 0xce
          || std::memcmp(guard.data() + 1, pixels.data() + 250, 6)) {
        throw std::runtime_error("Blob tail read did not stop at the blob boundary");
      }
      if (package.readDataBlob(0, guard.data(), 7, 257) || package.readDataBlob(1, guard.data(), 7)
          || package.readDataBlob(0, nullptr, 7) || package.readDataBlob(0, guard.data(), 1, SIZE_MAX)) {
        throw std::runtime_error("Invalid range or blob index accepted");
      }
      std::atomic<bool> failed{ false };
      const auto reader = [&package, &pixels, &failed](size_t offset) {
        std::array<uint8_t, 19> bytes;
        for (size_t n = 0; n < 1000; ++n) {
          if (package.readDataBlob(0, bytes.data(), bytes.size(), offset) != bytes.size()
              || std::memcmp(bytes.data(), pixels.data() + offset, bytes.size())) {
            failed = true;
          }
        }
      };
      std::thread a(reader, 3);
      std::thread b(reader, 171);
      a.join();
      b.join();
      if (failed) {
        throw std::runtime_error("Concurrent range readers interfered with shared file position");
      }
    }
    std::filesystem::remove(filename);
    std::cout << "bounded package ranges and concurrent readers passed\n";
  } catch (const std::exception& error) {
    std::filesystem::remove(filename);
    std::cerr << error.what() << '\n';
    return 1;
  }
}
