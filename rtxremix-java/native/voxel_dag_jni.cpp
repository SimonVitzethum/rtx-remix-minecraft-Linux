/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#include <jni.h>

#include <algorithm>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>

#include "voxel_dag.h"

namespace {
  std::mutex registryMutex;
  std::map<jlong, std::shared_ptr<rtx::voxel::Page>> pages;
  jlong nextId = 1;

  jlong add(std::shared_ptr<rtx::voxel::Page> page) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (pages.size() >= 4 || nextId == std::numeric_limits<jlong>::max()) {
      throw std::logic_error("Native DAG handle admission rejected");
    }
    const jlong id = nextId++;
    pages.emplace(id, std::move(page));
    return id;
  }
  std::shared_ptr<rtx::voxel::Page> get(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = pages.find(handle);
    if (found == pages.end()) {
      throw std::invalid_argument("Unknown or destroyed native DAG handle");
    }
    return found->second;
  }
  rtx::voxel::Builder& builder(rtx::voxel::Page& page) {
    auto result = dynamic_cast<rtx::voxel::Builder*>(&page);
    if (!result) {
      throw std::logic_error("A disk DAG page is immutable");
    }
    return *result;
  }
  template<typename T, typename Function>
  T guard(JNIEnv* env, Function function, const char* failure = "java/lang/IllegalStateException") {
    try {
      return function();
    } catch (const std::invalid_argument& error) {
      if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
      }
    } catch (const std::bad_alloc& error) {
      if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), error.what());
      }
    } catch (const std::exception& error) {
      if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass(failure), error.what());
      }
    }
    return T {};
  }
  void* direct(JNIEnv* env, jobject buffer, uint64_t bytes, uint64_t maximum, bool writable = false) {
    if (!buffer || bytes > maximum) {
      throw std::invalid_argument("Missing or oversized native DAG direct buffer");
    }
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!address || capacity < 0 || uint64_t(capacity) < bytes) {
      throw std::invalid_argument("Native DAG buffer must be direct and large enough (position is ignored)");
    }
    if (writable) {
      const jclass type = env->GetObjectClass(buffer);
      const jmethodID method = env->GetMethodID(type, "isReadOnly", "()Z");
      const bool readOnly = method && env->CallBooleanMethod(buffer, method);
      env->DeleteLocalRef(type);
      if (env->ExceptionCheck() || readOnly) {
        throw std::invalid_argument("Native DAG destination must be writable");
      }
    }
    return address;
  }
  std::filesystem::path path(JNIEnv* env, jstring value) {
    if (!value || env->GetStringLength(value) == 0) {
      throw std::invalid_argument("Native DAG cache path is empty");
    }
    const jsize size = env->GetStringLength(value);
    if (size > 32768) {
      throw std::invalid_argument("Native DAG cache path is too long");
    }
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) {
      throw std::bad_alloc();
    }
    std::u16string text;
    try {
      text.assign(reinterpret_cast<const char16_t*>(chars), size_t(size));
    } catch (...) {
      env->ReleaseStringChars(value, chars);
      throw;
    }
    env->ReleaseStringChars(value, chars);
    if (text.find(char16_t(0)) != std::u16string::npos) {
      throw std::invalid_argument("Native DAG cache path contains a null character");
    }
    return std::filesystem::path(text);
  }
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_nvidia_rtxremix_VoxelDag_create(JNIEnv* env, jclass, jint lg, jint slabs) {
  return guard<jlong>(env, [&] { return add(std::make_shared<rtx::voxel::Builder>(uint32_t(lg), uint32_t(slabs))); });
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_surface(JNIEnv* env, jclass, jlong handle, jint first, jint count, jobject buffer) {
  guard<int>(env, [&] {
    void* data = direct(env, buffer, uint64_t(uint32_t(count)) * 4, 65536);
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    builder(*page).surface(uint32_t(first), uint32_t(count), data);
    return 0;
  });
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_exact(JNIEnv* env, jclass, jlong handle, jint cx, jint sy, jint cz, jobject buffer) {
  guard<int>(env, [&] {
    void* data = direct(env, buffer, 8192, 8192);
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    builder(*page).exact(uint32_t(cx), uint32_t(sy), uint32_t(cz), data);
    return 0;
  });
}
JNIEXPORT jint JNICALL Java_com_nvidia_rtxremix_VoxelDag_finish(JNIEnv* env, jclass, jlong handle) {
  return guard<jint>(env, [&] {
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    if (auto writable = dynamic_cast<rtx::voxel::Builder*>(page.get())) {
      return jint(writable->finish());
    }
    return jint(page->nodeCount());
  });
}
JNIEXPORT jint JNICALL Java_com_nvidia_rtxremix_VoxelDag_root(JNIEnv* env, jclass, jlong handle, jint slab) {
  return guard<jint>(env, [&] {
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    return jint(page->root(uint32_t(slab)));
  });
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_copy(JNIEnv* env, jclass, jlong handle, jint first, jint count, jint offset, jobject buffer) {
  guard<int>(env, [&] {
    if (offset < 0) {
      throw std::invalid_argument("Native DAG relocation must be nonnegative");
    }
    void* data = direct(env, buffer, uint64_t(uint32_t(count)) * 4, 8u << 20, true);
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    page->copy(uint32_t(first), uint32_t(count), uint32_t(offset), data);
    return 0;
  });
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_destroy(JNIEnv* env, jclass, jlong handle) {
  guard<int>(env, [&] {
    std::shared_ptr<rtx::voxel::Page> removed;
    {
      std::lock_guard<std::mutex> lock(registryMutex);
      const auto found = pages.find(handle);
      if (found == pages.end()) {
        throw std::invalid_argument("Unknown or destroyed native DAG handle");
      }
      removed = std::move(found->second);
      pages.erase(found);
    }
    return 0;
  });
}
JNIEXPORT jlongArray JNICALL Java_com_nvidia_rtxremix_VoxelDag_metrics(JNIEnv* env, jclass) {
  return guard<jlongArray>(env, [&] {
    const auto values = rtx::voxel::metrics();
    std::array<jlong, 16> result;
    std::copy(values.begin(), values.end(), result.begin());
    jlongArray array = env->NewLongArray(jsize(result.size()));
    if (array) {
      env->SetLongArrayRegion(array, 0, jsize(result.size()), result.data());
    }
    return array;
  });
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_save(JNIEnv* env, jclass, jlong handle, jstring filename) {
  guard<int>(env, [&] {
    const auto file = path(env, filename);
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    rtx::voxel::save(*page, file);
    return 0;
  }, "java/io/IOException");
}
JNIEXPORT jlong JNICALL Java_com_nvidia_rtxremix_VoxelDag_open(JNIEnv* env, jclass, jstring filename) {
  return guard<jlong>(env, [&] { return add(rtx::voxel::open(path(env, filename))); }, "java/io/IOException");
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_overlay(JNIEnv* env, jclass, jlong handle, jstring directory, jint sx, jint sz, jint minY) {
  guard<int>(env, [&] {
    const auto dir = path(env, directory);
    auto page = get(handle);
    std::lock_guard<std::mutex> lock(page->mutex);
    builder(*page).overlay(dir, sx, sz, minY);
    return 0;
  }, "java/io/IOException");
}
JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_VoxelDag_recordSurface(JNIEnv* env, jclass, jstring directory, jint cx, jint cz, jobject buffer) {
  guard<int>(env, [&] {
    void* data = direct(env, buffer, 1024, 1024);
    rtx::voxel::recordSurface(path(env, directory), cx, cz, data);
    return 0;
  }, "java/io/IOException");
}
} // extern "C"
