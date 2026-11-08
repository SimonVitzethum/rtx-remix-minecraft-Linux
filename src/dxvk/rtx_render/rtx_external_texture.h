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
#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "rtx_asset_data.h"
#include "rtx_texture.h"

// Textures created through the Remix API, either from memory or from a file.
// The API hands out opaque handles. The registry maps a handle to the asset data that backs the texture and, once a
// material has used the texture on the render thread, to the managed GPU texture.

namespace dxvk {

  class DxvkContext;

  // Pixel layouts that can be created from memory.
  struct ExternalTextureFormat {
    VkFormat vkFormat = VK_FORMAT_UNDEFINED;
    uint32_t bytesPerPixel = 0;
    uint32_t channels = 0;
    bool isFloat16 = false;
    bool isFloat32 = false;
    bool isSrgb = false;
  };

  // Maps a remixapi_TextureFormat value to its layout, returns false for unknown values.
  bool getExternalTextureFormat(uint32_t remixapiFormat, ExternalTextureFormat& out);

  // Asset data backed by CPU memory. The full mip chain is generated on creation.
  class MemoryAssetData : public AssetData {
  public:
    MemoryAssetData(uint64_t hash,
                    const ExternalTextureFormat& format,
                    uint32_t width,
                    uint32_t height,
                    const uint8_t* pixels,
                    bool useMaxFilter);

    const void* data(int layer, int level) override;
    void placement(int layer, int face, int level, uint64_t& offset, size_t& size) const override;
    void evictCache(int, int) override { }
    void releaseSource() override { }

    // Overwrites a rectangle of mip 0 and regenerates the mip chain. Pixel data must be in the texture's format.
    bool patch(uint32_t dstX, uint32_t dstY, uint32_t width, uint32_t height, const uint8_t* pixels, uint32_t rowPitch);

  private:
    void generateMips(uint32_t rx0 = 0, uint32_t ry0 = 0, uint32_t rx1 = UINT32_MAX, uint32_t ry1 = UINT32_MAX);

    ExternalTextureFormat m_format;
    bool m_useMaxFilter;
    std::vector<std::vector<uint8_t>> m_levels;
    std::vector<VkExtent2D> m_extents;
    std::mutex m_mutex;
  };

  struct ExternalTexture {
    Rc<AssetData> asset;
    Rc<MemoryAssetData> memory;     // Same object as asset for memory textures, null for file textures.
    Rc<ManagedTexture> managed;     // Created lazily on the render thread.
  };

  class ExternalTextureRegistry {
  public:
    static ExternalTextureRegistry& get();

    void* add(std::shared_ptr<ExternalTexture> texture);
    std::shared_ptr<ExternalTexture> find(void* handle);
    bool remove(void* handle);
    void clear();

    // Returns the GPU texture for a handle, creating it on first use. Must be called on the render thread.
    TextureRef resolve(DxvkContext& ctx, void* handle);

  private:
    std::mutex m_mutex;
    std::unordered_map<void*, std::shared_ptr<ExternalTexture>> m_textures;
    uint64_t m_nextId = 1;
  };

}
