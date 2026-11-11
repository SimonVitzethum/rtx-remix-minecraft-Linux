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
#include "rtx_texture_upload.h"
#include "rtx_staging_ring.h"

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

  void MemoryAssetData::generateMips(uint32_t rx0, uint32_t ry0, uint32_t rx1, uint32_t ry1) {
    // The dirty rectangle (mip 0 texels, [rx0,rx1) x [ry0,ry1)) shrinks by half per level; only it is regenerated.
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

      rx1 = std::min(rx1, src.width); ry1 = std::min(ry1, src.height);
      rx0 >>= 1; ry0 >>= 1; rx1 = (rx1 + 1) >> 1; ry1 = (ry1 + 1) >> 1;
      const uint32_t yEnd = std::min(ry1, dst.height), xEnd = std::min(rx1, dst.width);
      for (uint32_t y = ry0; y < yEnd; ++y) {
        for (uint32_t x = rx0; x < xEnd; ++x) {
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

  bool MemoryAssetData::copyData(int layer, int level, size_t offset, size_t size, void* destination) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (layer != 0 || level < 0 || size_t(level) >= m_levels.size()) {
      return false;
    }
    const auto& source = m_levels[level];
    if (offset > source.size() || size > source.size() - offset) {
      return false;
    }
    std::memcpy(destination, source.data() + offset, size);
    return true;
  }

  void MemoryAssetData::placement(int, int, int, uint64_t& offset, size_t& size) const {
    // Memory textures are never read through a file or package.
    offset = 0;
    size = 0;
  }

  bool MemoryAssetData::patch(uint32_t dstX, uint32_t dstY, uint32_t width, uint32_t height, const uint8_t* pixels, uint32_t rowPitch) {
    std::lock_guard<std::mutex> lock(m_mutex);
    const VkExtent2D extent = m_extents[0];
    if (!pixels || !width || !height || dstX > extent.width || dstY > extent.height
        || width > extent.width - dstX || height > extent.height - dstY) {
      return false;
    }

    const size_t rowBytes = static_cast<size_t>(width) * m_format.bytesPerPixel;
    const size_t pitch = rowPitch ? rowPitch : rowBytes;
    if (pitch < rowBytes) {
      return false;
    }
    for (uint32_t row = 0; row < height; ++row) {
      std::memcpy(m_levels[0].data() + (static_cast<size_t>(dstY + row) * extent.width + dstX) * m_format.bytesPerPixel,
                  pixels + row * pitch, rowBytes);
    }

    generateMips(dstX, dstY, dstX + width, dstY + height);

    // Record the same region per mip level that generateMips rewrote.
    m_dirty.resize(m_levels.size());
    uint32_t rx0 = dstX, ry0 = dstY, rx1 = dstX + width, ry1 = dstY + height;
    for (size_t level = 0; level < m_levels.size(); ++level) {
      if (level > 0) {
        const VkExtent2D src = m_extents[level - 1];
        rx1 = std::min(rx1, src.width); ry1 = std::min(ry1, src.height);
        rx0 >>= 1; ry0 >>= 1; rx1 = (rx1 + 1) >> 1; ry1 = (ry1 + 1) >> 1;
      }
      const VkExtent2D ext = m_extents[level];
      const uint32_t ex1 = std::min(rx1, ext.width), ey1 = std::min(ry1, ext.height);
      if (rx0 >= ex1 || ry0 >= ey1) {
        continue;
      }
      // Animated tiles are scattered over a huge atlas: keep separate rectangles (a union would cover most of it) and
      // only merge a rectangle into another when they overlap or touch. Beyond 256 rectangles everything is merged.
      auto& rects = m_dirty[level];
      DirtyRect n = { rx0, ry0, ex1, ey1, true };
      for (bool merged = true; merged;) {
        merged = false;
        for (size_t i = 0; i < rects.size(); ++i) {
          const DirtyRect& r = rects[i];
          if (n.x0 <= r.x1 && r.x0 <= n.x1 && n.y0 <= r.y1 && r.y0 <= n.y1) {
            n = { std::min(n.x0, r.x0), std::min(n.y0, r.y0), std::max(n.x1, r.x1), std::max(n.y1, r.y1), true };
            rects.erase(rects.begin() + i);
            merged = true;
            break;
          }
        }
      }
      rects.push_back(n);
      if (rects.size() > 256) {
        DirtyRect u = rects[0];
        for (const auto& r : rects) {
          u = { std::min(u.x0, r.x0), std::min(u.y0, r.y0), std::max(u.x1, r.x1), std::max(u.y1, r.y1), true };
        }
        rects.assign(1, u);
      }
    }
    return true;
  }

  void MemoryAssetData::clearDirty() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_dirty.clear();
  }

  MemoryAssetData::DirtyUploadResult MemoryAssetData::uploadDirty(DxvkContext* ctx, const Rc<DxvkImage>& image, size_t byteBudget, RtxStagingRing* staging) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (image == nullptr || image->info().mipLevels != m_levels.size() || image->info().format != m_info.format
        || image->info().extent.width != m_extents[0].width || image->info().extent.height != m_extents[0].height) {
      return { false, false, 0 };
    }
    size_t copied = 0;
    for (size_t level = 0; level < m_dirty.size(); ++level) {
      auto& rects = m_dirty[level];
      while (!rects.empty()) {
        auto& rect = rects.front();
        const auto layout = TextureUploadLayout::make(rect.x1 - rect.x0, rect.y1 - rect.y0, 1,
          1, 1, 1, m_format.bytesPerPixel);
        const auto band = layout.band(0, 0, staging ? std::min(byteBudget - copied, size_t(staging->budget())) : byteBudget - copied);
        if (!band.bytes) {
          return { true, false, copied };
        }
        const uint32_t levelWidth = m_extents[level].width;
        const uint8_t* source = m_levels[level].data() + (size_t(rect.y0) * levelWidth + rect.x0) * m_format.bytesPerPixel;
        VkImageSubresourceLayers layers = { VK_IMAGE_ASPECT_COLOR_BIT, uint32_t(level), 0, 1 };
        const VkOffset3D offset{ int32_t(rect.x0), int32_t(rect.y0), 0 };
        const VkExtent3D extent{ rect.x1 - rect.x0, band.rows, 1 };
        if (staging) {
          const auto allocation = staging->alloc(CACHE_LINE_SIZE, band.bytes);
          if (!allocation.slice.defined()) {
            return { true, false, copied };
          }
          auto* destination = static_cast<uint8_t*>(allocation.slice.mapPtr(0));
          for (uint32_t row = 0; row < band.rows; ++row) {
            std::memcpy(destination + size_t(row) * layout.rowBytes,
              source + size_t(row) * levelWidth * m_format.bytesPerPixel, layout.rowBytes);
          }
          ctx->copyBufferToImage(image, layers, offset, extent,
            allocation.slice.buffer(), allocation.slice.offset(), 1, 1);
        } else {
          ctx->updateImage(image, layers, offset, extent,
            source, VkDeviceSize(levelWidth) * m_format.bytesPerPixel, 0);
        }
        copied += band.bytes;
        rect.y0 += band.rows;
        if (rect.y0 == rect.y1) {
          rects.erase(rects.begin());
        }
      }
    }
    m_dirty.clear();
    return { true, true, copied };
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

  void ExternalTextureRegistry::clear() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_textures.clear();
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
