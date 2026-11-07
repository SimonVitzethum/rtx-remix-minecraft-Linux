/*
* Copyright (c) 2021-2026, NVIDIA CORPORATION. All rights reserved.
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
#include "rtx_external_texture.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <remix/remix_c.h>

#include "dxvk_context.h"
#include "dxvk_device.h"
#include "rtx_texture_manager.h"

namespace dxvk {

  bool getExternalTextureFormat(uint32_t remixapiFormat, ExternalTextureFormat& out) {
    out = {};
    switch (remixapiFormat) {
    case REMIXAPI_TEXTURE_FORMAT_RGBA8_UNORM: out = { VK_FORMAT_R8G8B8A8_UNORM, 4, 4, false, false, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_RGBA8_SRGB:  out = { VK_FORMAT_R8G8B8A8_SRGB,  4, 4, false, false, true  }; return true;
    case REMIXAPI_TEXTURE_FORMAT_BGRA8_UNORM: out = { VK_FORMAT_B8G8R8A8_UNORM, 4, 4, false, false, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_BGRA8_SRGB:  out = { VK_FORMAT_B8G8R8A8_SRGB,  4, 4, false, false, true  }; return true;
    case REMIXAPI_TEXTURE_FORMAT_R8_UNORM:    out = { VK_FORMAT_R8_UNORM,       1, 1, false, false, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_RG8_UNORM:   out = { VK_FORMAT_R8G8_UNORM,     2, 2, false, false, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_RGBA16_SFLOAT: out = { VK_FORMAT_R16G16B16A16_SFLOAT, 8, 4, true, false, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_RGBA32_SFLOAT: out = { VK_FORMAT_R32G32B32A32_SFLOAT, 16, 4, false, true, false }; return true;
    case REMIXAPI_TEXTURE_FORMAT_R32_SFLOAT:  out = { VK_FORMAT_R32_SFLOAT,     4, 1, false, true, false }; return true;
    default: return false;
    }
  }

  namespace {
    float halfToFloat(uint16_t h) {
      const uint32_t sign = (h >> 15) & 1;
      const uint32_t exp = (h >> 10) & 0x1F;
      const uint32_t man = h & 0x3FF;
      float v;
      if (exp == 0) {
        v = std::ldexp(static_cast<float>(man), -24);
      } else if (exp == 31) {
        v = man ? NAN : INFINITY;
      } else {
        v = std::ldexp(static_cast<float>(man | 0x400), static_cast<int>(exp) - 25);
      }
      return sign ? -v : v;
    }

    uint16_t floatToHalf(float f) {
      uint32_t x;
      std::memcpy(&x, &f, sizeof(x));
      const uint32_t sign = (x >> 16) & 0x8000;
      int32_t exp = static_cast<int32_t>((x >> 23) & 0xFF) - 127 + 15;
      uint32_t man = x & 0x7FFFFF;
      if (exp >= 31) {
        return static_cast<uint16_t>(sign | 0x7C00);
      }
      if (exp <= 0) {
        if (exp < -10) {
          return static_cast<uint16_t>(sign);
        }
        man |= 0x800000;
        return static_cast<uint16_t>(sign | (man >> (14 - exp)));
      }
      return static_cast<uint16_t>(sign | (exp << 10) | (man >> 13));
    }

    float srgbToLinear(float c) {
      return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
    }

    float linearToSrgb(float c) {
      return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f;
    }
  }

  MemoryAssetData::MemoryAssetData(uint64_t hash,
                                   const ExternalTextureFormat& format,
                                   uint32_t width,
                                   uint32_t height,
                                   const uint8_t* pixels,
                                   bool useMaxFilter)
    : m_format(format), m_useMaxFilter(useMaxFilter) {
    uint32_t levels = 1;
    for (uint32_t dim = std::max(width, height); dim > 1; dim >>= 1) {
      ++levels;
    }

    m_levels.resize(levels);
    m_extents.resize(levels);
    m_extents[0] = { width, height };
    m_levels[0].assign(pixels, pixels + static_cast<size_t>(width) * height * format.bytesPerPixel);
    generateMips();

    m_hash = hash;
    m_info.type = AssetType::Image2D;
    m_info.compression = AssetCompression::None;
    m_info.format = format.vkFormat;
    m_info.extent = VkExtent3D{ width, height, 1 };
    m_info.mipLevels = levels;
    m_info.mininumLevelsToUpload = levels;
    m_info.numLayers = 1;
    m_info.filename = "";
  }

  void MemoryAssetData::generateMips() {
    const uint32_t channels = m_format.channels;
    for (size_t level = 1; level < m_levels.size(); ++level) {
      const VkExtent2D src = m_extents[level - 1];
      const VkExtent2D dst = { std::max(src.width >> 1, 1u), std::max(src.height >> 1, 1u) };
      m_extents[level] = dst;
      m_levels[level].resize(static_cast<size_t>(dst.width) * dst.height * m_format.bytesPerPixel);

      const uint8_t* srcData = m_levels[level - 1].data();
      uint8_t* dstData = m_levels[level].data();

      auto readChannel = [&](uint32_t x, uint32_t y, uint32_t c) -> float {
        const size_t index = (static_cast<size_t>(y) * src.width + x) * channels + c;
        if (m_format.isFloat32) {
          float v;
          std::memcpy(&v, srcData + index * 4, 4);
          return v;
        }
        if (m_format.isFloat16) {
          uint16_t h;
          std::memcpy(&h, srcData + index * 2, 2);
          return halfToFloat(h);
        }
        const float v = srcData[index] / 255.0f;
        // The alpha channel of sRGB formats is stored linearly.
        const bool alphaChannel = channels == 4 && c == 3;
        return m_format.isSrgb && !alphaChannel ? srgbToLinear(v) : v;
      };

      auto writeChannel = [&](uint32_t x, uint32_t y, uint32_t c, float v) {
        const size_t index = (static_cast<size_t>(y) * dst.width + x) * channels + c;
        if (m_format.isFloat32) {
          std::memcpy(dstData + index * 4, &v, 4);
        } else if (m_format.isFloat16) {
          const uint16_t h = floatToHalf(v);
          std::memcpy(dstData + index * 2, &h, 2);
        } else {
          const bool alphaChannel = channels == 4 && c == 3;
          if (m_format.isSrgb && !alphaChannel) {
            v = linearToSrgb(std::clamp(v, 0.0f, 1.0f));
          }
          dstData[index] = static_cast<uint8_t>(std::clamp(v, 0.0f, 1.0f) * 255.0f + 0.5f);
        }
      };

      for (uint32_t y = 0; y < dst.height; ++y) {
        for (uint32_t x = 0; x < dst.width; ++x) {
          const uint32_t x0 = std::min(x * 2, src.width - 1);
          const uint32_t x1 = std::min(x * 2 + 1, src.width - 1);
          const uint32_t y0 = std::min(y * 2, src.height - 1);
          const uint32_t y1 = std::min(y * 2 + 1, src.height - 1);
          for (uint32_t c = 0; c < channels; ++c) {
            const float a = readChannel(x0, y0, c);
            const float b = readChannel(x1, y0, c);
            const float d = readChannel(x0, y1, c);
            const float e = readChannel(x1, y1, c);
            writeChannel(x, y, c, m_useMaxFilter ? std::max(std::max(a, b), std::max(d, e)) : (a + b + d + e) * 0.25f);
          }
        }
      }
    }
  }

  const void* MemoryAssetData::data(int, int level) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (level < 0 || static_cast<size_t>(level) >= m_levels.size()) {
      return nullptr;
    }
    return m_levels[level].data();
  }

  void MemoryAssetData::placement(int, int, int, uint64_t& offset, size_t& size) const {
    // Memory textures are never read through a file or package.
    offset = 0;
    size = 0;
  }

  bool MemoryAssetData::patch(uint32_t dstX, uint32_t dstY, uint32_t width, uint32_t height, const uint8_t* pixels, uint32_t rowPitch) {
    std::lock_guard<std::mutex> lock(m_mutex);
    const VkExtent2D extent = m_extents[0];
    if (dstX + width > extent.width || dstY + height > extent.height) {
      return false;
    }

    const size_t rowBytes = static_cast<size_t>(width) * m_format.bytesPerPixel;
    const size_t pitch = rowPitch ? rowPitch : rowBytes;
    for (uint32_t row = 0; row < height; ++row) {
      std::memcpy(m_levels[0].data() + (static_cast<size_t>(dstY + row) * extent.width + dstX) * m_format.bytesPerPixel,
                  pixels + row * pitch, rowBytes);
    }

    generateMips();
    return true;
  }

  ExternalTextureRegistry& ExternalTextureRegistry::get() {
    static ExternalTextureRegistry s_instance;
    return s_instance;
  }

  void* ExternalTextureRegistry::add(std::shared_ptr<ExternalTexture> texture) {
    std::lock_guard<std::mutex> lock(m_mutex);
    void* handle = reinterpret_cast<void*>(static_cast<uintptr_t>(m_nextId++ << 4));
    m_textures.emplace(handle, std::move(texture));
    return handle;
  }

  std::shared_ptr<ExternalTexture> ExternalTextureRegistry::find(void* handle) {
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto it = m_textures.find(handle);
    return it == m_textures.end() ? nullptr : it->second;
  }

  bool ExternalTextureRegistry::remove(void* handle) {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_textures.erase(handle) > 0;
  }

  TextureRef ExternalTextureRegistry::resolve(DxvkContext& ctx, void* handle) {
    if (handle == nullptr) {
      return {};
    }

    const auto texture = find(handle);
    if (!texture) {
      return {};
    }

    if (texture->managed == nullptr) {
      texture->managed = ctx.getCommonObjects()->getTextureManager().preloadTextureAsset(texture->asset, ColorSpace::AUTO, false);
    }
    return TextureRef { texture->managed };
  }

}
