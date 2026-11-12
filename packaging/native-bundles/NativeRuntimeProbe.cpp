/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <stdexcept>

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#define REMIX_WINAPI_NO_INCLUDE
#define REMIX_WINAPI_NO_LIBRARY_LOADER
#define REMIXAPI
#define REMIXAPI_CALL
#define __stdcall
#endif
#include "remix_c.h"

// CPU-only API negotiation. Never call dxvk_Initialize, create a device, or submit work.
int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Pass the built runtime path");
#ifdef _WIN32
        const auto path = std::filesystem::absolute(std::filesystem::u8path(argv[1]));
        const auto library = LoadLibraryExW(path.c_str(), nullptr,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        if (!library) throw std::runtime_error("Runtime/dependency LoadLibraryExW failed");
        const auto initialize = reinterpret_cast<PFN_remixapi_InitializeLibrary>(
            GetProcAddress(library, "remixapi_InitializeLibrary"));
#else
        const auto library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        const auto initialize = reinterpret_cast<PFN_remixapi_InitializeLibrary>(
            dlsym(library, "remixapi_InitializeLibrary"));
#endif
        if (!initialize) throw std::runtime_error("Missing remixapi_InitializeLibrary export");
        struct GuardedInterface {
            uint64_t before = UINT64_C(0x735697abcddcba12);
            remixapi_Interface value{};
            uint64_t after = UINT64_C(0x3914fefdfc123456);
        } result;
        remixapi_InitializeLibraryInfo info{};
        info.sType = REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO;
        info.version = REMIXAPI_VERSION_MAKE(REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH);
        if (initialize(&info, &result.value) != REMIXAPI_ERROR_CODE_SUCCESS)
            throw std::runtime_error("Runtime rejects the producer's canonical API version");
        if (result.before != UINT64_C(0x735697abcddcba12) || result.after != UINT64_C(0x3914fefdfc123456))
            throw std::runtime_error("Runtime overwrote the canonical interface extent");
        static_assert(sizeof(remixapi_Interface) % sizeof(void*) == 0);
        constexpr auto pointers = sizeof(remixapi_Interface) / sizeof(void*);
        for (size_t i = 0; i < pointers; i++) {
            uintptr_t value;
            std::memcpy(&value, reinterpret_cast<const char*>(&result.value) + i * sizeof(void*), sizeof(value));
            if (!value) throw std::runtime_error("Runtime omitted a canonical interface function");
        }
        std::printf("{\"api\":\"%d.%d.%d\",\"interfacePointers\":%zu,\"apiCompatibilityTest\":\"passed\",\"gpuInitialized\":false}\n",
            REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH, pointers);
        // Exit releases the loader state. Explicit unload could invoke unrelated global teardown.
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "CPU-only native API gate failed: %s\n", error.what());
        return 1;
    }
}
