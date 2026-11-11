/*
* Copyright (c) 2022-2023, NVIDIA CORPORATION. All rights reserved.
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

#include <chrono>
#include <deque>
#include <memory>

#include "dxvk_device.h"
#include "rtx_texture_manager.h"
#include "../../util/thread.h"
#include "../../util/rc/util_rc_ptr.h"
#include "dxvk_context.h"
#include "dxvk_scoped_annotation.h"

#include "rtx_asset_data_manager.h"
#include "rtx_bindless_resource_manager.h"
#include "rtx_file_watch.h"
#include "rtx_texture.h"
#include "rtx_io.h"
#include "rtx_staging_ring.h"
#include "rtx_texture_upload.h"
#include "rtx_external_texture.h"


namespace dxvk {


  size_t g_streamedTextures_budgetBytes = 0;
  size_t g_streamedTextures_usedBytes   = 0;

  constexpr size_t Megabytes = 1024 * 1024;

  static size_t calcTextureMemoryBudget_Megabytes(DxvkDevice* device);
  
  // Returns current VRAM usage by material textures in bytes
  static size_t calcCurrentTextureUsageBytes(DxvkDevice* device) {
    size_t usageBytes = 0;
    for (uint32_t i = 0; i < device->adapter()->memoryProperties().memoryHeapCount; i++) {
      const bool isDeviceLocal = device->adapter()->memoryProperties().memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
      if (isDeviceLocal) {
        usageBytes += device->getMemoryStats(i).usedByCategory(DxvkMemoryStats::Category::RTXMaterialTexture);
      }
    }
    return usageBytes;
  }
  
  // Returns texture memory budget in bytes
  static size_t calcTextureMemoryBudgetBytes(DxvkDevice* device) {
    return calcTextureMemoryBudget_Megabytes(device) * Megabytes;
  }
  
  // Returns true if current texture VRAM usage exceeds budget
  static bool isOverTextureBudget(DxvkDevice* device) {
    return calcCurrentTextureUsageBytes(device) > calcTextureMemoryBudgetBytes(device);
  }

  // This byte limit bounds packing work and transfer traffic in one frame.
  constexpr size_t kUploadBytesPerFrame = 8 * Megabytes;

  static size_t stagingBufferSize_Bytes() {
    return std::min(size_t(std::max(1, RtxOptions::TextureManager::stagingBufferSizeMiB())) * Megabytes,
                    kUploadBytesPerFrame);
  }


#define SAMPLER_FEEDBACK_RELATED_PER_TEX 8


  namespace {

    // Remains private until all bands are recorded. The asset snapshot survives hot reload.
    struct TextureUpload {
      TextureUpload(const Rc<ManagedTexture>& texture, const Rc<AssetData>& asset,
                    const DxvkImageCreateInfo& imageInfo, uint16_t begin, uint16_t end, size_t bytes)
        : texture(texture), asset(asset), imageInfo(imageInfo), mipBegin(begin), mipEnd(end), bytes(bytes) { }
      Rc<ManagedTexture> texture;
      Rc<AssetData> asset;
      DxvkImageCreateInfo imageInfo;
      uint16_t mipBegin;
      uint16_t mipEnd;
      size_t bytes;
      Rc<DxvkImageView> image;
      size_t deviceBytes = 0; // Consumer thread only.
      bool budgetWarning = false;
      const std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();
      std::atomic<bool> cancelled{ false };
      std::atomic<bool> failed{ false };
      std::atomic<bool> admitted{ false };
    };

    struct ReadyToCopyMip {
      DxvkBufferSlice srcBuffer;
      VkExtent3D mipExtent;
      uint32_t mipLevel;
      uint32_t layer;
      VkOffset3D offset;
    };

    struct ReadyToCopy {
      std::shared_ptr<TextureUpload> upload;
      std::vector<ReadyToCopyMip> mips;
      std::shared_ptr<RtxStagingRing::Allocation::Lease> lease;
      size_t bytes = 0;
      bool complete = true;
    };

    struct ReadyToCopy_RTXIO {
      Rc<ManagedTexture> dstTexture;
      Rc<DxvkImageView> rtxioDst;
      uint16_t mip_begin;
      uint16_t mip_end;
    };

    TextureUploadLayout uploadLayout(const AssetData& asset, uint32_t level) {
      const auto* format = imageFormatInfo(asset.info().format);
      if (!format || !asset.info().numLayers) {
        throw DxvkError("Invalid texture upload format or layers");
      }
      const auto extent = util::computeMipLevelExtent(asset.info().extent, level);
      return TextureUploadLayout::make(extent.width, extent.height, extent.depth,
        format->blockSize.width, format->blockSize.height, format->blockSize.depth, format->elementSize);
    }

    size_t calcSizeForAsset(const AssetData& asset, uint32_t begin, uint32_t end) {
      if (begin > end || end > asset.info().mipLevels || end > MAX_MIPS) {
        throw DxvkError("Invalid texture upload mip range");
      }
      size_t bytes = 0;
      for (uint32_t level = begin; level < end; ++level) {
        const auto layout = uploadLayout(asset, level);
        const size_t aligned = TextureUploadLayout::add(layout.layerBytes, CACHE_LINE_SIZE - 1)
          & ~(size_t(CACHE_LINE_SIZE) - 1);
        bytes = TextureUploadLayout::add(bytes, TextureUploadLayout::multiply(aligned, asset.info().numLayers));
      }
      return bytes;
    }

    std::shared_ptr<TextureUpload> planUpload(const Rc<ManagedTexture>& texture) {
      const auto [begin, end] = texture->calcRequiredMips_BeginEnd();
      return std::make_shared<TextureUpload>(
        texture, texture->m_assetData, texture->imageCreateInfo(), begin, end,
        calcSizeForAsset(*texture->m_assetData, begin, end));
    }

    // Small mip chains share one slice. The same layouts drive the banded path.
    ReadyToCopy makeStagingForTextureAsset(RtxStagingRing& allocator,
                                           const std::shared_ptr<TextureUpload>& upload) {
      ScopedCpuProfileZone();
      ReadyToCopy ready;
      if (!upload->bytes) {
        ready.upload = upload;
        return ready;
      }
      const auto allocation = allocator.alloc(CACHE_LINE_SIZE, upload->bytes);
      const auto& staging = allocation.slice;
      if (!staging.defined()) {
        return {};
      }
      ready.lease = allocation.lease;
      size_t offset = 0;
      for (uint32_t level = upload->mipBegin; level < upload->mipEnd; ++level) {
        const auto layout = uploadLayout(*upload->asset, level);
        const auto extent = util::computeMipLevelExtent(upload->asset->info().extent, level);
        for (uint32_t layer = 0; layer < upload->asset->info().numLayers; ++layer) {
          const auto slice = staging.subSlice(offset, layout.layerBytes);
          if (!upload->asset->copyData(layer, level, 0, layout.layerBytes, slice.mapPtr(0))) {
            throw DxvkError("Cannot read texture upload data");
          }
          ready.mips.push_back({ slice, extent, level - upload->mipBegin, layer, { 0, 0, 0 } });
          offset += dxvk::align(layout.layerBytes, size_t(CACHE_LINE_SIZE));
          upload->asset->evictCache(layer, level);
        }
      }
      upload->asset->releaseSource();
      ready.upload = upload;
      ready.bytes = upload->bytes;
      return ready;
    }


    const char* makeDebugTextureName(const char* filename) {
      if (filename) {
        const char* lastSlash     = strrchr(filename, '/');
        const char* lastBackslash = strrchr(filename, '\\');
        const char* lastSeparator = (lastSlash > lastBackslash) ? lastSlash : lastBackslash;
        if (lastSeparator) {
          assert(*(lastSeparator + 1) != '\0');
          return lastSeparator + 1;
        }
      }
      return filename;
    }


    Rc<DxvkImageView> allocDeviceImage(
      DxvkDevice* device,
      const Rc<AssetData>& asset,
      DxvkImageCreateInfo desc,
      const uint32_t mipLevels_begin,
      const uint32_t mipLevels_end // non-inclusive
    ) {
      assert(mipLevels_begin < mipLevels_end);
      const uint32_t mipLevels = mipLevels_end - mipLevels_begin;

      desc.extent = util::computeMipLevelExtent(asset->info().extent, mipLevels_begin);
      desc.mipLevels = mipLevels;
      desc.layout = VK_IMAGE_LAYOUT_UNDEFINED;

#ifndef NDEBUG
      const char* debugName = makeDebugTextureName(asset->info().filename);
#else
      const char* debugName = "material texture";
#endif

      Rc<DxvkImage> imgAlloc = device->createImage(
        desc,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXMaterialTexture,
        debugName);

      DxvkImageViewCreateInfo viewInfo{};
      {
        viewInfo.type = desc.numLayers > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        viewInfo.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.minLevel = 0;
        viewInfo.numLevels = mipLevels;
        viewInfo.minLayer = 0;
        viewInfo.numLayers = desc.numLayers;
        viewInfo.format = desc.format;
      }
      return device->createImageView(imgAlloc, viewInfo);
    }


    // Query the driver before committing VRAM, including tiling and allocation granularity.
    size_t calcDeviceImageBytes(DxvkDevice* device, const TextureUpload& upload) {
      const auto& desc = upload.imageInfo;
      VkImageFormatListCreateInfo formats{ VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO };
      formats.viewFormatCount = desc.viewFormatCount;
      formats.pViewFormats = desc.viewFormats;
      VkImageCreateInfo info{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
      info.pNext = &formats;
      info.flags = desc.flags;
      info.imageType = desc.type;
      info.format = desc.format;
      info.extent = util::computeMipLevelExtent(upload.asset->info().extent, upload.mipBegin);
      info.mipLevels = upload.mipEnd - upload.mipBegin;
      info.arrayLayers = desc.numLayers;
      info.samples = desc.sampleCount;
      info.tiling = desc.tiling;
      info.usage = desc.usage;
      info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      info.initialLayout = desc.initialLayout;
      const auto vkd = device->vkd();
      VkImage image = VK_NULL_HANDLE;
      if (vkd->vkCreateImage(vkd->device(), &info, nullptr, &image) != VK_SUCCESS) {
        throw DxvkError("Cannot query texture upload image memory requirements");
      }
      VkMemoryRequirements requirements;
      vkd->vkGetImageMemoryRequirements(vkd->device(), image, &requirements);
      vkd->vkDestroyImage(vkd->device(), image, nullptr);
      const size_t granularity = device->properties().core.properties.limits.bufferImageGranularity;
      return TextureUploadLayout::add(requirements.size, granularity - 1) & ~(granularity - 1);
    }


    void copyStagingToDevice(DxvkContext* ctx,
                             DxvkBarrierSet& execBarriers,
                             DxvkBarrierSet& execAcquires,
                             const ReadyToCopy& ready) {
      Rc<DxvkCommandList> cmd = ctx->getCommandList();

      const Rc<DxvkImage>& image = ready.upload->image->image();

      if (image->info().layout == VK_IMAGE_LAYOUT_UNDEFINED) {
        ctx->changeImageLayout(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      }

      for (const ReadyToCopyMip& level : ready.mips) {

        const auto subresources = VkImageSubresourceLayers{
          VK_IMAGE_ASPECT_COLOR_BIT,
          level.mipLevel,
          level.layer,
          1,
        };

        const auto stagingHandle = level.srcBuffer.getSliceHandle();

        // from DxvkContext::updateImage

        // Prepare the image layout. If the given extent covers
        // the entire image, we may discard its previous contents.
        auto subresourceRange = vk::makeSubresourceRange(subresources);
        {
          subresourceRange.aspectMask = image->formatInfo()->aspectMask;
        }

        ctx->prepareImage(execBarriers, image, subresourceRange);

        if (execBarriers.isImageDirty(image, subresourceRange, DxvkAccess::Write)) {
          execBarriers.recordCommands(cmd);
        }

        // Initialize the image if the entire subresource is covered
        VkImageLayout imageLayoutInitial = image->info().layout;
        VkImageLayout imageLayoutTransfer = image->pickLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        if (level.offset.x == 0 && level.offset.y == 0 && level.offset.z == 0
            && image->isFullSubresource(subresources, level.mipExtent)) {
          imageLayoutInitial = VK_IMAGE_LAYOUT_UNDEFINED;
        }

        if (imageLayoutTransfer != imageLayoutInitial) {
          execAcquires.accessImage(
            image,
            subresourceRange,
            imageLayoutInitial,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            imageLayoutTransfer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT);
        }

        execAcquires.recordCommands(cmd);

        // Copy contents of the staging buffer into the image.
        // Since our source data is tightly packed, we do not
        // need to specify any strides.
        VkBufferImageCopy region;
        {
          region.bufferOffset = stagingHandle.offset;
          region.bufferRowLength = 0;
          region.bufferImageHeight = 0;
          region.imageSubresource = subresources;
          region.imageOffset = level.offset;
          region.imageExtent = level.mipExtent;
        }

        cmd->cmdCopyBufferToImage(
          DxvkCmdBuffer::ExecBuffer,
          stagingHandle.handle,
          image->handle(),
          imageLayoutTransfer,
          1,
          &region);

        // Transition image back into its optimal layout
        execBarriers.accessImage(
          image,
          subresourceRange,
          imageLayoutTransfer,
          VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_ACCESS_TRANSFER_WRITE_BIT,
          image->info().layout,
          image->info().stages,
          image->info().access);

        cmd->trackResource<DxvkAccess::Read>(level.srcBuffer.buffer());
      }

      cmd->trackResource<DxvkAccess::Write>(ready.upload->image);
    }


    void flushRtxIo(bool async) {
#ifdef WITH_RTXIO
      if (RtxIo::enabled()) {
        RtxIo::get().flush(async);
      }
#endif
    }


    template<typename T, typename Hasher, typename Predicate>
    void erase_if(std::unordered_set<T, Hasher>& target, Predicate eraseIfTrue) {
      for (auto first = target.begin(), last = target.end(); first != last;) {
        if (eraseIfTrue(*first)) {
          first = target.erase(first);
        } else {
          ++first;
        }
      }
    }


    struct RcHasher { size_t operator()(const Rc<ManagedTexture>& s) const { return (size_t)s.ptr(); } };

  } // unnamed namespace


  // AsyncRunner begin


  // Spawns a low-priority thread that loads files, allocates the staging memory for them with a fixed-size allocator,
  // and returns ready-to-copy mip-chains to the Vulkan thread.
  // Enforces strong limits on allocator and amount of textures sent to Vulkan thread, to avoid stutter.
  struct AsyncRunner {

    static constexpr uint32_t MAX_TEXTURE_UPLOADS_PER_FRAME = 32;

    explicit AsyncRunner(const Rc<DxvkDevice>& device)
      : m_ringbuf{ device, stagingBufferSize_Bytes() }
      , m_synchronousAlloc{ device, std::min(stagingBufferSize_Bytes(), 4 * Megabytes) }
      , m_requiresShutdown{ false }
      , m_thread{ dxvk::thread{ [this] { this->asyncLoop(); } } }
    {
      m_thread.set_priority(ThreadPriority::Lowest);
    }

    ~AsyncRunner() {
      if (!m_requiresShutdown.load()) {
        {
          // Both worker waits re-check m_requiresShutdown under their own mutex, so the flag is
          // published under both: a store made outside a wait mutex can land between a false
          // predicate and the worker sleeping, and the notify would then be dropped. The
          // ready-upload wait is otherwise only released by the render thread
          // (submitTexturesToDeviceLocal), which no longer runs during teardown.
          auto lProcess = std::unique_lock{ m_texturesToProcess_mutex };
          auto lReady = std::unique_lock{ m_readyTextures_mutex };
          m_requiresShutdown.store(true);
        }
        m_texturesToProcess_cond.notify_all();
        m_readyTextures_cond.notify_all();
      }
      if (m_thread.joinable()) {
        m_thread.join();
      }
    }

    AsyncRunner(const AsyncRunner&) = delete;
    AsyncRunner(AsyncRunner&&) noexcept = delete;
    AsyncRunner& operator=(const AsyncRunner&) = delete;
    AsyncRunner& operator=(AsyncRunner&&) noexcept = delete;

    void queueAdd(const Rc<ManagedTexture>& tex, bool allowAsync);
    std::vector<ReadyToCopy> retrieveReadyToUploadTextures();

    void deferUploads(std::vector<ReadyToCopy> uploads);

    void admitUpload(TextureUpload& upload) {
      auto lock = std::unique_lock{ m_texturesToProcess_mutex };
      upload.admitted = true;
      m_texturesToProcess_cond.notify_one();
    }

    bool hasPendingUploads() {
      auto process = std::unique_lock{ m_texturesToProcess_mutex };
      auto ready = std::unique_lock{ m_readyTextures_mutex };
      return m_processing || !m_texturesToProcess.empty() || !m_readyTextures.empty();
    }

    MemoryAssetData::DirtyUploadResult reconcileDirty(DxvkContext* ctx, MemoryAssetData& memory,
                                                     const Rc<DxvkImage>& image, size_t budget) {
      auto lock = std::unique_lock{ m_immediateMutex, std::try_to_lock };
      if (!lock.owns_lock()) {
        return { true, false, 0 };
      }
      m_synchronousAlloc.trim();
      return memory.uploadDirty(ctx, image, budget, &m_synchronousAlloc);
    }

  private:
    void asyncLoop(); // boilerplate

  private:
    // has a limited budget, returns nothing if fails
    RtxStagingRing            m_ringbuf;

    // Small immediate requests have their own bounded pool so they cannot wait on the producer.
    RtxStagingRing            m_synchronousAlloc;
    dxvk::mutex               m_immediateMutex;

    std::atomic<bool>         m_requiresShutdown { false };
    std::atomic<bool>         m_processing { false };
    dxvk::mutex               m_texturesToProcess_mutex;
    dxvk::condition_variable  m_texturesToProcess_cond;
    std::deque<std::shared_ptr<TextureUpload>> m_texturesToProcess;

    dxvk::mutex               m_readyTextures_mutex;
    dxvk::condition_variable  m_readyTextures_cond;
    std::vector<ReadyToCopy>  m_readyTextures;
    dxvk::thread              m_thread;
  };


  void AsyncRunner::queueAdd(const Rc<ManagedTexture>& tex, bool async) {
    assert(tex->m_state == ManagedTexture::State::kQueuedForUpload);
    auto upload = planUpload(tex);
    if (!upload->bytes) {
      ReadyToCopy ready;
      ready.upload = std::move(upload);
      auto lock = std::unique_lock{ m_readyTextures_mutex };
      m_readyTextures.push_back(std::move(ready));
      return;
    }
    if (!async && upload->bytes <= m_synchronousAlloc.budget()) {
      auto immediate = std::unique_lock{ m_immediateMutex, std::try_to_lock };
      if (immediate.owns_lock()) {
        m_synchronousAlloc.trim();
        auto ready = makeStagingForTextureAsset(m_synchronousAlloc, upload);
        if (ready.upload) {
          auto lock = std::unique_lock{ m_readyTextures_mutex };
          m_readyTextures.push_back(std::move(ready));
          return;
        }
      }
    }
    auto process = std::unique_lock{ m_texturesToProcess_mutex };
    auto ready = std::unique_lock{ m_readyTextures_mutex };
    m_texturesToProcess.push_back(upload);
    ReadyToCopy admission;
    admission.upload = std::move(upload);
    admission.complete = false;
    m_readyTextures.push_back(std::move(admission));
    m_readyTextures_cond.notify_one();
  }


  std::vector<ReadyToCopy> AsyncRunner::retrieveReadyToUploadTextures() {
    {
      auto immediate = std::unique_lock{ m_immediateMutex, std::try_to_lock };
      if (immediate.owns_lock()) {
        m_synchronousAlloc.trim();
      }
    }
    auto lock = std::unique_lock{ m_readyTextures_mutex };
    std::vector<ReadyToCopy> ready;
    size_t bytes = 0;
    auto end = m_readyTextures.begin();
    for (; end != m_readyTextures.end() && ready.size() < MAX_TEXTURE_UPLOADS_PER_FRAME; ++end) {
      if (end->bytes > kUploadBytesPerFrame - bytes) {
        break;
      }
      bytes += end->bytes;
      ready.push_back(std::move(*end));
    }
    m_readyTextures.erase(m_readyTextures.begin(), end);
    m_readyTextures_cond.notify_one();
    return ready;
  }


  void AsyncRunner::deferUploads(std::vector<ReadyToCopy> uploads) {
    auto lock = std::unique_lock{ m_readyTextures_mutex };
    m_readyTextures.insert(m_readyTextures.end(),
      std::make_move_iterator(uploads.begin()), std::make_move_iterator(uploads.end()));
  }


  void AsyncRunner::asyncLoop() {
    env::setThreadName("rtx-texture-async");
    while (!m_requiresShutdown.load()) {
      std::shared_ptr<TextureUpload> upload;
      {
        auto lock = std::unique_lock{ m_texturesToProcess_mutex };
        const auto eligible = [](const auto& item) { return item->admitted.load() || item->cancelled.load(); };
        // Admission requests hold no staging or source cache while waiting for device memory.
        m_texturesToProcess_cond.wait_for(lock, std::chrono::milliseconds(10), [this, &eligible]() {
          return m_requiresShutdown.load() || std::any_of(m_texturesToProcess.begin(), m_texturesToProcess.end(), eligible);
        });
        if (m_requiresShutdown.load()) {
          return;
        }
        const auto next = std::find_if(m_texturesToProcess.begin(), m_texturesToProcess.end(), eligible);
        if (next == m_texturesToProcess.end()) {
          m_ringbuf.trim();
          continue;
        }
        upload = std::move(*next);
        m_texturesToProcess.erase(next);
        m_processing = true;
      }

      const auto fail = [this, &upload](const std::string& message) {
        if (!upload->cancelled.load() && !m_requiresShutdown.load()) {
          Logger::err(str::format("[TextureStaging] Upload failed: ", message));
          upload->failed = true;
          ReadyToCopy failure;
          failure.upload = upload;
          failure.complete = false;
          auto lock = std::unique_lock{ m_readyTextures_mutex };
          m_readyTextures.push_back(std::move(failure));
        }
      };

      try {
        const auto publish = [this](ReadyToCopy ready) {
          auto lock = std::unique_lock{ m_readyTextures_mutex };
          m_readyTextures_cond.wait(lock, [this]() {
            return m_readyTextures.size() < MAX_TEXTURE_UPLOADS_PER_FRAME || m_requiresShutdown.load();
          });
          if (!m_requiresShutdown.load()) {
            m_readyTextures.push_back(std::move(ready));
          }
        };
        const auto allocate = [this, &upload](size_t bytes) {
          RtxStagingRing::Allocation allocation;
          while (!m_requiresShutdown.load() && !upload->cancelled.load()) {
            allocation = m_ringbuf.alloc(CACHE_LINE_SIZE, bytes);
            if (allocation.slice.defined()) {
              break;
            }
            auto lock = std::unique_lock{ m_readyTextures_mutex };
            m_readyTextures_cond.wait_for(lock, std::chrono::milliseconds(2));
          }
          return allocation;
        };

        if (upload->bytes <= m_ringbuf.budget()) {
          ReadyToCopy ready;
          while (!m_requiresShutdown.load() && !upload->cancelled.load()) {
            ready = makeStagingForTextureAsset(m_ringbuf, upload);
            if (ready.upload) {
              break;
            }
            auto lock = std::unique_lock{ m_readyTextures_mutex };
            m_readyTextures_cond.wait_for(lock, std::chrono::milliseconds(2));
          }
          if (ready.upload) {
            publish(std::move(ready));
          }
        } else {
          const auto start = std::chrono::steady_clock::now();
          size_t bandCount = 0;
          size_t peakBytes = 0;
          for (uint32_t level = upload->mipBegin; level < upload->mipEnd; ++level) {
            const auto layout = uploadLayout(*upload->asset, level);
            if (!layout.band(0, 0, m_ringbuf.budget()).bytes) {
              throw DxvkError("Texture block row exceeds staging budget");
            }
            for (uint32_t layer = 0; layer < upload->asset->info().numLayers; ++layer) {
              for (uint32_t z = 0; z < layout.slices; ++z) {
                for (uint32_t row = 0; row < layout.rows;) {
                  const auto band = layout.band(row, z, m_ringbuf.budget());
                  const size_t bytes = band.bytes;
                  const auto allocation = allocate(bytes);
                  const auto& slice = allocation.slice;
                  if (!slice.defined()) {
                    throw DxvkError("Texture upload cancelled");
                  }
                  ReadyToCopy ready;
                  ready.lease = allocation.lease;
                  if (!upload->asset->copyData(layer, level, band.offset, bytes, slice.mapPtr(0))) {
                    throw DxvkError("Cannot read texture upload band");
                  }
                  const VkExtent3D bandExtent = { util::computeMipLevelExtent(upload->asset->info().extent, level).width,
                    band.height, band.depth };
                  ready.upload = upload;
                  ready.mips.push_back({ slice, bandExtent, level - upload->mipBegin, layer,
                    { 0, int32_t(band.y), int32_t(band.z) } });
                  ready.bytes = bytes;
                  row += band.rows;
                  ready.complete = level + 1 == upload->mipEnd && layer + 1 == upload->asset->info().numLayers
                    && z + 1 == layout.slices && row == layout.rows;
                  publish(std::move(ready));
                  ++bandCount;
                  peakBytes = std::max(peakBytes, bytes);
                }
              }
              upload->asset->evictCache(layer, level);
            }
          }
          Logger::info(str::format("[TextureStaging] ", upload->asset->info().extent.width, "x",
            upload->asset->info().extent.height, ": planned ", upload->bytes, " bytes, packed ", bandCount,
            " bands, peak slice ", peakBytes, " bytes, producer elapsed ",
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count(), " ms"));
        }
      } catch (const DxvkError& e) {
        fail(e.message());
      } catch (const std::exception& e) {
        fail(e.what());
      }
      for (uint32_t level = upload->mipBegin; level < upload->mipEnd; ++level) {
        for (uint32_t layer = 0; layer < upload->asset->info().numLayers; ++layer) {
          upload->asset->evictCache(layer, level);
        }
      }
      upload->asset->releaseSource();
      m_processing = false;
    }
  }


  // AsyncRunner end


  // AsyncRunner_RTXIO begin

  
#ifdef WITH_RTXIO
  // Spawns a separate thread that sends requests to RTXIO library,
  // needed to avoid situations when RTXIO blocks the Vulkan thread (calling thread).
  struct AsyncRunner_RTXIO {
    AsyncRunner_RTXIO(const AsyncRunner_RTXIO&) = delete;
    AsyncRunner_RTXIO(AsyncRunner_RTXIO&&) noexcept = delete;
    AsyncRunner_RTXIO& operator=(const AsyncRunner_RTXIO&) = delete;
    AsyncRunner_RTXIO& operator=(AsyncRunner_RTXIO&&) noexcept = delete;

    
    explicit AsyncRunner_RTXIO(const Rc<DxvkDevice>& device)
      : m_device{ device }
      , m_requiresShutdown{ false }
      , m_thread{ dxvk::thread{ [this] { this->asyncLoop(); } } }
      , m_texturesToProcess_count{ 0 }
      , m_requiresSyncFlush{ false }
    {
      m_thread.set_priority(ThreadPriority::Lowest);
    }


    ~AsyncRunner_RTXIO() {
      if (!m_requiresShutdown.load()) {
        {
          // Published under the mutex of the worker's only wait, so the notify below cannot be
          // dropped by a store that lands after the wait predicate was evaluated.
          auto l = std::unique_lock{ m_texturesToProcess_mutex };
          m_requiresShutdown.store(true);
        }
        m_texturesToProcess_cond.notify_all();
      }
      if (m_thread.joinable()) {
        m_thread.join();
      }
    }


    void syncPoint(bool blockUntilQueueIsEmpty) {
      if (blockUntilQueueIsEmpty) {
        while (m_texturesToProcess_count.load() != 0) {
          _mm_pause();
        }
      }

      if (blockUntilQueueIsEmpty || m_requiresSyncFlush) {
        // If there is any non-async request in flight,
        // dispatch the RTX IO job synchronously
        flushRtxIo(false);
        m_requiresSyncFlush = false;
      }
    }


    void queueAdd(const Rc<ManagedTexture>& tex, bool async) {
      assert(!m_requiresShutdown.load());
      assert(tex->m_state == ManagedTexture::State::kQueuedForUpload);
      if (!async) {
        m_requiresSyncFlush = true;
      }
      auto l = std::unique_lock{ m_texturesToProcess_mutex };
      m_texturesToProcess.emplace(tex);
      m_texturesToProcess_count = uint32_t(m_texturesToProcess.size());
      m_texturesToProcess_cond.notify_one();
    }


    void asyncLoop() {
      env::setThreadName("rtx-texture-async-rtxio");
      try {
        while (true) {
          if (m_requiresShutdown.load()) {
            break;
          }
          if (!RtxIo::enabled()) {
            break;
          }

          Rc<ManagedTexture> itemToProcess{};
          {
            auto l = std::unique_lock{ m_texturesToProcess_mutex };

            while (m_texturesToProcess.empty() && !m_requiresShutdown.load()) {
              m_texturesToProcess_cond.wait(l);

              l.unlock();
              flushRtxIo(true);
              l.lock();
            }

            if (m_requiresShutdown.load()) {
              break;
            }

            if (!m_texturesToProcess.empty()) {
              itemToProcess = std::move(*m_texturesToProcess.begin());
              m_texturesToProcess.erase(m_texturesToProcess.begin());
              m_texturesToProcess_count = uint32_t(m_texturesToProcess.size());
            }
          }

          const auto [mip_begin, mip_end] = itemToProcess->calcRequiredMips_BeginEnd();
          auto rtxioDst = allocDeviceImage(m_device.ptr(),
                                           itemToProcess->m_assetData,
                                           itemToProcess->imageCreateInfo(),
                                           mip_begin,
                                           mip_end);
          loadTextureRtxIo(itemToProcess, rtxioDst, mip_begin, mip_end);

          {
            auto l = std::unique_lock{ m_waitingList_mutex };
            m_waitingList.push_back(ReadyToCopy_RTXIO{
              /* .dstTexture = */ itemToProcess,
              /* .rtxioDst   = */ rtxioDst,
              /* .mip_begin  = */ mip_begin,
              /* .mip_end    = */ mip_end,
            });
          }
        }
      } catch (const DxvkError& e) {
        Logger::err(str::format("Exception on rtx-texture-async-rtxio thread!"));
        Logger::err(e.message());
      }
    }


    bool finalizeReadyRtxioTextures(DxvkContext* ctx) {
      auto l_canBeRemovedFromWaiting = [ctx](ReadyToCopy_RTXIO& ready) -> bool {
        Rc<ManagedTexture>& tex = ready.dstTexture;
        if (tex->m_state != ManagedTexture::State::kQueuedForUpload) {
          return true;
        }
        if (!RtxIo::get().isComplete(tex->m_completionSyncpt)) {
          return false;
        }
        if (ctx) {
          if (ready.rtxioDst->image()->info().layout == VK_IMAGE_LAYOUT_UNDEFINED) {
            ctx->changeImageLayout(ready.rtxioDst->image(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
          }
        }
        tex->m_currentMipView   = ready.rtxioDst;
        tex->m_currentMip_begin = ready.mip_begin;
        tex->m_currentMip_end   = ready.mip_end;
        tex->m_state = ManagedTexture::State::kVidMem;
        return true;
      };

      assert(dxvk::this_thread::get_id() != m_thread.get_id());
      if (!RtxIo::enabled()) {
        return false;
      }

      {
        auto l = std::unique_lock{ m_waitingList_mutex };

        auto newend = std::remove_if(m_waitingList.begin(),
                                     m_waitingList.end(),
                                     l_canBeRemovedFromWaiting);
        const bool anyPromoted = (newend != m_waitingList.end());
        m_waitingList.erase(newend, m_waitingList.end());

        if (!m_waitingList.empty()) {
          // if anything is being waited, flushRtxIo on m_thread
          m_texturesToProcess_cond.notify_one();
        }
        return anyPromoted;
      }
    }

  private:
    Rc<DxvkDevice>                  m_device;
    std::atomic<bool>               m_requiresShutdown { false };
    dxvk::thread                    m_thread;

    dxvk::mutex                     m_texturesToProcess_mutex;
    dxvk::condition_variable        m_texturesToProcess_cond;
    std::unordered_set<Rc<ManagedTexture>, RcHasher> m_texturesToProcess; // TODO: remove set?
    std::atomic<uint32_t>           m_texturesToProcess_count { 0 };

    dxvk::mutex                     m_waitingList_mutex;
    std::vector<ReadyToCopy_RTXIO>  m_waitingList;

    bool m_requiresSyncFlush; // read-write only from a client thread
  };
#else
  // NV-DXVK start: RTXIO is compiled out, the runner only exists to keep the call sites valid.
  struct AsyncRunner_RTXIO {
    explicit AsyncRunner_RTXIO(const Rc<DxvkDevice>&) { }
    template<typename... Args> void queueAdd(Args&&...) { }
    template<typename... Args> void syncPoint(Args&&...) { }
    template<typename... Args> void finalizeReadyRtxioTextures(Args&&...) { }
  };
  // NV-DXVK end
#endif


  // AsyncRunner_RTXIO end


  // For each ManagedTexture, keep the data returned by the sampler feedback.
  // It smoothly accumulates the mipcount, trying to avoid spikes,
  // so that it can be further used to request a texture load from a disk.
  struct FeedbackAccum {
    uint32_t  frame;          // Frame when 'mipcount' member was changed.
    float     avgMipcount;    // A running average of all seen mipcount-s.
                              // Used as a smooth fallback, when there's VRAM budget pressure.
    uint8_t   mipcount;       // The max mipcount seen across frames.
  };

  namespace {
    float calcResolutionAndHistoryWeightForTexture(const FeedbackAccum& x, const uint32_t& curframe) {
      uint32_t framediff = (curframe >= x.frame) ? curframe - x.frame : 0;
      float fr_weight = 1.F / (1.F + framediff);

      // need relative mip count? divide by asset_mipcount?
      float mip_weight = float(std::min(x.mipcount, MAX_MIPS)) / float(MAX_MIPS);

      assert(fr_weight >= 0 && fr_weight <= 1);
      assert(mip_weight >= 0 && mip_weight <= 1);

      return (2.0f * mip_weight) + (1.0f * fr_weight);
    }
  } // unnamed namespace


  constexpr VkDeviceSize MiBPerGiB = 1024;
  // Remix needs at least two frames to completely evict the demoted textures.
  // After a global texture demotion event, texture manager will delay future texture promotions
  // by this number of frames to make sure the previously used memory is released and there
  // will be no overcommit.
  constexpr uint32_t kPromotionDelayFrames = 2;

  RtxTextureManager::RtxTextureManager(DxvkDevice* pDevice)
    : CommonDeviceObject(pDevice)
    , m_asyncThread{}
    , m_asyncThread_rtxio{}
  {
    m_sf.m_related = new uint16_t[SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT * SAMPLER_FEEDBACK_RELATED_PER_TEX];
    m_sf.m_noisyMipcount = new uint8_t[SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT]{ /* zero-init */ };
    m_sf.m_accumulatedMipcount = new FeedbackAccum[SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT]{ /* zero-init */ };
    m_sf.m_cachedAssetMipcount = new uint8_t[SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT]{ /* zero-init */ };
    m_sf.m_cachedGpubuf = new uint32_t[SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT]; // NO zero-init

    // SAMPLER_FEEDBACK_INVALID must be 0xFFFF (all-0xFF bytes) for memset to fill
    // each uint16_t slot with the correct sentinel value.
    static_assert(SAMPLER_FEEDBACK_INVALID == UINT16_MAX, "SAMPLER_FEEDBACK_INVALID must be 0xFFFF for memset fill to be correct");
    memset(m_sf.m_related, 0xFF, SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT * SAMPLER_FEEDBACK_RELATED_PER_TEX * sizeof(m_sf.m_related[0]));

    FileWatch::get().setTextureManager(this);
  }

  void RtxTextureManager::startAsync() {
    if (RtxIo::enabled()) {
      m_asyncThread_rtxio = new AsyncRunner_RTXIO{ m_device };
    } else {
      m_asyncThread = new AsyncRunner{ m_device };
    }
  }

  RtxTextureManager::~RtxTextureManager() {
    FileWatch::get().clearTextureManager(this);

    delete m_sf.m_cachedGpubuf;
    delete m_sf.m_cachedAssetMipcount;
    delete m_sf.m_accumulatedMipcount;
    delete m_sf.m_noisyMipcount;
    delete m_sf.m_related;
    delete m_asyncThread;
    delete m_asyncThread_rtxio;
  }

  static ManagedTexture::State processManagedTextureState(ManagedTexture* tex) {
    assert(tex != nullptr);
    if (tex->m_state == ManagedTexture::State::kFailed) {
      tex->requestMips(0);
      return ManagedTexture::State::kFailed;
    }
    return tex->m_state;
  }

  void RtxTextureManager::scheduleTextureLoad(const Rc<ManagedTexture>& texture, bool async, bool forceUnload) {
    if (!texture.ptr()) {
      return;
    }

    const auto managedState = processManagedTextureState(texture.ptr());
    if (managedState == ManagedTexture::State::kQueuedForUpload) {
      // Texture is in the async thread processing queue, leave it
      return;
    }
    // If not forced, we can early-out if mip count is already satisfied
    if (!forceUnload) {
      if (managedState == ManagedTexture::State::kVidMem) {
        // If uploaded to GPU, check if requested amount of mips is the same
        if (texture->hasUploadedMips(texture->m_requestedMips, true)) {
          return;
        }
      }
    }

    if (RtxOptions::alwaysWaitForAsyncTextures()) {
      async = false;
    }

    texture->m_state = ManagedTexture::State::kQueuedForUpload;
    if (m_asyncThread) {
      m_asyncThread->queueAdd(texture, async);
    } else if (m_asyncThread_rtxio) {
      m_asyncThread_rtxio->queueAdd(texture, async);
    } else {
      assert(0);
    }
  }

  void RtxTextureManager::submitTexturesToDeviceLocal(DxvkContext* ctx,
                                                      DxvkBarrierSet& execBarriers,
                                                      DxvkBarrierSet& execAcquires) {
    ScopedCpuProfileZoneN("Textures: upload to device");

    if (m_asyncThread) {
      const bool forceWait = RtxOptions::alwaysWaitForAsyncTextures() || !RtxOptions::asyncAssetLoading();
      do {
        auto uploads = m_asyncThread->retrieveReadyToUploadTextures();
        std::vector<ReadyToCopy> deferred;
        size_t remainingBytes = kUploadBytesPerFrame;
        const auto recordStart = std::chrono::steady_clock::now();
        bool budgetBlocked = false;
        for (auto& ready : uploads) {
          auto& upload = *ready.upload;
          const auto& tex = upload.texture;
          if (upload.cancelled.load()) {
            continue;
          }
          const auto wanted = tex->calcRequiredMips_BeginEnd();
          const bool demoted = wanted.first > upload.mipBegin;
          if (demoted || tex->m_state != ManagedTexture::State::kQueuedForUpload
              || tex->m_assetData != upload.asset) {
            if (textureStagingDiagnosticsEnabled()) {
              Logger::info(str::format("[TextureStaging] Cancelled ", upload.asset->info().extent.width, "x",
                upload.asset->info().extent.height, ": asset changed ", tex->m_assetData != upload.asset,
                ", demoted ", demoted, ", state ", uint32_t(tex->m_state), ", frame ", m_device->getCurrentFrameId()));
            }
            upload.cancelled = true;
            if (tex->m_state == ManagedTexture::State::kQueuedForUpload && (demoted || tex->m_assetData != upload.asset)) {
              tex->m_state = ManagedTexture::State::kInitialized;
            }
            continue;
          }
          if (upload.failed.load()) {
            upload.cancelled = true;
            tex->m_state = ManagedTexture::State::kFailed;
            continue;
          }
          if (upload.mipBegin == upload.mipEnd) {
            tex->m_currentMipView = nullptr;
            tex->m_currentMip_begin = upload.mipBegin;
            tex->m_currentMip_end = upload.mipEnd;
            tex->m_state = ManagedTexture::State::kVidMem;
            continue;
          }
          if (!upload.image.ptr()) {
            if (!upload.deviceBytes) {
              upload.deviceBytes = calcDeviceImageBytes(m_device, upload);
            }
            const size_t budget = calcTextureMemoryBudgetBytes(m_device);
            const size_t used = calcCurrentTextureUsageBytes(m_device);
            if (used > budget || upload.deviceBytes > budget - used) {
              // Preserve the request and its staging lease until eviction creates room.
              m_wasTextureBudgetPressure = true;
              budgetBlocked = true;
              if (tex->m_currentMipView.ptr() && upload.mipBegin >= tex->m_currentMip_begin) {
                // A same-size replacement or demotion must be able to retire its old allocation.
                tex->m_currentMipView = nullptr;
                tex->m_currentMip_begin = 0;
                tex->m_currentMip_end = 0;
              }
              if (!upload.budgetWarning) {
                Logger::info(str::format("[TextureStaging] Deferring image: requires ", upload.deviceBytes,
                  " bytes, texture budget ", budget, ", used ", used));
                upload.budgetWarning = true;
              }
              deferred.push_back(std::move(ready));
              continue;
            }
            upload.image = allocDeviceImage(m_device, upload.asset, upload.imageInfo, upload.mipBegin, upload.mipEnd);
            m_asyncThread->admitUpload(upload);
            if (textureStagingDiagnosticsEnabled()) {
              Logger::info(str::format("[TextureStaging] Admitted ", upload.asset->info().extent.width, "x",
                upload.asset->info().extent.height, ", image bytes ", upload.deviceBytes,
                ", frame ", m_device->getCurrentFrameId()));
            }
          }
          if (ready.bytes > remainingBytes) {
            deferred.push_back(std::move(ready));
            continue;
          }
          if (!ready.mips.empty()) {
            copyStagingToDevice(ctx, execBarriers, execAcquires, ready);
          }
          remainingBytes -= ready.bytes;
          if (ready.complete) {
            if (auto* memory = dynamic_cast<MemoryAssetData*>(upload.asset.ptr())) {
              // Dirty writes may target bands already copied while this image was private.
              execBarriers.recordCommands(ctx->getCommandList());
              const auto dirty = m_asyncThread->reconcileDirty(ctx, *memory, upload.image->image(), remainingBytes);
              remainingBytes -= dirty.bytes;
              if (dirty.bytes && textureStagingDiagnosticsEnabled()) {
                Logger::info(str::format("[TextureStaging] Reconciled ", dirty.bytes, " dirty bytes for ",
                  upload.asset->info().extent.width, "x", upload.asset->info().extent.height,
                  ", complete ", dirty.complete, ", frame ", m_device->getCurrentFrameId()));
              }
              if (dirty.compatible && !dirty.complete) {
                ready.mips.clear();
                ready.lease.reset();
                ready.bytes = 0;
                deferred.push_back(std::move(ready));
                continue;
              }
            }
            tex->m_currentMipView = upload.image;
            tex->m_currentMip_begin = upload.mipBegin;
            tex->m_currentMip_end = upload.mipEnd;
            tex->m_state = ManagedTexture::State::kVidMem;
            if (upload.bytes > kUploadBytesPerFrame) {
              Logger::info(str::format("[TextureStaging] Published ", upload.asset->info().extent.width, "x",
                upload.asset->info().extent.height, " after ",
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - upload.started).count(),
                " ms (queue, packing and recording, not GPU completion)"));
            }
          }
        }
        if (kUploadBytesPerFrame - remainingBytes >= 4 * Megabytes) {
          Logger::info(str::format("[TextureStaging] Frame ", m_device->getCurrentFrameId(), ": budgeted ",
            kUploadBytesPerFrame - remainingBytes, " bytes, CPU ",
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - recordStart).count(), " ms"));
        }
        m_asyncThread->deferUploads(std::move(deferred));
        if (!forceWait || budgetBlocked || !m_asyncThread->hasPendingUploads()) {
          break;
        }
        // Explicit deterministic capture mode retires bounded batches before producing the next one.
        ctx->flushCommandList();
        m_device->waitForIdle();
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
      } while (true);
    } else if (m_asyncThread_rtxio) {
      m_asyncThread_rtxio->syncPoint(RtxOptions::alwaysWaitForAsyncTextures());
      m_asyncThread_rtxio->finalizeReadyRtxioTextures(ctx);
    }
  }

  void RtxTextureManager::addTexture(const TextureRef& inputTexture, uint16_t associatedFeedbackStamp, bool async, uint32_t& textureIndexOut) {
    if (!inputTexture.isValid()) {
      return;
    }

    textureIndexOut = m_textureCache.track(inputTexture);

    constexpr uint32_t kInvalidSurfaceTextureIndex = 0xFFFFu;
    if (textureIndexOut == kInvalidSurfaceTextureIndex || textureIndexOut >= m_textureCache.getTotalCount()) {
      return;
    }
    const TextureRef& texRef = m_textureCache.at(textureIndexOut);
    if (!texRef.isValid()) {
      return;
    }
    const Rc<ManagedTexture>& tex = texRef.getManagedTexture();
    if (tex == nullptr) {
      return;
    }

    if (!async || RtxOptions::TextureManager::neverDowngradeTextures()) {
      tex->m_canDemote = false;
      tex->requestMips(MAX_MIPS);
      scheduleTextureLoad(tex, false);
      return;
    }
    updateSamplerFeedback(tex, associatedFeedbackStamp);
  }

  void RtxTextureManager::updateSamplerFeedback(const Rc<ManagedTexture>& tex, uint16_t associatedFeedbackStamp) {
    m_sf.associate(
      RtxOptions::TextureManager::samplerFeedbackEnable() ? associatedFeedbackStamp : SAMPLER_FEEDBACK_INVALID,
      tex->m_samplerFeedbackStamp
    );
  }

  void RtxTextureManager::assertAllRefCountsZero() {
    auto ls = std::unique_lock{ m_sf.m_idToTexture_mutex };
    for (const auto& tex : m_sf.m_idToTexture) {
      if (tex != nullptr && tex->m_refCount != 0) {
        Logger::err(str::format("[RTX] assertAllRefCountsZero: texture stamp ", tex->m_samplerFeedbackStamp,
          " has ref count ", tex->m_refCount, " after all instances destroyed (retain/release mismatch)"));
        assert(false && "assertAllRefCountsZero: non-zero ref count after instance destruction");
        tex->m_refCount = 0;
      }
    }

    const uint32_t remaining = m_retainedCount.load(std::memory_order_relaxed);
    if (remaining != 0) {
      Logger::err(str::format("[RTX] assertAllRefCountsZero: m_retainedCount is ", remaining,
        " after all per-texture ref counts were zeroed (0<->1 transition tracking is broken)"));
      assert(false && "assertAllRefCountsZero: m_retainedCount inconsistent with per-texture ref counts");
      m_retainedCount.store(0, std::memory_order_relaxed);
    }
  }

  void RtxTextureManager::retainTexture(uint32_t textureIndex) {
    // forEachTextureIndex on RtSurfaceMaterial yields kSurfaceMaterialInvalidTextureIndex
    // (0xFFFFu) for unset texture slots, and in-range indices can point to TextureRefs whose
    // ManagedTexture is still null (loading in progress). Both cases are silent no-ops.
    if (textureIndex >= m_textureCache.getTotalCount()) {
      if (textureIndex != kSurfaceMaterialInvalidTextureIndex) {
        Logger::err(str::format("[RTX] retainTexture: index ", textureIndex,
          " out of range (cache size ", m_textureCache.getTotalCount(),
          "; texture cache is inconsistent with surface material cache"));
        assert(false && "retainTexture: index out of range");
      }
      return;
    }
    const Rc<ManagedTexture>& tex = m_textureCache.at(textureIndex).getManagedTexture();
    if (tex == nullptr) {
      return;
    }
    if (tex->m_refCount < 0) {
      Logger::err(str::format("[RTX] retainTexture: index ", textureIndex,
        " has negative ref count ", tex->m_refCount,
        "; resetting to 0 so subsequent retains are counted correctly"));
      assert(false && "retainTexture: negative ref count from prior underflow");
      tex->m_refCount = 0;
    }
    if (tex->m_refCount++ == 0) {
      m_retainedCount.fetch_add(1, std::memory_order_relaxed);
    }
  }

  void RtxTextureManager::releaseTexture(uint32_t textureIndex) {
    // Out-of-bounds is silently expected after SceneManager::clear() clears the texture
    // cache before all instance destroy events fire; in that case the release is a no-op.
    if (textureIndex >= m_textureCache.getTotalCount()) {
      return;
    }
    const Rc<ManagedTexture>& tex = m_textureCache.at(textureIndex).getManagedTexture();
    if (tex == nullptr) {
      return;
    }
    if (tex->m_refCount <= 0) {
      Logger::err(str::format("[RTX] releaseTexture: ref count underflow at index ", textureIndex,
        " (count is ", tex->m_refCount, "; mismatched retainTexture/releaseTexture calls)"));
      assert(false && "releaseTexture: ref count underflow");
      return;
    }
    if (--tex->m_refCount == 0) {
      m_retainedCount.fetch_sub(1, std::memory_order_relaxed);
    }
  }


  void RtxTextureManager::clear() {
    ScopedCpuProfileZone();

    // Only demote textures when we're actually over budget.
    // If we're under budget, there's no reason to demote - keep textures in VRAM
    // so they're ready when the scene returns (e.g., after closing a menu).
    if (isOverTextureBudget(m_device)) {
      manageBudgetWithPriority();
    }

    // Reset sampler feedback relationships so they are rebuilt from scratch by the next
    // dynamic frame rather than carrying over stale groupings from the old scene.
    // SAMPLER_FEEDBACK_INVALID must be 0xFFFF (all-0xFF bytes) for memset to fill
    // each uint16_t slot with the correct sentinel value.
    static_assert(SAMPLER_FEEDBACK_INVALID == UINT16_MAX, "SAMPLER_FEEDBACK_INVALID must be 0xFFFF for memset fill to be correct");
    memset(m_sf.m_related, 0xFF, SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT * SAMPLER_FEEDBACK_RELATED_PER_TEX * sizeof(m_sf.m_related[0]));

    m_textureCache.clear();
    ++m_textureCacheGeneration;
  }

  void RtxTextureManager::requestHotReload(const Rc<ManagedTexture>& tex) {
    if (!RtxOptions::TextureManager::hotReload()) {
      return;
    }
    if (!m_asyncThread) {
      ONCE(Logger::err("filewatch: hot reload is not available with RTX IO. Only raw native filesystem is supported."));
      return;
    }
    if (!tex.ptr()) {
      return;
    }
    auto lockRequestsList = std::unique_lock{ m_hotreloadMutex };
    m_hotreloadRequests.insert(tex);
  }

  namespace {
    struct FileReadLock {
      explicit FileReadLock(const char* filepath) {
        m_handle = CreateFileA(
          filepath, //
          GENERIC_READ,
          FILE_SHARE_READ, // others can read
          nullptr,
          OPEN_EXISTING,
          FILE_ATTRIBUTE_NORMAL,
          nullptr
        );
      }
      ~FileReadLock() {
        if (m_handle && m_handle != INVALID_HANDLE_VALUE) {
          CloseHandle(m_handle);
        }
      }
      bool otherProcessIsWriting() const {
        return !m_handle || m_handle == INVALID_HANDLE_VALUE;
      }
    private:
      HANDLE m_handle{};
    };
  }

  void RtxTextureManager::reuploadTexture(const Rc<ManagedTexture>& tex) {
    if (!tex.ptr()) {
      return;
    }
    tex->requestMips(0);
    constexpr bool forceUnload = true; // do not check current mip count
    scheduleTextureLoad(tex, false, forceUnload);
  }

  void RtxTextureManager::processAllHotReloadRequests() {
    if (!RtxOptions::TextureManager::hotReload()) {
      return;
    }
    if (!m_asyncThread) {
      return;
    }
    auto lockRequestsList = std::unique_lock{ m_hotreloadMutex };
    if (m_hotreloadRequests.empty()) {
      return;
    }

    // iterate destructively
    for (auto it = m_hotreloadRequests.begin(); it != m_hotreloadRequests.end();) {
      const Rc<ManagedTexture>& tex = *it;

      const char* filepath = tex->m_assetData->info().filename;
      if (!filepath || filepath[0] == '\0') {
        it = m_hotreloadRequests.erase(it); // pop request
        continue;
      }

      // try lock the file for reading, to ensure that other processes are not writing into it
      FileReadLock fileReadLock = FileReadLock{ filepath };
      if (fileReadLock.otherProcessIsWriting()) {
        // skip and try to check request in the next frame,
        // i.e. wait for full file write from other process
        // NOTE: this keeps the request in 'm_hotreloadRequests'
        ++it;
        continue;
      }

      // NOTE: FileReadLock opened a file FILE_SHARE_READ, so asset manager's 'fopen' call should succeed
      auto newAssetData = AssetDataManager::get().findAsset(filepath, true);
      if (!newAssetData.ptr()) {
        // file doesn't exist
        it = m_hotreloadRequests.erase(it); // pop request
        continue;
      }

      // replace information about the file
      tex->m_assetData = newAssetData;
      tex->requestMips(0);
      constexpr bool forceUnload = true; // do not check current mip count
      scheduleTextureLoad(tex, false, forceUnload );

      it = m_hotreloadRequests.erase(it); // pop request
    }
  }

  void RtxTextureManager::prepareSamplerFeedback(DxvkContext* ctx) {
    auto& res = ctx->getCommonObjects()->getResources().getRaytracingOutput();

    // reset device-local sampler feedback buffer
    const auto bytesToClear = m_sf.m_idToTexture_count.load() * sizeof(uint32_t);

    // Note: Only clear the buffer when a non-zero clear size is requested (as Vulkan
    // does not allow for zero-sized clears).
    if (bytesToClear != 0) {
      ctx->clearBuffer(
        res.m_samplerFeedbackDevice,
        0, 
        bytesToClear,
        UINT32_MAX  // to find min during the rendering
      );
    }
  }

  void RtxTextureManager::copySamplerFeedbackToHost(DxvkContext* ctx) {
    auto& res = ctx->getCommonObjects()->getResources().getRaytracingOutput();

    const auto frameid = m_device->getCurrentFrameId();

    const auto curframe = frameid % std::size(res.m_samplerFeedbackReadback);
    const auto prevframe = (frameid + (std::size(res.m_samplerFeedbackReadback) - 1)) % std::size(res.m_samplerFeedbackReadback);
    const auto bytesToCopy = m_sf.m_idToTexture_count.load() * sizeof(uint32_t);

    // Note: Only copy the buffer when a non-zero copy size is requested (as Vulkan
    // does not allow for zero-sized copies).
    if (bytesToCopy != 0) {
      ctx->copyBuffer(
        res.m_samplerFeedbackReadback[curframe],
        0,
        res.m_samplerFeedbackDevice,
        0,
        bytesToCopy);
    }

    {
      const uint32_t* gpuAccessedMips_safeToReadOnHost =
        (const uint32_t*)res.m_samplerFeedbackReadback[prevframe]->mapPtr(0); // approximately...

      garbageCollection(gpuAccessedMips_safeToReadOnHost);
    }
  }

  bool SamplerFeedback::associate(uint16_t stampWithList, uint16_t stampToAdd) {
    assert(stampToAdd != SAMPLER_FEEDBACK_INVALID);
    if (stampWithList == SAMPLER_FEEDBACK_INVALID) {
      return false;
    }
    // no need to add to itself
    if (stampWithList == stampToAdd) {
      return true;
    }
    uint16_t* listOfRelatedStamps = &m_related[ptrdiff_t{ stampWithList } * SAMPLER_FEEDBACK_RELATED_PER_TEX];
    for (uint8_t i = 0; i < SAMPLER_FEEDBACK_RELATED_PER_TEX; i++) {
      // if entry exists
      if (listOfRelatedStamps[i] == stampToAdd) {
        return true;
      }
      // add, if not
      if (listOfRelatedStamps[i] == SAMPLER_FEEDBACK_INVALID) {
        listOfRelatedStamps[i] = stampToAdd;
        return true;
      }
    }
    return false;
  }

  uint32_t SamplerFeedback::fetchNoisyMipCounts(const uint32_t* src_gpubuf) {
    uint32_t textureCount;
    {
      auto ls = std::unique_lock{ m_idToTexture_mutex };

      textureCount = uint32_t(m_idToTexture.size());
      if (textureCount == 0) {
        return 0;
      }

      // optimize access to asset's mipcount, to avoid pointer chase via ManagedTexture,
      // improves cache efficiency
      if (m_cachedAssetMipcount_length != textureCount) {
        memset(m_cachedAssetMipcount, 0, textureCount * sizeof(m_cachedAssetMipcount[0]));
        m_cachedAssetMipcount_length = uint32_t(textureCount);
        for (uint32_t stamp = 0; stamp < textureCount; stamp++) {
          const Rc<ManagedTexture>& tex = m_idToTexture[stamp];
          assert(stamp == tex->m_samplerFeedbackStamp);
          if (tex.ptr()) {
            m_cachedAssetMipcount[stamp] = uint8_t(std::min(tex->m_assetData->info().mipLevels, uint32_t(MAX_MIPS)));
          }
        }
      }
    }

    // Shadow memory to reduce random access via DMA
    memcpy(m_cachedGpubuf, src_gpubuf, textureCount * sizeof(uint32_t));

    // Reset to zero to find a max value for each texture in 'src_gpubuf'
    memset(m_noisyMipcount, 0, textureCount * sizeof(m_noisyMipcount[0]));
    for (uint32_t stamp = 0; stamp < textureCount; stamp++) {
      uint8_t newMipCount;
      {
        const uint32_t assetMipCount = m_cachedAssetMipcount[stamp];

        const uint32_t mipAccessed = std::min(m_cachedGpubuf[stamp], assetMipCount);
        newMipCount = uint8_t(assetMipCount - mipAccessed);
      }
      m_noisyMipcount[stamp] = std::max(m_noisyMipcount[stamp], newMipCount);
    }

    // A single stamp can be associated with many other stamps (SAMPLER_FEEDBACK_RELATED_PER_TEX).
    // (Because sampler feedback is decided on an albedo texture,
    // so expand the mip count of albedo onto roughness, emissive and other textures)
    for (uint32_t stamp = 0; stamp < textureCount; stamp++) {
      uint16_t* listOfRelatedStamps = &m_related[stamp * SAMPLER_FEEDBACK_RELATED_PER_TEX];
      if (listOfRelatedStamps[0] == SAMPLER_FEEDBACK_INVALID) {
        continue;
      }
      const auto newMipCount = m_noisyMipcount[stamp];

      for (uint8_t i = 0; i < SAMPLER_FEEDBACK_RELATED_PER_TEX; i++) {
        uint16_t stampOfRelated = listOfRelatedStamps[i];
        if (stampOfRelated == SAMPLER_FEEDBACK_INVALID) {
          break; // end of list
        }
        m_noisyMipcount[stampOfRelated] = std::max(m_noisyMipcount[stampOfRelated], newMipCount);
      }
    }

    return textureCount;
  }

