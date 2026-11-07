/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal POSIX/Linux stand-in for <windows.h>, used only to compile the
 * RTX Remix C API header (remix_c.h) on Linux. The Remix API surface that the
 * bridge uses only needs a handful of Windows types and macros; the real
 * <windows.h> is intentionally not pulled in on Linux.
 */
#ifndef RTX_REMIX_LINUX_WINDOWS_H_SHIM
#define RTX_REMIX_LINUX_WINDOWS_H_SHIM

#include <stdint.h>
#include <stddef.h>

typedef void* HANDLE;
typedef long  HRESULT;

#define WINAPI
#define CALLBACK

#ifndef __declspec
#define __declspec(d)
#endif

#endif /* RTX_REMIX_LINUX_WINDOWS_H_SHIM */
