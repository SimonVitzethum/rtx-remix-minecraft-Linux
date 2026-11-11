/*
* Copyright (c) 2023, NVIDIA CORPORATION. All rights reserved.
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
#include <unordered_map>
#include <vector>

#include "dxvk_format.h"
#include "rtx_asset_data.h"
#include "rtx_asset_package.h"
#include "rtx_texture_upload.h"

namespace dxvk {

  class PackagedAssetData : public AssetData {
  public:
    PackagedAssetData() = delete;
    PackagedAssetData(const Rc<AssetPackage>& package, uint32_t assetIdx)
    : m_package(package)
    , m_assetIdx(assetIdx) {
      m_assetDesc = package->getAssetDesc(assetIdx);

      if (m_assetDesc == nullptr) {
        throw DxvkError("Asset description was not found in the package!");
      }

      if (!m_assetDesc->numMips || m_assetDesc->numMips > 32
          || m_assetDesc->numTailMips > m_assetDesc->numMips || !m_assetDesc->arraySize) {
        throw DxvkError("Invalid packaged texture mip or layer count");
      }

      m_info.type = type();
      m_info.compression = compression();
      m_info.format = static_cast<VkFormat>(m_assetDesc->format);
      m_info.extent = extent(0);
      m_info.mipLevels = m_assetDesc->numMips;
      // At the moment RTX IO can only load mip tail all at once,
      // i.e. we need to upload a mip amount of max(N, numTailMips).
      m_info.mininumLevelsToUpload = std::clamp<uint16_t>(m_assetDesc->numTailMips, 1, m_assetDesc->numMips);
      m_info.numLayers = m_assetDesc->arraySize;
      m_info.lastWriteTime = std::filesystem::last_write_time(m_package->getFilename());
      m_info.filename = m_package->getFilename().c_str();

      const auto& filename = m_package->getFilename();
      m_hash = XXH3_64bits(filename.data(), filename.size());
      m_hash ^= XXH3_64bits(&m_assetIdx, sizeof(m_assetIdx));
    }

    AssetType type() const {
      switch (m_assetDesc->type) {
      case AssetPackage::AssetDesc::Type::BUFFER:
        return AssetType::Buffer;
      case AssetPackage::AssetDesc::Type::IMAGE_1D:
        return AssetType::Image1D;
      case AssetPackage::AssetDesc::Type::IMAGE_2D:
      case AssetPackage::AssetDesc::Type::IMAGE_CUBE:
        return AssetType::Image2D;
      case AssetPackage::AssetDesc::Type::IMAGE_3D:
        return AssetType::Image3D;
      case AssetPackage::AssetDesc::Type::UNKNOWN:
        break;
      }

      assert(0 && "Unknown asset type");

      return AssetType::Unknown;
    }

    AssetCompression compression() const {
      const auto blobDesc = m_package->getDataBlobDesc(
        m_assetDesc->baseBlobIdx);

      if (!blobDesc) {
        throw DxvkError("Asset base blob was not found in the package");
      }

      // We support only the GDeflate compression method atm
      return blobDesc->compression != 0 ?
        AssetCompression::GDeflate : AssetCompression::None;
    }

    VkExtent3D extent(int level) const {
      if (type() == AssetType::Buffer) {
        return VkExtent3D { m_assetDesc->size, 0, 1 };
      }

      return VkExtent3D{
        std::max<uint32_t>(m_assetDesc->width >> level, 1u),
        std::max<uint32_t>(m_assetDesc->height >> level, 1u),
        std::max<uint32_t>(m_assetDesc->depth >> level, 1u)
      };
    }

    const void* data(int layer, int level) override {
      const auto range = cpuMipRange(layer, level);
      if (!range.size) {
        return nullptr;
      }
      const uint32_t blobIdx = range.blob;

      const auto& it = m_data.find(blobIdx);
      if (it != m_data.end()) {
        return it->second.data() + range.offset;
      }

      if (auto blobDesc = m_package->getDataBlobDesc(blobIdx)) {
        if (blobDesc->compression != 0) {
          throw DxvkError("Compressed data blobs are not supported for CPU readback.");
        }

        std::vector<uint8_t> data(blobDesc->size);
        if (m_package->readDataBlob(blobIdx, data.data(), data.size()) != data.size()) {
          return nullptr;
        }

        const auto [insertedIterator, insertionSuccessful] = m_data.try_emplace(blobIdx, std::move(data));

        // Note: Element with this blobIdx shouldn't exist due to being checked earlier, and because evicting the
        // cache erases the element fully. If in the future the vector is kept around in the map to reuse its memory
        // (due to using clear rather than freeing the memory fully) then this logic will have to change.
        assert(insertionSuccessful);

        return insertedIterator->second.data() + range.offset;
      }

      return nullptr;
    }

    bool copyData(int layer, int level, size_t offset, size_t size, void* destination) override {
      const auto range = cpuMipRange(layer, level);
      if (!range.size || !destination || offset > range.size || size > range.size - offset) {
        return false;
      }
      return m_package->readDataBlob(range.blob, destination, size, range.offset + offset) == size;
    }

    void evictCache(int layer, int level) override {
      const uint32_t blobIdx = getBlobIndex(layer, 0, level);

      // Note: Release the vector stored at the given blob index to free up its memory fully.
      m_data.erase(blobIdx);
    }

    void releaseSource() override {
    }

    void placement(
      int       layer,
      int       face,
      int       level,
      uint64_t& offset,
      size_t&   size) const override {

      uint32_t blobIdx = getBlobIndex(layer, face, level);

      if (auto blobDesc = m_package->getDataBlobDesc(blobIdx)) {
        offset = blobDesc->offset;
        size = blobDesc->size;
        return;
      }

      assert(0 && "Data blob was not found!");
    }

  private:
    struct CpuMipRange {
      uint32_t blob = AssetPackage::kNoAssetIdx;
      size_t offset = 0;
      size_t size = 0;
    };

    CpuMipRange cpuMipRange(int layer, int level) const {
      if (layer < 0 || uint32_t(layer) >= m_info.numLayers || level < 0 || uint32_t(level) >= m_info.mipLevels
          || m_info.mipLevels > 32 || m_assetDesc->numTailMips > m_info.mipLevels) {
        return {};
      }
      const uint32_t index = getBlobIndex(layer, 0, level);
      const auto* blob = m_package->getDataBlobDesc(index);
      const auto* format = imageFormatInfo(m_info.format);
      if (!blob || !format || !format->elementSize) {
        return {};
      }
      if (blob->compression) {
        throw DxvkError("Compressed data blobs are not supported for CPU readback.");
      }
      const auto mipBytes = [this, format](uint32_t mip) {
        const auto size = extent(mip);
        return TextureUploadLayout::make(size.width, size.height, size.depth,
          format->blockSize.width, format->blockSize.height, format->blockSize.depth, format->elementSize).layerBytes;
      };
      size_t offset = 0;
      // Tail mips are concatenated in one blob, largest to smallest.
      const uint32_t firstTail = m_info.mipLevels - m_assetDesc->numTailMips;
      for (uint32_t mip = firstTail; mip < uint32_t(level); ++mip) {
        offset = TextureUploadLayout::add(offset, mipBytes(mip));
      }
      const size_t size = mipBytes(level);
      if (offset > blob->size || size > blob->size - offset) {
        return {};
      }
      return { index, offset, size };
    }

    uint32_t getBlobIndex(int       layer,
                          int       face,
                          int       level) const {
      if (m_assetDesc->type == AssetPackage::AssetDesc::Type::BUFFER) {
        return m_assetDesc->baseBlobIdx;
      }

      if (m_assetDesc->type == AssetPackage::AssetDesc::Type::IMAGE_CUBE) {
        layer = layer * 6 + face;
      }

      const uint32_t numLooseMips =
        m_assetDesc->numMips - m_assetDesc->numTailMips;
      const uint32_t baseBlobIdx =
        level >= numLooseMips ? m_assetDesc->tailBlobIdx :
        level + m_assetDesc->baseBlobIdx;

      return baseBlobIdx + layer * numLooseMips;
    }

    Rc<AssetPackage> m_package;
    const AssetPackage::AssetDesc* m_assetDesc = nullptr;
    uint32_t m_assetIdx;

    std::unordered_map<uint32_t, std::vector<uint8_t>> m_data;
  };

} // namespace dxvk