#define FRAMES_TO_DETAIN 60

  void SamplerFeedback::accumulateMipCounts(uint32_t len, uint32_t curframe, bool canReset) {
    for (uint32_t stamp = 0; stamp < len; stamp++) {
      FeedbackAccum& accum = m_accumulatedMipcount[stamp];

      const auto new_mipcount = m_noisyMipcount[stamp];

      if (new_mipcount != 0) {
        if (new_mipcount >= accum.mipcount) {
          accum.frame = curframe;
          accum.mipcount = new_mipcount;
        }
      }

      accum.avgMipcount = (float(new_mipcount) + accum.avgMipcount) * 0.5f;

      if (canReset) {
        assert(curframe >= accum.frame);
        if (curframe - accum.frame > FRAMES_TO_DETAIN) {
          accum.frame = curframe;
          accum.mipcount = (uint8_t)std::clamp(accum.avgMipcount, 0.f, float(MAX_MIPS));
        }
      }
    }
  }

  void RtxTextureManager::garbageCollection(const uint32_t* gpuAccessedMips) {
    ScopedCpuProfileZone();

    const auto curframe = m_device->getCurrentFrameId();

    uint32_t sfTextureCount = m_sf.fetchNoisyMipCounts(gpuAccessedMips);
    m_sf.accumulateMipCounts(sfTextureCount, curframe, m_wasTextureBudgetPressure);
    m_wasTextureBudgetPressure = false;

    
    static auto prioritylist = std::vector<ManagedTexture*>{};
    static auto checkonlyframes = std::vector<ManagedTexture*>{};
    {
      prioritylist.clear();
      checkonlyframes.clear();
      auto ls = std::unique_lock{ m_sf.m_idToTexture_mutex };
      for (const auto& tex : m_sf.m_idToTexture) {
        assert(tex != nullptr);
        if (tex != nullptr && tex->m_canDemote) {
          if (tex->m_refCount > 0 && tex->m_samplerFeedbackStamp != SAMPLER_FEEDBACK_INVALID) {
            prioritylist.push_back(tex.ptr());
          } else {
            checkonlyframes.push_back(tex.ptr());
          }
        }
      }
    }


    // Non-SF textures (sky, terrain, etc.): keep at full mips while active in the scene,
    // evict once the scene clears (m_refCount > 0 reset in clear()).
    for (ManagedTexture* tex : checkonlyframes) {
      assert(tex && tex->m_canDemote);
      tex->requestMips(tex->m_refCount > 0 ? MAX_MIPS : 0);
      scheduleTextureLoad(tex, true);
    }

    
    // For sampler-feedback textures, make a list, so that the low priority textures are at the end.
    // If full list doesn't fit into the budget, demote the low priority ones.
    {
      auto l_sort = [curframe, this](const ManagedTexture* a, const ManagedTexture* b) {
        assert(a && b);
        float weightA = calcResolutionAndHistoryWeightForTexture(m_sf.m_accumulatedMipcount[a->m_samplerFeedbackStamp], curframe);
        float weightB = calcResolutionAndHistoryWeightForTexture(m_sf.m_accumulatedMipcount[b->m_samplerFeedbackStamp], curframe);

        if (std::abs(weightA - weightB) < 0.00001f) {
          return (a->m_samplerFeedbackStamp < b->m_samplerFeedbackStamp); // stable fallback, if too similar
        }
        return weightA > weightB;
      };
      std::sort(prioritylist.begin(), prioritylist.end(), l_sort);
    }
    {
      const size_t budgetBytes = calcTextureMemoryBudgetBytes(m_device);
      size_t       usedBytes   = 0;
      for (ManagedTexture* tex : prioritylist) {
        assert(tex && tex->m_canDemote && tex->m_samplerFeedbackStamp != SAMPLER_FEEDBACK_INVALID);
        // for low memory GPUs we should do our best to not blow through all memory, lower the highest quality mip level
        // need to account for textures that dont have more than 1 mip level here too.
        const uint32_t allmipcount = tex->m_assetData->info().mipLevels - ((RtxOptions::lowMemoryGpu() && tex->m_assetData->info().mipLevels > 0) ? 1u : 0u);

        uint32_t mipc = m_sf.m_accumulatedMipcount[tex->m_samplerFeedbackStamp].mipcount;
        mipc = std::min(mipc, allmipcount);

        // TODO: potential bottleneck
        size_t byteSize = calcSizeForAsset(*tex->m_assetData, allmipcount - mipc, allmipcount);

        if (usedBytes + byteSize <= budgetBytes) {
          usedBytes += byteSize;
          tex->requestMips(mipc);
        } else {
          // doesn't fit => demote
          tex->requestMips(0);
          m_wasTextureBudgetPressure = true;
        }

        scheduleTextureLoad(tex, true);
      }
      assert(usedBytes <= budgetBytes);

      // for debug report
      g_streamedTextures_budgetBytes = budgetBytes;
      g_streamedTextures_usedBytes   = usedBytes;
    }
  }

  void RtxTextureManager::manageBudgetWithPriority() {
    ScopedCpuProfileZone();
    
    const size_t budgetBytes = calcTextureMemoryBudgetBytes(m_device);
    size_t currentUsage = calcCurrentTextureUsageBytes(m_device);
    
    if (currentUsage <= budgetBytes) {
      return;  // Already under budget
    }
    
    auto demoteTexture = [&](ManagedTexture* tex) {
      const uint32_t allmipcount = tex->m_assetData->info().mipLevels;
      const uint32_t currentMips = tex->m_requestedMips.load();
      
      if (currentMips > 0) {
        const size_t oldSize = calcSizeForAsset(*tex->m_assetData, allmipcount - currentMips, allmipcount);
        tex->requestMips(0);
        scheduleTextureLoad(tex, false);
        currentUsage -= oldSize;
        m_wasTextureBudgetPressure = true;
      }
    };
    
    // Demote active scene textures (m_refCount > 0) to free budget.
    // Skip newly loaded textures (m_refCount > 0 == false) since they haven't been
    // registered yet and are needed for the incoming scene.
    {
      auto ls = std::unique_lock{ m_sf.m_idToTexture_mutex };
      for (const auto& tex : m_sf.m_idToTexture) {
        if (currentUsage <= budgetBytes) {
          break;
        }
        if (tex != nullptr && tex->m_canDemote && tex->m_refCount > 0) {
          demoteTexture(tex.ptr());
        }
      }
    }
    
    // Update debug stats
    g_streamedTextures_budgetBytes = budgetBytes;
    g_streamedTextures_usedBytes = currentUsage;
  }

  XXH64_hash_t RtxTextureManager::getUniqueKey() {
    static uint64_t ID;
    XXH64_hash_t key;

    do {
#ifdef _DEBUG
      assert(ID + 1 > ID && "Texture hash key id rollover detected!");
#endif
      ++ID;
      key = XXH3_64bits(&ID, sizeof(ID));
    } while (key == kInvalidTextureKey);

    return key;
  }

  bool warnIfTextureSuboptimal(const AssetData& assetData) {
    const auto& extent = assetData.info().extent;

    // Large textures that have only a single mip level are considered suboptimal since they
    // may cause high pressure on memory and/or cause hitches when loaded at runtime.
    const bool result = (assetData.info().mipLevels == 1) && (size_t(extent.width) * extent.height >= 512 * 512);
    if (result) {
      Logger::warn(str::format("A suboptimal replacement texture detected: ",
                                    assetData.info().filename,
                                    "! Please make sure all replacement textures have mip-maps."));
    }
    return result;
  }

  static bool canStreamAsset(const AssetData& assetData) {
    // Texture size does not constrain streaming. Only a complete block row must fit.
    return uploadLayout(assetData, 0).rowBytes <= stagingBufferSize_Bytes();
  }

  Rc<ManagedTexture> RtxTextureManager::preloadTextureAsset(const Rc<AssetData>& assetData,
                                                            ColorSpace colorSpace,
                                                            bool forceLoad) {

    const auto& assetInfo = assetData->info();
    if (!assetInfo.mipLevels || assetInfo.mipLevels > MAX_MIPS || !assetInfo.numLayers
        || !assetInfo.extent.width || !assetInfo.extent.height || !assetInfo.extent.depth) {
      Logger::err("[TextureStaging] Invalid texture dimensions, layers or mip count");
      return {};
    }

    const auto& limits = m_device->properties().core.properties.limits;
    uint32_t maxMips = 0;
    for (uint32_t dimension = std::max(assetInfo.extent.width, assetInfo.extent.height); dimension; dimension >>= 1) {
      ++maxMips;
    }
    if (assetInfo.extent.width > limits.maxImageDimension2D || assetInfo.extent.height > limits.maxImageDimension2D
        || assetInfo.extent.depth != 1 || assetInfo.numLayers > limits.maxImageArrayLayers || assetInfo.mipLevels > maxMips) {
      Logger::err("[TextureStaging] Texture exceeds the supported 2D image limits");
      return {};
    }

    const XXH64_hash_t hash = assetData->hash();

    {
      auto l = std::unique_lock{ m_assetHashToTextures_mutex };

      auto it = m_assetHashToTextures.find(hash);
      if (it != m_assetHashToTextures.end()) {
        // Is this truly the same asset?
        if (it->second->m_assetData->info().matches(assetData->info())) {
          return it->second;
        }

        // Else, clear out the old
        m_assetHashToTextures.erase(it);
      }
    }

    {
      VkFormatProperties properties = m_device->adapter()->formatProperties(assetData->info().format);

      if (!(properties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ||
          !(properties.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_DST_BIT)) {
        std::ostringstream formatStr;
        formatStr << assetData->info().format;

        Logger::err(str::format(
          "Ignoring replacement texture with unsupported format [", formatStr.str(), "]: ",
          assetData->info().filename));
        return {};
      }
    }

    warnIfTextureSuboptimal(*assetData);
    if (m_asyncThread && !canStreamAsset(*assetData)) {
      Logger::err("[TextureStaging] Texture block row exceeds the staging budget");
      return {};
    }

    Rc<ManagedTexture> texture = TextureUtils::createTexture(assetData, colorSpace);
    if (forceLoad) {
      texture->m_canDemote = false;
      texture->requestMips(MAX_MIPS);
      scheduleTextureLoad(texture, false);
    } else {
      texture->m_canDemote = true;
      texture->requestMips(1);
      scheduleTextureLoad(texture, false);
    }

    {
      auto ls = std::unique_lock{ m_sf.m_idToTexture_mutex };

      if (m_sf.m_idToTexture.size() < SAMPLER_FEEDBACK_MAX_TEXTURE_COUNT) {
        texture->m_samplerFeedbackStamp = m_sf.m_idToTexture_count.fetch_add(1);
        m_sf.m_idToTexture.push_back(texture);
      } else {
        assert(0);
        Logger::err("Sampler feedback stamp overflow!");
        texture->m_samplerFeedbackStamp = SAMPLER_FEEDBACK_INVALID;
      }
    }

    {
      FileWatch::get().watchTexture(this, texture);

      auto l = std::unique_lock{ m_assetHashToTextures_mutex };
      return m_assetHashToTextures.emplace(hash, texture).first->second;
    }
  }

  static size_t calcTextureMemoryBudget_Megabytes(DxvkDevice* device) {
    constexpr int MinBudgetMib = 32;

    if (RtxOptions::TextureManager::fixedBudgetEnable()) {
      return std::max(RtxOptions::TextureManager::fixedBudgetMiB(), MinBudgetMib);
    }


    // How much VRAM is free to use
    VkDeviceSize availableMemorySizeMib = 0;
    const VkPhysicalDeviceMemoryProperties memory = device->adapter()->memoryProperties();
    const DxvkAdapterMemoryInfo memHeapInfo = device->adapter()->getMemoryHeapInfo();
    for (uint32_t i = 0; i < memory.memoryHeapCount; i++) {
      bool isDeviceLocal = memory.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
      if (!isDeviceLocal) {
        continue;
      }

      VkDeviceSize remixFreeMemMib =
        (device->getMemoryStats(i).totalAllocated() >> 20) -
        (device->getMemoryStats(i).totalUsed() >> 20);

      VkDeviceSize memBudgetMib = memHeapInfo.heaps[i].memoryBudget >> 20;
      VkDeviceSize memUsedMib = (memHeapInfo.heaps[i].memoryAllocated >> 20) - remixFreeMemMib;

      if (memBudgetMib > memUsedMib) {
        availableMemorySizeMib = std::max(memBudgetMib - memUsedMib, availableMemorySizeMib);
      }
    }
    if (!device->getCommon()->getResources().isResourceReady()) {
      // Reserve space for various non-texture GPU resources (buffers, etc)

      const auto adaptiveResolutionReservedGPUMemoryMiB =
        static_cast<std::int32_t>(RtxOptions::adaptiveResolutionReservedGPUMemoryGiB() * MiBPerGiB);

      // Note: int32_t used for clamping behavior on underflow.
      availableMemorySizeMib = std::max(static_cast<std::int32_t>(availableMemorySizeMib) - adaptiveResolutionReservedGPUMemoryMiB, 0);
    }


    // How much VRAM is already allocated for existing textures
    VkDeviceSize currentUsageMib = 0;
    for (uint32_t i = 0; i < device->adapter()->memoryProperties().memoryHeapCount; i++) {
      bool isDeviceLocal = device->adapter()->memoryProperties().memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
      if (isDeviceLocal) {
        currentUsageMib += device->getMemoryStats(i).usedByCategory(DxvkMemoryStats::Category::RTXMaterialTexture) >> 20;
      }
    }


    // NOTE: the percentage needs to be a portion of the WHOLE range, not only the available mem
    VkDeviceSize wholeTextureBudgetMib = availableMemorySizeMib + currentUsageMib;


    float percentage = std::clamp(RtxOptions::TextureManager::budgetPercentageOfAvailableVram(), 0, 100) / 100.f;
    return std::max(size_t(wholeTextureBudgetMib * percentage), size_t(MinBudgetMib));
  }
}
